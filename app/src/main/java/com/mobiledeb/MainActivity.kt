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

    private lateinit var ui: TerminalUi
    private lateinit var term: DebianTerminal

    @Volatile private var started = false
    private var everStarted = false
    private var webReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        ui = TerminalUi(this)
        setContentView(ui.root)

        term = DebianTerminal(
            ctx = applicationContext,
            onOutput = { ui.writeBytes(it) },
            onMessage = { ui.printText(it) },
            onExit = { code ->
                ui.printText("\n[进程已退出，code=$code]\n点底部「重启」重新进入。\n")
                started = false
            },
        )

        ui.onInput = { term.write(ui.applyCtrl(it)) }
        ui.onKey = { term.write(it) }
        ui.onResize = { c, r -> term.resize(c, r) }
        ui.onReady = { c, r ->
            term.resize(c, r)
            webReady = true
            if (!everStarted) tryStart()
        }
        ui.onRestart = { if (!started) tryStart() }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置授权回来后自动开始
        if (webReady && !everStarted) tryStart()
    }

    // ------------------------------------------------------------ 权限

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED

    private fun requestStorageAccess() {
        ui.printText("需要「所有文件访问」权限以读取 ${DebianTerminal.SHARED_DIR.path}\n授权后返回本应用即可。\n")
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
        if (webReady && !started) tryStart()
    }

    // ------------------------------------------------------------ 终端

    private fun tryStart() {
        if (started || !webReady) return
        if (hasStorageAccess()) startTerminal() else requestStorageAccess()
    }

    private fun startTerminal() {
        started = true
        everStarted = true
        Thread {
            DebianTerminal.SHARED_DIR.mkdirs()
            if (!term.isRootfsReady()) {
                val ok = term.prepareRootfs { ui.printText(it) }
                if (!ok) {
                    started = false
                    return@Thread
                }
            }
            term.start()
        }.apply { name = "debian-boot" }.start()
    }

    override fun onDestroy() {
        term.stop()
        ui.destroy()
        super.onDestroy()
    }
}
