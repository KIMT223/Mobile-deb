package com.mobiledeb

import android.content.Context
import android.os.FileObserver
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/*
 * PRoot + Debian 进程管理。
 *
 * 共享目录 /storage/emulated/0/mobile_deb/ 放 rootfs 压缩包 (.tar.gz/.tgz/.tar.xz)，
 * 首次启动解压到 filesDir/rootfs（内部存储支持 symlink 和 chmod，sdcard 不支持）。
 *
 * 终端大小同步：rootfs 里用 script 分配 PTY，并把 PTY 设备名写到 /tmp/.mdtty；
 * 窗口大小变化时另起一个 proot 执行 `stty -F <pty> rows R cols C`，内核会自动给 bash 发 SIGWINCH。
 */
class DebianTerminal(
    private val ctx: Context,
    private val onOutput: (ByteArray) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    companion object {
        val SHARED_DIR = File("/storage/emulated/0/mobile_deb")
        private const val PATH_ENV =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        private const val TTY_FILE = "tmp/.mdtty"
        private const val TTY_BASENAME = ".mdtty"
        private const val RESIZE_TIMEOUT_MS = 8000L
    }

    private val rootfsDir = File(ctx.filesDir, "rootfs")
    @Volatile private var process: Process? = null
    @Volatile private var stdin: OutputStream? = null
    @Volatile private var rootDir: File? = null
    @Volatile private var supportsResize = false
    @Volatile private var wantedSize: Pair<Int, Int>? = null
    @Volatile private var appliedSize: Pair<Int, Int>? = null

    // stop() 后会关闭这两个 executor，start() 时按需重建，避免二次启动时被拒绝。
    private val execLock = Any()
    @Volatile private var writer: ExecutorService? = null
    @Volatile private var resizer: ExecutorService? = null

    private fun ensureExecutors() {
        synchronized(execLock) {
            val w = writer
            if (w == null || w.isShutdown) writer = Executors.newSingleThreadExecutor()
            val r = resizer
            if (r == null || r.isShutdown) resizer = Executors.newSingleThreadExecutor()
        }
    }

    // ---------------------------------------------------------------- rootfs

    /** 返回真正的根目录（兼容压缩包带一层顶级目录的情况）。 */
    private fun resolveRoot(): File? {
        if (File(rootfsDir, "usr").isDirectory) return rootfsDir
        val child = rootfsDir.listFiles()?.singleOrNull { it.isDirectory }
        if (child != null && File(child, "usr").isDirectory) return child
        return null
    }

    fun isRootfsReady() = resolveRoot() != null

    private fun findArchive(): File? =
        SHARED_DIR.listFiles()
            ?.filter {
                it.isFile && (it.name.endsWith(".tar.gz") || it.name.endsWith(".tgz") ||
                        it.name.endsWith(".tar.xz"))
            }
            ?.sortedBy { it.name }
            ?.firstOrNull()

    /** 解压 rootfs，返回是否成功。耗时操作，请在后台线程调用。 */
    fun prepareRootfs(progress: (String) -> Unit): Boolean {
        val archive = findArchive()
        if (archive == null) {
            progress("未找到 rootfs 压缩包。\n请把 Debian rootfs (.tar.gz / .tar.xz) 放到:\n${SHARED_DIR.path}\n放好后点底部「重启」。\n")
            return false
        }
        progress("解压 ${archive.name} …\n")
        // 先解压到临时目录，成功后再替换，失败不会破坏已有 rootfs
        val tmp = File(ctx.filesDir, "rootfs.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        return try {
            extract(archive, tmp, progress)
            val valid = File(tmp, "usr").isDirectory ||
                    tmp.listFiles()?.singleOrNull { it.isDirectory }?.let { File(it, "usr").isDirectory } == true
            if (!valid) {
                progress("解压完成，但没找到 usr/ 目录，压缩包可能不是 rootfs。\n")
                tmp.deleteRecursively()
                false
            } else {
                rootfsDir.deleteRecursively()
                if (!tmp.renameTo(rootfsDir)) throw IOException("重命名 rootfs 目录失败")
                progress("解压完成。\n")
                true
            }
        } catch (e: Exception) {
            progress("解压失败: ${e.message}\n")
            tmp.deleteRecursively()
            false
        }
    }

    private fun extract(archive: File, dest: File, progress: (String) -> Unit) {
        val raw = BufferedInputStream(FileInputStream(archive), 1 shl 16)
        val decompressed: InputStream =
            if (archive.name.endsWith(".xz")) XZCompressorInputStream(raw)
            else GzipCompressorInputStream(raw)
        val destPath = dest.canonicalPath + File.separator
        var count = 0

        TarArchiveInputStream(decompressed).use { tar ->
            var e = tar.nextTarEntry
            while (e != null) {
                // 兼容 PAX/GNU 长名头：commons-compress 会自动展开成普通 entry
                val name = e.name.removePrefix("./")
                if (name.isNotEmpty()) {
                    val out = File(dest, name)
                    // 防路径穿越
                    val outParent = out.parentFile
                    if (outParent == null ||
                        !(outParent.canonicalPath + File.separator).startsWith(destPath)
                    ) {
                        e = tar.nextTarEntry
                        continue
                    }
                    when {
                        e.isDirectory -> {
                            out.mkdirs()
                            // chmod 在个别文件系统上可能 EPERM/ENOTSUP，忽略失败
                            runCatching { Os.chmod(out.path, (e.mode and 0xFFF) or 0x1C0) }
                        }
                        e.isSymbolicLink -> {
                            out.parentFile?.mkdirs()
                            out.delete()
                            val ok = runCatching { Os.symlink(e.linkName, out.path) }.isSuccess
                            if (!ok) {
                                // 退化成一个普通文本文件，内容为链接目标，至少不阻塞解压
                                runCatching { out.writeText(e.linkName) }
                            }
                        }
                        e.isLink -> {
                            out.parentFile?.mkdirs()
                            out.delete()
                            val target = File(dest, e.linkName.removePrefix("./"))
                            val ok = runCatching { Os.link(target.path, out.path) }.isSuccess
                            if (!ok) {
                                runCatching { target.copyTo(out, overwrite = true) }
                            }
                        }
                        e.isFile -> {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { fos -> tar.copyTo(fos, 1 shl 16) }
                            runCatching { Os.chmod(out.path, (e.mode and 0xFFF) or 0x180) }
                        }
                        else -> { /* 设备节点等跳过，/dev 由 bind 提供 */ }
                    }
                    if (++count % 2000 == 0) progress("已处理 $count 个文件…\n")
                }
                e = tar.nextTarEntry
            }
        }
    }

    // --------------------------------------------------------------- proot

    private fun libDir() = ctx.applicationInfo.nativeLibraryDir

    /** proot 公共参数；full=true 时额外挂载 /sys 和共享目录。 */
    private fun prootBase(root: File, full: Boolean): MutableList<String>? {
        val proot = File(libDir(), "libproot.so")
        if (!proot.exists()) return null
        val cmd = mutableListOf(
            proot.path, "--link2symlink", "-0", "-r", root.path,
            "-b", "/dev", "-b", "/proc",
        )
        if (full) {
            cmd += listOf("-b", "/sys")
            if (SHARED_DIR.isDirectory) cmd += listOf("-b", "${SHARED_DIR.path}:/mnt/shared")
        }
        return cmd
    }

    private fun applyEnv(pb: ProcessBuilder) {
        val libDir = libDir()
        val tmp = File(ctx.cacheDir, "proot-tmp").apply { mkdirs() }
        val loader = File(libDir, "libproot-loader.so")
        pb.environment().apply {
            put("PROOT_TMP_DIR", tmp.path)
            put("PROOT_NO_SECCOMP", "1")
            // 仅动态链接版 proot 需要；静态版不要设，否则会泄漏进 Debian
            if (File(libDir, "libtalloc.so").exists()) put("LD_LIBRARY_PATH", libDir)
            if (loader.exists()) put("PROOT_LOADER", loader.path)
        }
    }

    // --------------------------------------------------------------- process

    fun start() {
        val root = resolveRoot()
        if (root == null) {
            onMessage("rootfs 未就绪\n"); onExit(-1); return
        }
        val cmd = prootBase(root, true)
        if (cmd == null) {
            onMessage("缺少 libproot.so（应放在 jniLibs/arm64-v8a/）\n"); onExit(-1); return
        }

        ensureExecutors()
        rootDir = root
        appliedSize = null
        supportsResize = false

        runCatching {
            val rc = File(root, "etc/resolv.conf")
            if (!rc.exists() || rc.length() == 0L || rc.readText().isBlank()) {
                rc.delete() // 可能是指向 /run 的悬空符号链接
                rc.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            }
        }
        // 确保 /tmp 存在，否则 script 的 `tty > /tmp/.mdtty` 会静默失败
        File(root, "tmp").mkdirs()
        File(root, TTY_FILE).delete()

        // 用 script 在 rootfs 内分配 PTY，并记录 PTY 设备名，供后续调整窗口大小
        val hasScript = File(root, "usr/bin/script").exists()
        supportsResize = hasScript && File(root, "usr/bin/stty").exists()
        val shell =
            if (hasScript)
                listOf("/usr/bin/script", "-qfc", "tty > /$TTY_FILE; exec /bin/bash -l", "/dev/null")
            else listOf("/bin/bash", "-i")

        if (!supportsResize) {
            onMessage("[提示] rootfs 缺少 script/stty，终端不会跟随窗口大小变化。\n")
        }

        cmd += listOf(
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "TERM=xterm-256color", "COLORTERM=truecolor",
            "LANG=C.UTF-8", "PATH=$PATH_ENV",
            "GLIBC_TUNABLES=glibc.pthread.rseq=0",
        )
        cmd += shell

        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        applyEnv(pb)

        try {
            val p = pb.start()
            process = p
            stdin = p.outputStream

            Thread {
                val ins = p.inputStream
                val buf = ByteArray(8192)
                try {
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        onOutput(buf.copyOf(n))
                    }
                } catch (_: IOException) {
                }
                val code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
                onExit(code)
            }.apply { isDaemon = true; name = "debian-stdout" }.start()

            if (supportsResize && wantedSize != null) triggerResize()
        } catch (e: Exception) {
            onMessage("启动失败: ${e.message}\n")
            onExit(-1)
        }
    }

    /** 向 stdin 写入原始文本（含控制字符）。 */
    fun write(text: String) {
        val w = writer ?: return
        try {
            w.execute {
                try {
                    stdin?.let { it.write(text.toByteArray(Charsets.UTF_8)); it.flush() }
                } catch (_: IOException) {
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    /** 终端窗口大小变化（可在进程启动前调用，启动后自动应用）。 */
    fun resize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        wantedSize = cols to rows
        if (isRunning() && supportsResize) triggerResize()
    }

    private fun triggerResize() {
        val r = resizer ?: return
        try {
            r.execute { applyResize() }
        } catch (_: Exception) {
        }
    }

    private fun applyResize() {
        val size = wantedSize ?: return
        if (size == appliedSize) return
        val root = rootDir ?: return
        if (!isRunning()) return

        // 用 FileObserver + CountDownLatch 等 PTY 设备名出现；期间窗口又变了就放弃本次
        val tty = waitForTtyFile(root, RESIZE_TIMEOUT_MS) { isRunning() && wantedSize == size }
            ?: return

        val cmd = prootBase(root, false) ?: return
        cmd += listOf(
            "/usr/bin/env", "-i", "/usr/bin/stty", "-F", tty,
            "rows", size.second.toString(), "cols", size.first.toString(),
        )
        try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            applyEnv(pb)
            val p = pb.start()
            // 排空输出，避免缓冲区满卡死
            p.inputStream.readBytes()
            if (p.waitFor() == 0) {
                appliedSize = size
                // 执行期间窗口又变了，再来一次
                if (wantedSize != size) triggerResize()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 等 /tmp/.mdtty 写出合法的 /dev/... 路径。
     * - 先做一次同步检查；
     * - 之后注册 FileObserver，文件创建/写入完成时立刻唤醒；
     * - 每 200ms 也会重新读文件，防止 FileObserver 漏事件；
     * - stillValid 返回 false 时提前退出。
     */
    private fun waitForTtyFile(root: File, timeoutMs: Long, stillValid: () -> Boolean): String? {
        val ttyFile = File(root, TTY_FILE)
        readTtyPath(ttyFile)?.let { return it }

        val tmpDir = File(root, "tmp")
        if (!tmpDir.isDirectory) return null

        val latch = CountDownLatch(1)
        @Suppress("DEPRECATION")
        val observer = object : FileObserver(
            tmpDir.absolutePath,
            CREATE or MOVED_TO or CLOSE_WRITE or MODIFY
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (path == TTY_BASENAME) latch.countDown()
            }
        }
        observer.startWatching()
        try {
            // 注册后再检查一次，避免在第一次检查和注册之间文件已经出现
            readTtyPath(ttyFile)?.let { return it }

            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (!stillValid()) return null
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                latch.await(minOf(remaining, 200L), TimeUnit.MILLISECONDS)
                // 无论 latch 是否触发，都重新读文件，防止 FileObserver 漏事件
                readTtyPath(ttyFile)?.let { return it }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { observer.stopWatching() }
        }
        return null
    }

    private fun readTtyPath(f: File): String? = try {
        if (!f.isFile) null
        else f.readText().trim().takeIf { it.startsWith("/dev/") }
    } catch (_: Exception) {
        null
    }

    fun isRunning() = process?.isAlive == true

    fun stop() {
        val p = process
        val s = stdin
        process = null
        stdin = null
        runCatching { s?.close() }
        runCatching { p?.destroy() }
        runCatching { p?.destroyForcibly() }
        // 关闭并释放 executor，下次 start() 时 ensureExecutors() 会重建
        synchronized(execLock) {
            runCatching { writer?.shutdownNow() }
            runCatching { resizer?.shutdownNow() }
            writer = null
            resizer = null
        }
    }
}