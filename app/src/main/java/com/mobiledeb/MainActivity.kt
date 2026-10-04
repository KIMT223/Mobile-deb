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

    private class Session(
        val id: Int,
        var name: String,
        val term: DebianTerminal,
    )

    private lateinit var ui: TerminalUi
    private val sessions = mutableListOf<Session>()
    private var nextId = 1

    @Volatile private var rootfsPrepared = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        ui = TerminalUi(this)
        setContentView(ui.root)

        ui.onInput = { id, data -> findSession(id)?.term?.write(data) }
        ui.onKey = { id, data -> findSession(id)?.term?.write(data) }
        ui.onResize = { id, c, r -> findSession(id)?.term?.resize(c, r) }
        ui.onReady = { id, c, r -> findSession(id)?.term?.resize(c, r) }
        ui.onPageReady = { if (sessions.isEmpty()) ensureInitialSession() }
        ui.onNewSession = { createAndStartSession() }
        ui.onSwitchSession = { id -> ui.switchTab(id) }
        ui.onCloseSession = { id -> closeSession(id) }
        ui.onRenameSession = { id, newName ->
            findSession(id)?.name = newName
        }
    }

    override fun onResume() {
        super.onResume()
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

    private fun findSession(id: Int): Session? {
        for (s in sessions) if (s.id == id) return s
        return null
    }

    private fun ensureInitialSession() {
        if (sessions.isNotEmpty()) return
        if (!ui.isPageReady()) return
        if (!hasStorageAccess()) {
            requestStorageAccess()
            return
        }
        createAndStartSession()
    }

    private fun createAndStartSession() {
        if (!hasStorageAccess()) {
            requestStorageAccess()
            return
        }
        val id = nextId++
        val sessionName = "终端 $id"

        val term = DebianTerminal(
            ctx = applicationContext,
            onOutput = { ui.writeBytes(id, it) },
            onMessage = { ui.printText(id, it) },
            onExit = { code ->
                ui.setSessionStatus(id, false)
                ui.printText(id, "\n[进程已退出，code=$code]\n")
            },
        )
        val s = Session(id, sessionName, term)
        sessions.add(s)
        ui.createTab(id, sessionName, activate = true)

        val bootThread = Thread {
            DebianTerminal.SHARED_DIR.mkdirs()
            if (!rootfsPrepared) {
                if (!term.isRootfsReady()) {
                    val ok = term.prepareRootfs { msg -> ui.printText(id, msg) }
                    if (!ok) {
                        ui.setSessionStatus(id, false)
                        return@Thread
                    }
                }
                rootfsPrepared = true
            }
            term.start()
            ui.setSessionStatus(id, true)
        }
        bootThread.also { it.name = "debian-boot-$id" }.start()
    }

    private fun closeSession(id: Int) {
        val s = findSession(id) ?: return
        val wasCurrent = ui.getCurrentId() == id
        s.term.stop()
        sessions.remove(s)
        ui.removeTab(id)
        if (sessions.isEmpty()) {
            createAndStartSession()
        } else if (wasCurrent) {
            ui.switchTab(sessions[sessions.size - 1].id)
        }
    }

    override fun onDestroy() {
        for (s in sessions) s.term.stop()
        sessions.clear()
        ui.destroy()
        super.onDestroy()
    }
}