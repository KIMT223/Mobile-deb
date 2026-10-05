package com.mobiledeb

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher

/**
 * Activity 只是视图：会话全部由 TerminalService 持有。
 * 旋转 / 被系统重建 / 划掉后重开，都会重新绑定服务并重放各会话最近的输出。
 */
class MainActivity : Activity() {

    private companion object {
        /** 仅存在于界面的「提示」标签（比如缺权限时），不对应任何会话。 */
        const val HINT_ID = 0
        const val REQ_STORAGE = 1
        const val REQ_NOTIFY = 2
    }

    private lateinit var ui: TerminalUi

    private var service: TerminalService? = null
    private var bound = false
    private var attached = false
    private var hintShown = false

    // 重放期间到达的实时输出先挂起，重放完再按序写入，避免新旧输出乱序
    private val gate = Any()
    private var replaying = false
    private val held = ArrayList<Pair<Int, ByteArray>>()

    private val listener = object : TerminalService.Listener {
        override fun onOutput(id: Int, data: ByteArray) {
            synchronized(gate) {
                if (replaying) {
                    held.add(id to data)
                    return
                }
            }
            ui.writeBytes(id, data)
        }

        override fun onStatus(id: Int, running: Boolean) {
            ui.setSessionStatus(id, running)
        }

        override fun onStopped() {
            runOnUiThread { finish() }
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as TerminalService.LocalBinder).service
            tryAttach()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            attached = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        ui = TerminalUi(this)
        setContentView(ui.root)

        ui.onInput = { id, data -> service?.input(id, data) }
        ui.onKey = { id, data -> service?.input(id, data) }
        ui.onResize = { id, c, r -> service?.resize(id, c, r) }
        // 非当前标签的 ready 带的是隐藏状态下的默认 80x24，不能拿去改 pty；切换标签时会再发一次真实尺寸
        ui.onReady = { id, c, r -> if (id == ui.getCurrentId()) service?.resize(id, c, r) }
        ui.onPageReady = { tryAttach() }
        ui.onNewSession = { createAndStartSession() }
        ui.onSwitchSession = { id -> switchTo(id) }
        ui.onCloseSession = { id -> closeSession(id) }
        ui.onRenameSession = { id, newName -> service?.rename(id, newName) }

        if (Build.VERSION.SDK_INT >= 33) {
            // targetSdk 36 起 onBackPressed 不再回调，改走 dispatcher；两者不会同时生效
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                if (!ui.handleBack()) finish()
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            }
        }

        val intent = Intent(this, TerminalService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }
        bound = bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!ui.handleBack()) super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (!hasStorageAccess()) return
        tryAttach()
        val s = service
        if (attached && s != null) {
            if (s.sessionIds().isEmpty()) createAndStartSession() else clearHint()
        }
    }

    override fun onDestroy() {
        service?.detach(listener)
        if (bound) {
            runCatching { unbindService(conn) }
            bound = false
        }
        ui.destroy()
        super.onDestroy()          // 注意：不停会话，它们属于 TerminalService
    }

    // ------------------------------------------------------------ 绑定 / 重放

    private fun tryAttach() {
        if (attached) return
        val s = service ?: return
        if (!ui.isPageReady()) return
        attached = true

        synchronized(gate) { replaying = true }
        val snap = s.attach(listener)

        val ids = snap.map { it.id }
        val target = if (s.activeId in ids) s.activeId else ids.lastOrNull() ?: -1
        for (info in snap) {
            ui.createTab(info.id, info.name, activate = info.id == target)
            ui.setSessionStatus(info.id, info.running)
            if (info.backlog.isNotEmpty()) ui.writeBytes(info.id, info.backlog)
        }

        synchronized(gate) {
            replaying = false
            for ((id, data) in held) ui.writeBytes(id, data)
            held.clear()
        }

        if (snap.isEmpty() && hasStorageAccess()) createAndStartSession()
        else if (snap.isEmpty()) requestStorageAccess()
    }

    // ------------------------------------------------------------ 权限

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED

    private fun requestStorageAccess() {
        showHint("需要「所有文件访问」权限以读取 ${DebianTerminal.SHARED_DIR.path}\n授权后返回本应用即可。\n")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ), REQ_STORAGE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE && hasStorageAccess()) {
            val s = service
            if (attached && s != null && s.sessionIds().isEmpty()) createAndStartSession()
        }
    }

    // ------------------------------------------------------------ 提示标签

    private fun showHint(text: String) {
        if (!hintShown) {
            hintShown = true
            ui.createTab(HINT_ID, "提示", activate = true)
        }
        ui.printText(HINT_ID, text)
    }

    private fun clearHint() {
        if (!hintShown) return
        hintShown = false
        ui.removeTab(HINT_ID)
        if (ui.getCurrentId() < 0) {
            service?.sessionIds()?.lastOrNull()?.let { switchTo(it) }
        }
    }

    // ------------------------------------------------------------ 终端

    private fun switchTo(id: Int) {
        if (id != HINT_ID) service?.activeId = id
        ui.switchTab(id)
    }

    private fun createAndStartSession() {
        val s = service ?: return
        if (!ui.isPageReady()) return
        if (!hasStorageAccess()) {
            requestStorageAccess()
            return
        }
        clearHint()
        val info = s.newSession()
        ui.createTab(info.id, info.name, activate = true)
        s.boot(info.id)            // 标签建好之后再启动，状态点和输出才不会丢
    }

    private fun closeSession(id: Int) {
        if (id == HINT_ID) {
            clearHint()
            return
        }
        val s = service ?: return
        val wasCurrent = ui.getCurrentId() == id
        s.closeSession(id)
        ui.removeTab(id)
        val ids = s.sessionIds()
        if (ids.isEmpty()) {
            createAndStartSession()
        } else if (wasCurrent) {
            switchTo(ids.last())
        }
    }
}
