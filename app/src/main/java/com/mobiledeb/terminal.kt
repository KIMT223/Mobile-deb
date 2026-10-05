package com.mobiledeb

import android.content.Context
import android.os.FileObserver
import android.os.SystemClock
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
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 单个 proot + Debian 会话。一个实例对应一个终端。
 * 多个实例共享同一份 rootfs（filesDir/rootfs），首次解压由 TerminalService 加锁保证只做一次。
 *
 * 终端大小同步：rootfs 里用 script 分配 PTY，并把 PTY 设备名写到 /tmp/.mdtty-<id>
 * （每个会话一个文件，避免多会话互相覆盖）；窗口大小变化时另起一个 proot 执行
 * `stty -F <pty> rows R cols C`，内核会自动给 bash 发 SIGWINCH。
 */
class DebianTerminal(
    private val ctx: Context,
    private val id: Int,
    private val onOutput: (ByteArray) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    companion object {
        val SHARED_DIR = File("/storage/emulated/0/mobile_deb")
        private const val PATH_ENV =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        private const val RESIZE_TIMEOUT_MS = 8000L
    }

    private val ttyRel = "tmp/.mdtty-$id"
    private val ttyBasename = ".mdtty-$id"

    private val rootfsDir = File(ctx.filesDir, "rootfs")
    private val shmDir = File(ctx.filesDir, "shm")
    private val fakeProcDir = File(ctx.filesDir, "procfake")
    @Volatile private var extraBinds: List<String> = emptyList()
    @Volatile private var process: Process? = null
    @Volatile private var stdin: OutputStream? = null
    @Volatile private var rootDir: File? = null
    @Volatile private var supportsResize = false
    @Volatile private var wantedSize: Pair<Int, Int>? = null
    @Volatile private var appliedSize: Pair<Int, Int>? = null
    @Volatile private var stopped = false

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

    /** 解压 rootfs，返回是否成功。耗时操作，请在后台线程调用（调用方负责加锁）。 */
    fun prepareRootfs(progress: (String) -> Unit): Boolean {
        val archive = findArchive()
        if (archive == null) {
            progress("未找到 rootfs 压缩包。\n请把 Debian rootfs (.tar.gz / .tar.xz) 放到:\n${SHARED_DIR.path}\n放好后点侧边栏「+」重试。\n")
            return false
        }
        progress("解压 ${archive.name} …\n")
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

    private fun inside(f: File, destPath: String): Boolean = try {
        (f.canonicalPath + File.separator).startsWith(destPath)
    } catch (_: IOException) {
        false
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
                val name = e.name.removePrefix("./")
                if (name.isNotEmpty()) {
                    val out = File(dest, name)
                    val outParent = out.parentFile
                    if (outParent == null || !inside(outParent, destPath)) {
                        e = tar.nextTarEntry
                        continue
                    }
                    when {
                        e.isDirectory -> {
                            out.mkdirs()
                            runCatching { Os.chmod(out.path, (e.mode and 0xFFF) or 0x1C0) }
                        }
                        e.isSymbolicLink -> {
                            out.parentFile?.mkdirs()
                            out.delete()
                            val ok = runCatching { Os.symlink(e.linkName, out.path) }.isSuccess
                            if (!ok) runCatching { out.writeText(e.linkName) }
                        }
                        e.isLink -> {
                            // 硬链接目标必须在解压目录内，防止借 ../ 读取/链接到外部文件
                            val target = File(dest, e.linkName.removePrefix("./"))
                            if (inside(target, destPath)) {
                                out.parentFile?.mkdirs()
                                out.delete()
                                val ok = runCatching { Os.link(target.path, out.path) }.isSuccess
                                if (!ok) runCatching { target.copyTo(out, overwrite = true) }
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
            cmd += extraBinds
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
            if (File(libDir, "libtalloc.so").exists()) put("LD_LIBRARY_PATH", libDir)
            if (loader.exists()) put("PROOT_LOADER", loader.path)
        }
    }

    // ------------------------------------------------- /dev/shm 与伪造的 /proc 文件

    private fun readable(path: String): Boolean =
        runCatching { FileInputStream(path).use { it.read() }; true }.getOrDefault(false)

    /**
     * Android 没有 /dev/shm，且 8+ 起普通应用读不了 /proc/stat、/proc/loadavg 等。
     * 这里准备：
     *  - filesDir/shm（1777）→ 绑到 /dev/shm
     *  - 宿主上读不了的 /proc 文件，用占位内容的文件覆盖（能读就不动它）
     * 返回要追加给 proot 的 -b 参数。数值是占位的：CPU 核数取真实值，使用率/负载恒为 0。
     */
    private fun prepareExtraBinds(): List<String> {
        val args = mutableListOf<String>()

        runCatching {
            shmDir.mkdirs()
            Os.chmod(shmDir.path, 0x3FF)            // 01777
            args += listOf("-b", "${shmDir.path}:/dev/shm")
        }

        runCatching {
            fakeProcDir.mkdirs()
            val upMs = SystemClock.elapsedRealtime()
            val up = String.format(Locale.US, "%.2f", upMs / 1000.0)
            val ticks = upMs / 10                   // USER_HZ = 100
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val btime = (System.currentTimeMillis() - upMs) / 1000

            fun fake(name: String, content: String) {
                if (readable("/proc/$name")) return
                val f = File(fakeProcDir, name)
                f.writeText(content)
                args += listOf("-b", "${f.path}:/proc/$name")
            }

            val user = ticks / 4
            val sys = ticks / 8
            val idle = ticks - user - sys
            val per = "$user 0 $sys $idle 0 0 0 0 0 0"
            val total = "${user * cores} 0 ${sys * cores} ${idle * cores} 0 0 0 0 0 0"
            val stat = StringBuilder("cpu  $total\n")
            for (i in 0 until cores) stat.append("cpu$i $per\n")
            stat.append("intr 0\nctxt 0\nbtime $btime\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n")

            fake("stat", stat.toString())
            fake("loadavg", "0.00 0.00 0.00 1/100 1\n")
            fake("uptime", "$up $up\n")
            fake("version", "Linux version 6.1.0-mobiledeb (build@localhost) (gcc) #1 SMP PREEMPT\n")
        }

        return args
    }

    // --------------------------------------------------------------- process

    fun start() {
        val root = resolveRoot()
        if (root == null) {
            onMessage("rootfs 未就绪\n"); onExit(-1); return
        }
        extraBinds = prepareExtraBinds()
        val cmd = prootBase(root, true)
        if (cmd == null) {
            onMessage("缺少 libproot.so（应放在 jniLibs/arm64-v8a/）\n"); onExit(-1); return
        }

        stopped = false
        ensureExecutors()
        rootDir = root
        appliedSize = null
        supportsResize = false

        runCatching {
            val rc = File(root, "etc/resolv.conf")
            if (!rc.exists() || rc.length() == 0L || rc.readText().isBlank()) {
                rc.delete()
                rc.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            }
        }
        File(root, "tmp").mkdirs()
        File(root, ttyRel).delete()

        val hasScript = File(root, "usr/bin/script").exists()
        supportsResize = hasScript && File(root, "usr/bin/stty").exists()
        val shell =
            if (hasScript)
                listOf("/usr/bin/script", "-qfc", "tty > /$ttyRel; exec /bin/bash -l", "/dev/null")
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
                        if (!stopped) onOutput(buf.copyOf(n))
                    }
                } catch (_: IOException) {
                }
                val code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
                if (!stopped) onExit(code)
            }.apply { isDaemon = true; name = "debian-stdout-$id" }.start()

            if (supportsResize && wantedSize != null) triggerResize()
        } catch (e: Exception) {
            onMessage("启动失败: ${e.message}\n")
            onExit(-1)
        }
    }

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
            p.inputStream.readBytes()
            if (p.waitFor() == 0) {
                appliedSize = size
                if (wantedSize != size) triggerResize()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 等待 shell 把 PTY 设备名写进 tty 文件。
     * 用 Semaphore 而不是 latch：事件消费后会重新阻塞，文件还是空的那段时间不会空转。
     */
    private fun waitForTtyFile(root: File, timeoutMs: Long, stillValid: () -> Boolean): String? {
        val ttyFile = File(root, ttyRel)
        readTtyPath(ttyFile)?.let { return it }

        val tmpDir = File(root, "tmp")
        if (!tmpDir.isDirectory) return null

        val sem = Semaphore(0)
        @Suppress("DEPRECATION")
        val observer = object : FileObserver(
            tmpDir.absolutePath,
            CREATE or MOVED_TO or CLOSE_WRITE or MODIFY
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (path == ttyBasename) sem.release()
            }
        }
        observer.startWatching()
        try {
            readTtyPath(ttyFile)?.let { return it }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                if (!stillValid()) return null
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                sem.tryAcquire(minOf(remaining, 200L), TimeUnit.MILLISECONDS)
                sem.drainPermits()
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
        stopped = true
        val p = process
        val s = stdin
        process = null
        stdin = null
        runCatching { s?.close() }
        runCatching { p?.destroy() }
        runCatching { p?.destroyForcibly() }
        synchronized(execLock) {
            runCatching { writer?.shutdownNow() }
            runCatching { resizer?.shutdownNow() }
            writer = null
            resizer = null
        }
    }
}
