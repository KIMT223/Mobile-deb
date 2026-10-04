package com.mobiledeb

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager

class MainActivity : Activity() {

    private data class Session(
        val id: Int,
        val name: String,
        val term: DebianTerminal,
    )

    private lateinit var ui: TerminalUi
    private val sessions = mutableListOf<Session>()
    private var nextId = 1

    /** 全局只解压一次 rootfs，多终端共享 filesDir/rootfs。 */
    @Volatile private var rootfsPrepared = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        ui = TerminalUi(this)
        setContentView(ui.root)

        ui.onInput = { id, data -> findSession(id)?.term?.write(data) }
        ui.onKey = { id, data -> findSession(id)?.term?.write(data) }
        ui.onResize = { id, c, r -> findSession(id)?.term?.resize(c, r) }
        ui.onReady = { id, c, r ->
            // 页面里 xterm 初始化完成，把当前尺寸同步给 proot
            findSession(id)?.term?.resize(c, r)
        }
        ui.onPageReady = {
            // WebView 加载完毕，启动第一个终端
            if (sessions.isEmpty()) ensureInitialSession()
        }
        ui.onNewSession = { createAndStartSession() }
        ui.onSwitchSession = { id -> ui.switchTab(id) }
        ui.onCloseSession = { id -> closeSession(id) }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置授权回来后自动开始；如果已授权则立刻进入
        if (!hasStorageAccess()) return
        if (sessions.isEmpty()) ensureInitialSession()
    }

    // ------------------------------------------------------------ 权限

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED

    private fun requestStorageAccess() {
        ui.printGlobalText("需要「所有文件访问」权限以读取 ${DebianTerminal.SHARED_DIR.path}\n授权后返回本应用即可。\n")
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
                ), 1
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasStorageAccess() && sessions.isEmpty()) ensureInitialSession()
    }

    // ------------------------------------------------------------ 终端

    private fun findSession(id: Int): Session? = sessions.firstOrNull { it.id == id }

    private fun ensureInitialSession() {
        if (sessions.isNotEmpty()) return
        if (!ui.isPageReady()) return
        if (!hasStorageAccess()) {
            requestStorageAccess()
            return
        }
        createAndStartSession()
    }

    /** 新建一个终端：分配 id、注册 UI 标签、启动 proot。 */
    private fun createAndStartSession() {
        if (!hasStorageAccess()) {
            requestStorageAccess()
            return
        }
        val id = nextId++
        val name = "终端 $id"
        val term = DebianTerminal(
            ctx = applicationContext,
            onOutput = { ui.writeBytes(id, it) },
            onMessage = { ui.printText(id, it) },
            onExit = { code ->
                ui.printText(id, "\n[进程已退出，code=$code]\n点侧边栏「+」新建，或「×」关闭。\n")
            },
        )
        val s = Session(id, name, term)
        sessions.add(s)
        ui.createTab(id, name, activate = true)

        Thread {
            DebianTerminal.SHARED_DIR.mkdirs()
            if (!rootfsPrepared) {
                if (!term.isRootfsReady()) {
                    val ok = term.prepareRootfs { ui.printText(id, it) }
                    if (!ok) return@Thread
                }
                rootfsPrepared = true
            }
            term.start()
        }.apply { name = "debian-boot-$id" }.start()
    }

    private fun closeSession(id: Int) {
        val s = findSession(id) ?: return
        s.term.stop()
        sessions.remove(s)
        ui.removeTab(id)
        if (sessions.isEmpty()) {
            // 全部关掉了就自动新建一个，避免空界面
            createAndStartSession()
        } else if (ui.getCurrentId() == id) {
            ui.switchTab(sessions.last().id)
        }
    }

    override fun onDestroy() {
        sessions.forEach { it.term.stop() }
        sessions.clear()
        ui.destroy()
        super.onDestroy()
    }
}