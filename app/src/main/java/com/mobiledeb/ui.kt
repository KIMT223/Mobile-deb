package com.mobiledeb

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * WebView + xterm.js 多终端界面。
 *
 * - 一个 WebView 里多个 xterm.js 实例，通过 JS 桥按 id 路由输入输出；
 * - 侧边栏列出所有终端，点击切换，点「×」关闭，点「+」新建；
 * - 长按 WebView 弹出菜单：全选并复制 / 复制选中 / 粘贴 / 清屏 / 字号。
 */
class TerminalUi(private val activity: Activity) {

    // ---- 桥接回调（都带终端 id） ----
    var onInput: (Int, String) -> Unit = { _, _ -> }
    var onKey: (Int, String) -> Unit = { _, _ -> }
    var onResize: (Int, Int, Int) -> Unit = { _, _, _ -> }
    var onReady: (Int, Int, Int) -> Unit = { _, _, _ -> }
    var onPageReady: () -> Unit = {}

    // ---- 侧边栏回调 ----
    var onNewSession: () -> Unit = {}
    var onSwitchSession: (Int) -> Unit = {}
    var onCloseSession: (Int) -> Unit = {}

    val root: FrameLayout

    private val web: WebView
    private val sidebar: LinearLayout
    private val sessionList: LinearLayout
    private val scrim: View

    private val handler = Handler(Looper.getMainLooper())

    private class Buf {
        val baos = ByteArrayOutputStream()
        var scheduled = false
    }
    private val buffers = ConcurrentHashMap<Int, Buf>()

    @Volatile private var pageReady = false
    @Volatile private var destroyed = false
    @Volatile private var ctrl = false
    private var ctrlButton: Button? = null

    private var currentId = -1
    private val sessionItems = ConcurrentHashMap<Int, View>()

    private companion object {
        const val CHUNK = 32 * 1024
        const val SIDEBAR_WIDTH_DP = 220
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics
    ).toInt()

    // ---------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() = WebView(activity).apply {
        setBackgroundColor(0xFF121212.toInt())
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.javaScriptEnabled = true
        settings.allowContentAccess = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        addJavascriptInterface(Bridge(), "Android")
        loadUrl("file:///android_asset/terminal.html")

        // 长按 -> 弹出终端菜单
        setOnLongClickListener {
            showTerminalMenu()
            true
        }
    }

    // ---------------------------------------------------------- 初始化布局

    init {
        root = FrameLayout(activity)

        val main = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            fitsSystemWindows = true
        }
        web = createWebView()
        main.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))

        val bar = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("☰") { toggleSidebar() })
        ctrlButton = button("Ctrl") { setCtrl(!ctrl) }.also { bar.addView(it) }

        fun raw(label: String, seq: String) =
            bar.addView(button(label) { onKey(currentId, seq) })

        fun jsCall(label: String, code: String) =
            bar.addView(button(label) { if (!destroyed) web.evaluateJavascript(code, null) })

        raw("Esc", "\u001B")
        raw("Tab", "\t")
        jsCall("↑", "arrowActive('A')")
        jsCall("↓", "arrowActive('B')")
        jsCall("←", "arrowActive('D')")
        jsCall("→", "arrowActive('C')")
        jsCall("Home", "arrowActive('H')")
        jsCall("End", "arrowActive('F')")
        raw("PgUp", "\u001B[5~")
        raw("PgDn", "\u001B[6~")
        raw("^C", "\u0003")
        raw("^D", "\u0004")
        raw("/", "/")
        raw("-", "-")
        raw("|", "|")
        raw("~", "~")
        jsCall("A-", "fontDeltaActive(-1)")
        jsCall("A+", "fontDeltaActive(1)")
        bar.addView(button("复制") { web.evaluateJavascript("copyAllActive()", null) })
        bar.addView(button("粘贴") { paste() })
        bar.addView(button("新建") { onNewSession() })

        main.addView(
            HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(Color.parseColor("#1E1E1E"))
                addView(bar)
            },
            LinearLayout.LayoutParams(-1, -2)
        )
        root.addView(main, FrameLayout.LayoutParams(-1, -1))

        // 侧边栏遮罩
        scrim = View(activity).apply {
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
            setOnClickListener { hideSidebar() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        // 侧边栏
        sidebar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1B1B1B"))
            visibility = View.GONE
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(activity).apply {
                text = "终端列表"
                setTextColor(Color.WHITE)
                textSize = 16f
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        header.addView(button("+") { onNewSession() })
        sidebar.addView(header)

        sessionList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        sidebar.addView(
            ScrollView(activity).apply { addView(sessionList) },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        val sidebarLp = FrameLayout.LayoutParams(dp(SIDEBAR_WIDTH_DP), -1)
        sidebarLp.gravity = Gravity.START
        root.addView(sidebar, sidebarLp)
    }

    private fun button(label: String, action: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        minWidth = dp(46)
        minimumWidth = dp(46)
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    // ---------------------------------------------------------- 侧边栏

    private fun toggleSidebar() {
        if (sidebar.visibility == View.VISIBLE) hideSidebar() else showSidebar()
    }

    private fun showSidebar() {
        sidebar.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        sidebar.translationX = -dp(SIDEBAR_WIDTH_DP).toFloat()
        sidebar.animate().translationX(0f).setDuration(180).start()
    }

    private fun hideSidebar() {
        if (sidebar.visibility != View.VISIBLE) return
        sidebar.animate()
            .translationX(-dp(SIDEBAR_WIDTH_DP).toFloat())
            .setDuration(180)
            .withEndAction {
                sidebar.visibility = View.GONE
                scrim.visibility = View.GONE
            }
            .start()
    }

    private fun makeSessionItem(id: Int, name: String): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(activity).apply {
            text = name
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button("×") { onCloseSession(id) })
        row.setOnClickListener { onSwitchSession(id) }
        return row
    }

    private fun refreshActiveHighlight() {
        sessionItems.forEach { (id, v) ->
            v.setBackgroundColor(
                if (id == currentId) Color.parseColor("#2A4A6E") else Color.TRANSPARENT
            )
        }
    }

    // ---------------------------------------------------------- 会话 API

    /** 新建一个终端标签；activate=true 时立即切换过去。 */
    fun createTab(id: Int, name: String, activate: Boolean) {
        sessionItems[id] = makeSessionItem(id, name).also { sessionList.addView(it) }
        if (activate) currentId = id
        refreshActiveHighlight()
        if (!pageReady) return
        web.evaluateJavascript("createTerm($id)", null)
        if (activate) web.evaluateJavascript("switchTerm($id)", null)
    }

    fun switchTab(id: Int) {
        currentId = id
        refreshActiveHighlight()
        if (pageReady) web.evaluateJavascript("switchTerm($id)", null)
        hideSidebar()
    }

    fun removeTab(id: Int) {
        sessionItems.remove(id)?.let { sessionList.removeView(it) }
        buffers.remove(id)
        if (pageReady) web.evaluateJavascript("closeTerm($id)", null)
        if (currentId == id) currentId = -1
        refreshActiveHighlight()
    }

    fun getCurrentId() = currentId
    fun isPageReady() = pageReady

    // ---------------------------------------------------------- 输出

    fun writeBytes(id: Int, b: ByteArray) {
        val buf = buffers.getOrPut(id) { Buf() }
        synchronized(buf) { buf.baos.write(b, 0, b.size) }
        scheduleFlush(id, buf)
    }

    /** 提示文本：把裸 \n 规范成 \r\n，保留原有的 \r，避免破坏进度条。 */
    fun printText(id: Int, s: String) {
        val n = s.replace("\r\n", "\n").replace("\n", "\r\n")
        writeBytes(id, n.toByteArray(Charsets.UTF_8))
    }

    /** 在还没有任何终端时（比如首次请求权限）把提示写到当前 UI 上。 */
    fun printGlobalText(s: String) {
        val n = s.replace("\r\n", "\n").replace("\n", "\r\n")
        if (currentId >= 0) writeBytes(currentId, n.toByteArray(Charsets.UTF_8))
    }

    private fun scheduleFlush(id: Int, buf: Buf) {
        synchronized(buf) {
            if (buf.scheduled) return
            buf.scheduled = true
        }
        if (destroyed) return
        handler.postDelayed({ flush(id) }, 16)
    }

    private fun flush(id: Int) {
        if (destroyed) return
        val buf = buffers[id] ?: return
        val data: ByteArray? = synchronized(buf) {
            buf.scheduled = false
            if (!pageReady) null else buf.baos.toByteArray().also { buf.baos.reset() }
        }
        if (data == null || data.isEmpty()) return
        var off = 0
        while (off < data.size) {
            val n = minOf(CHUNK, data.size - off)
            val b64 = Base64.encodeToString(data, off, n, Base64.NO_WRAP)
            web.evaluateJavascript("w($id,'$b64')", null)
            off += n
        }
    }

    // ---------------------------------------------------------- Ctrl 粘滞键

    private fun setCtrl(on: Boolean) {
        ctrl = on
        ctrlButton?.setBackgroundColor(if (on) Color.parseColor("#3A6EA5") else Color.TRANSPARENT)
    }

    /** Ctrl 打开时把下一个字符转成控制字符，随后自动关闭。 */
    private fun applyCtrl(d: String): String {
        if (!ctrl || d.length != 1) return d
        val c = d[0]
        val code = when (c) {
            in 'a'..'z' -> c.code - 96
            in 'A'..'Z' -> c.code - 64
            '[' -> 27
            '\\' -> 28
            ']' -> 29
            '^' -> 30
            '_' -> 31
            ' ', '@' -> 0
            else -> -1
        }
        ctrl = false
        activity.runOnUiThread { setCtrl(false) }
        return if (code >= 0) code.toChar().toString() else d
    }

    // ---------------------------------------------------------- 剪贴板 / 长按菜单

    private fun paste() {
        if (destroyed) return
        val cm = activity.getSystemService(ClipboardManager::class.java)
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(activity)?.toString()
        if (!text.isNullOrEmpty()) {
            web.evaluateJavascript("pasteActive(${JSONObject.quote(text)})", null)
        }
    }

    private fun showTerminalMenu() {
        if (destroyed || currentId < 0) return
        val items = arrayOf("全选并复制", "复制选中", "粘贴", "清屏", "字号 -", "字号 +")
        AlertDialog.Builder(activity)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> web.evaluateJavascript("copyAllActive()", null)
                    1 -> web.evaluateJavascript("copySelectionActive()", null)
                    2 -> paste()
                    3 -> web.evaluateJavascript("clearActive()", null)
                    4 -> web.evaluateJavascript("fontDeltaActive(-1)", null)
                    5 -> web.evaluateJavascript("fontDeltaActive(1)", null)
                }
            }
            .show()
    }

    fun destroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        web.destroy()
    }

    // ---------------------------------------------------------- JS 桥

    inner class Bridge {
        @JavascriptInterface
        fun input(id: String, d: String) {
            if (destroyed) return
            val i = id.toIntOrNull() ?: return
            onInput(i, applyCtrl(d))
        }

        @JavascriptInterface
        fun key(id: String, d: String) {
            if (destroyed) return
            val i = id.toIntOrNull() ?: return
            onKey(i, d)
        }

        @JavascriptInterface
        fun resize(id: String, cols: Int, rows: Int) {
            if (destroyed) return
            val i = id.toIntOrNull() ?: return
            onResize(i, cols, rows)
        }

        @JavascriptInterface
        fun ready(id: String, cols: Int, rows: Int) {
            val i = id.toIntOrNull() ?: return
            activity.runOnUiThread {
                if (destroyed) return@runOnUiThread
                onReady(i, cols, rows)
            }
        }

        @JavascriptInterface
        fun pageReady() {
            activity.runOnUiThread {
                if (destroyed) return@runOnUiThread
                pageReady = true
                onPageReady()
            }
        }

        @JavascriptInterface
        fun onSelection(text: String) {
            if (text.isEmpty()) return
            activity.runOnUiThread {
                val cm = activity.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("terminal", text))
                Toast.makeText(activity, "已复制 ${text.length} 字符", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun onToast(text: String) {
            activity.runOnUiThread {
                Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
            }
        }
    }
}