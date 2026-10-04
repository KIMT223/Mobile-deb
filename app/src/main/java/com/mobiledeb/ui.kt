package com.mobiledeb

import android.annotation.SuppressLint
import android.app.Activity
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
 * 长按直接走系统原生选择，无中间菜单。
 */
class TerminalUi(private val activity: Activity) {

    var onInput: (Int, String) -> Unit = { _, _ -> }
    var onKey: (Int, String) -> Unit = { _, _ -> }
    var onResize: (Int, Int, Int) -> Unit = { _, _, _ -> }
    var onReady: (Int, Int, Int) -> Unit = { _, _, _ -> }
    var onPageReady: () -> Unit = {}

    var onNewSession: () -> Unit = {}
    var onSwitchSession: (Int) -> Unit = {}
    var onCloseSession: (Int) -> Unit = {}

    val root: FrameLayout

    private val web: WebView
    private val sidebar: LinearLayout
    private val sessionList: LinearLayout
    private val scrim: View
    private val mainBar: LinearLayout

    private val handler = Handler(Looper.getMainLooper())

    private class Buf {
        val baos = ByteArrayOutputStream()
        var scheduled = false
    }

    private val buffers = ConcurrentHashMap<Int, Buf>()

    @Volatile private var webReady = false
    @Volatile private var destroyed = false
    @Volatile private var ctrl = false
    private var ctrlButton: Button? = null

    private var currentId = -1
    private val sessionItems = ConcurrentHashMap<Int, View>()

    private companion object {
        const val CHUNK = 32 * 1024
        const val SIDEBAR_WIDTH_DP = 220
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics
    ).toInt()

    // ---------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(activity)
        wv.setBackgroundColor(0xFF121212.toInt())
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.settings.javaScriptEnabled = true
        wv.settings.allowContentAccess = false
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        // 让 WebView 自己处理长按选择，不给我们拦截
        wv.isLongClickable = true
        wv.isHapticFeedbackEnabled = true
        wv.addJavascriptInterface(Bridge(), "Android")
        wv.loadUrl("file:///android_asset/terminal.html")
        return wv
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

        // ---- 单行工具栏 ----
        mainBar = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        mainBar.addView(button("☰") { toggleSidebar() })
        ctrlButton = button("Ctrl") { setCtrl(!ctrl) }.also { mainBar.addView(it) }

        fun raw(label: String, seq: String) {
            mainBar.addView(button(label) { onKey(currentId, seq) })
        }

        fun jsCall(label: String, code: String) {
            mainBar.addView(button(label) { if (!destroyed) web.evaluateJavascript(code, null) })
        }

        raw("Esc", "\u001B")
        raw("Tab", "\t")
        jsCall("↑", "arrowActive('A')")
        jsCall("↓", "arrowActive('B')")
        jsCall("←", "arrowActive('D')")
        jsCall("→", "arrowActive('C')")
        raw("PgUp", "\u001B[5~")
        raw("PgDn", "\u001B[6~")
        raw("^C", "\u0003")
        raw("^D", "\u0004")
        jsCall("A-", "fontDeltaActive(-1)")
        jsCall("A+", "fontDeltaActive(1)")
        mainBar.addView(button("复制") { web.evaluateJavascript("copySelectionOrAll()", null) })
        mainBar.addView(button("粘贴") { paste() })
        mainBar.addView(button("新建") { onNewSession() })

        main.addView(
            HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(Color.parseColor("#1E1E1E"))
                addView(mainBar)
            },
            LinearLayout.LayoutParams(-1, -2)
        )

        root.addView(main, FrameLayout.LayoutParams(-1, -1))

        scrim = View(activity).apply {
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
            setOnClickListener { hideSidebar() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))

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

    private fun button(label: String, action: () -> Unit): Button = Button(activity).apply {
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
        for ((id, v) in sessionItems) {
            v.setBackgroundColor(
                if (id == currentId) Color.parseColor("#2A4A6E") else Color.TRANSPARENT
            )
        }
    }

    // ---------------------------------------------------------- 会话 API

    fun createTab(id: Int, name: String, activate: Boolean) {
        sessionItems[id] = makeSessionItem(id, name).also { sessionList.addView(it) }
        if (activate) currentId = id
        refreshActiveHighlight()
        if (!webReady) return
        web.evaluateJavascript("createTerm($id)", null)
        if (activate) web.evaluateJavascript("switchTerm($id)", null)
    }

    fun switchTab(id: Int) {
        currentId = id
        refreshActiveHighlight()
        if (webReady) web.evaluateJavascript("switchTerm($id)", null)
        hideSidebar()
    }

    fun removeTab(id: Int) {
        val v = sessionItems.remove(id)
        if (v != null) sessionList.removeView(v)
        buffers.remove(id)
        if (webReady) web.evaluateJavascript("closeTerm($id)", null)
        if (currentId == id) currentId = -1
        refreshActiveHighlight()
    }

    fun getCurrentId(): Int = currentId
    fun isPageReady(): Boolean = webReady

    // ---------------------------------------------------------- 输出

    fun writeBytes(id: Int, b: ByteArray) {
        var buf = buffers[id]
        if (buf == null) {
            buf = Buf()
            buffers[id] = buf
        }
        synchronized(buf) { buf.baos.write(b, 0, b.size) }
        scheduleFlush(id, buf)
    }

    fun printText(id: Int, s: String) {
        val n = s.replace("\r\n", "\n").replace("\n", "\r\n")
        writeBytes(id, n.toByteArray(Charsets.UTF_8))
    }

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
            if (!webReady) null else buf.baos.toByteArray().also { buf.baos.reset() }
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

    // ---------------------------------------------------------- 剪贴板

    private fun paste() {
        if (destroyed) return
        val cm = activity.getSystemService(ClipboardManager::class.java)
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(activity)?.toString()
        if (!text.isNullOrEmpty()) {
            web.evaluateJavascript("pasteActive(${JSONObject.quote(text)})", null)
        }
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
                webReady = true
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