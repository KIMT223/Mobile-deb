package com.mobiledeb

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.ByteArrayOutputStream

/**
 * 持有所有终端会话的前台服务。Activity 只是「视图」：
 * 旋转 / 被系统重建 / 用户划掉再打开，都通过 attach() 取回会话列表和最近输出并重放。
 */
class TerminalService : Service() {

    interface Listener {
        fun onOutput(id: Int, data: ByteArray)
        fun onStatus(id: Int, running: Boolean)
        fun onStopped()
    }

    class SessionInfo(
        val id: Int,
        val name: String,
        val running: Boolean,
        val backlog: ByteArray,
    )

    /** 每个会话保留最近 MAX 字节输出，供 Activity 重建后重放。 */
    private class Backlog(private val max: Int = 256 * 1024) {
        private val out = ByteArrayOutputStream()

        @Synchronized
        fun append(b: ByteArray) {
            out.write(b, 0, b.size)
            if (out.size() > max * 2) {
                val all = out.toByteArray()
                val start = cutPoint(all)
                out.reset()
                out.write(all, start, all.size - start)
            }
        }

        @Synchronized
        fun snapshot(): ByteArray {
            val all = out.toByteArray()
            if (all.size <= max) return all
            val start = cutPoint(all)
            // 前面补一个 SGR 重置，避免被截断的颜色状态串到后面
            return RESET + all.copyOfRange(start, all.size)
        }

        /** 从尾部往前留 max 字节，并跳过 UTF-8 续字节，避免从字符中间截断。 */
        private fun cutPoint(all: ByteArray): Int {
            var start = (all.size - max).coerceAtLeast(0)
            while (start < all.size && (all[start].toInt() and 0xC0) == 0x80) start++
            return start
        }

        private companion object {
            val RESET = byteArrayOf(0x1B, '['.code.toByte(), '0'.code.toByte(), 'm'.code.toByte())
        }
    }

    private class Session(
        val id: Int,
        @Volatile var name: String,
        val term: DebianTerminal,
    ) {
        val backlog = Backlog()
        @Volatile var running = true
    }

    inner class LocalBinder : Binder() {
        val service: TerminalService get() = this@TerminalService
    }

    companion object {
        const val ACTION_STOP = "com.mobiledeb.action.STOP"
        private const val CHANNEL_ID = "terminal"
        private const val NOTIF_ID = 1
    }

    private val binder = LocalBinder()

    private val lock = Any()
    private val sessions = mutableListOf<Session>()   // 受 lock 保护
    private var nextId = 1                            // 受 lock 保护
    private var listener: Listener? = null            // 受 lock 保护

    /** 最后一次激活的标签，Activity 重建后用来恢复。 */
    @Volatile var activeId = -1

    private val rootfsLock = Any()
    @Volatile private var rootfsPrepared = false
    @Volatile private var preparing = false
    @Volatile private var stopping = false

    private var wakeLock: PowerManager.WakeLock? = null

    // ------------------------------------------------------------ 生命周期

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopAll()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        val list = synchronized(lock) { sessions.toList().also { sessions.clear() } }
        for (s in list) s.term.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    // ------------------------------------------------------------ Activity 接口

    /** 注册监听并取回快照；两步在同一把锁里完成，保证输出既不丢也不重复。 */
    fun attach(l: Listener): List<SessionInfo> = synchronized(lock) {
        listener = l
        sessions.map { SessionInfo(it.id, it.name, it.running, it.backlog.snapshot()) }
    }

    fun detach(l: Listener) {
        synchronized(lock) { if (listener === l) listener = null }
    }

    fun sessionIds(): List<Int> = synchronized(lock) { sessions.map { it.id } }

    /** 只创建会话对象，不启动进程；Activity 建好标签后再调 boot()。 */
    fun newSession(): SessionInfo {
        val s = synchronized(lock) {
            val id = nextId++
            val term = DebianTerminal(
                ctx = applicationContext,
                id = id,
                onOutput = { emit(id, it) },
                onMessage = { emitText(id, it) },
                onExit = { code -> onTermExit(id, code) },
            )
            Session(id, "终端 $id", term).also {
                sessions.add(it)
                activeId = id
            }
        }
        acquireWakeLock()
        updateNotification()
        return SessionInfo(s.id, s.name, true, ByteArray(0))
    }

    fun boot(id: Int) {
        val s = find(id) ?: return
        Thread {
            DebianTerminal.SHARED_DIR.mkdirs()
            if (!ensureRootfs(s)) {
                s.running = false
                emitStatus(id, false)
                return@Thread
            }
            if (find(id) == null) return@Thread          // 解压期间被关掉了
            s.term.start()
            if (find(id) == null) {                       // start 期间被关掉了
                s.term.stop()
            } else if (s.term.isRunning()) {              // 启动失败时 onExit 已经标记过了
                s.running = true
                emitStatus(id, true)
            }
        }.apply { name = "debian-boot-$id"; isDaemon = true }.start()
    }

    fun input(id: Int, text: String) {
        find(id)?.term?.write(text)
    }

    fun resize(id: Int, cols: Int, rows: Int) {
        find(id)?.term?.resize(cols, rows)
    }

    fun rename(id: Int, name: String) {
        find(id)?.name = name
    }

    fun closeSession(id: Int) {
        val found: Session
        val empty: Boolean
        synchronized(lock) {
            found = sessions.firstOrNull { it.id == id } ?: return
            sessions.remove(found)
            if (activeId == id) activeId = sessions.lastOrNull()?.id ?: -1
            empty = sessions.isEmpty()
        }
        found.term.stop()
        if (empty) releaseWakeLock()
        updateNotification()
    }

    fun stopAll() {
        stopping = true
        val (list, l) = synchronized(lock) {
            val copy = sessions.toList()
            sessions.clear()
            activeId = -1
            copy to listener
        }
        for (s in list) s.term.stop()
        releaseWakeLock()
        l?.onStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------ rootfs（全局只解压一次）

    private fun ensureRootfs(s: Session): Boolean {
        if (rootfsPrepared) return true
        if (preparing) emitText(s.id, "等待另一个终端完成 rootfs 解压…\n")
        return synchronized(rootfsLock) {
            if (rootfsPrepared) return@synchronized true
            preparing = true
            try {
                val ok = s.term.isRootfsReady() || s.term.prepareRootfs { emitText(s.id, it) }
                if (ok) rootfsPrepared = true
                ok
            } finally {
                preparing = false
            }
        }
    }

    // ------------------------------------------------------------ 输出

    private fun find(id: Int): Session? = synchronized(lock) { sessions.firstOrNull { it.id == id } }

    private fun emit(id: Int, data: ByteArray) {
        var l: Listener? = null
        synchronized(lock) {
            val s = sessions.firstOrNull { it.id == id } ?: return
            s.backlog.append(data)
            l = listener
        }
        l?.onOutput(id, data)
    }

    private fun emitText(id: Int, s: String) {
        val n = s.replace("\r\n", "\n").replace("\n", "\r\n")
        emit(id, n.toByteArray(Charsets.UTF_8))
    }

    private fun emitStatus(id: Int, running: Boolean) {
        val l = synchronized(lock) { listener }
        l?.onStatus(id, running)
    }

    private fun onTermExit(id: Int, code: Int) {
        val s = find(id) ?: return
        s.running = false
        emitStatus(id, false)
        emitText(id, "\n[进程已退出，code=$code]\n")
    }

    // ------------------------------------------------------------ 唤醒锁

    @Synchronized
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mobiledeb:terminal").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    @Synchronized
    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ------------------------------------------------------------ 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(CHANNEL_ID, "终端会话", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val count = synchronized(lock) { sessions.size }
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, TerminalService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            Notification.Builder(this)
        val icon = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
        return b.setSmallIcon(icon)
            .setContentTitle("Mobile Debian")
            .setContentText(if (count > 0) "$count 个终端运行中" else "已就绪")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "退出", stopPi)
            .build()
    }

    private fun updateNotification() {
        if (stopping) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }
}
