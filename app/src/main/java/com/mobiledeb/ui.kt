package com.mobiledeb

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
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
    var onRenameSession: (Int, String) -> Unit = { _, _ -> }

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

    // 会话的显示名 + 状态
    private class ItemState(
        val row: LinearLayout,
        val dot: View,
        val label: TextView,
    )
    private val itemStates = ConcurrentHashMap<Int, ItemState>()
    private val sessionNames = ConcurrentHashMap<Int, String>()

    @Volatile private var webReady = false
    @Volatile private var destroyed = false
    @Volatile private var ctrl = false
    private var ctrlButton: Button? = null

    private var currentId = -1

    private companion object {
        const val CHUNK = 32 * 1024
        const val SIDEBAR_WIDTH_DP = 260

        val COLOR_BG          = Color.parseColor("#121212")
        val COLOR_TOOLBAR     = Color.parseColor("#1A1A1A")
        val COLOR_BTN         = Color.parseColor("#262626")
        val COLOR_BTN_PRESS   = Color.parseColor("#333333")
        val COLOR_TEXT        = Color.parseColor("#E0E0E0")
        val COLOR_TEXT_DIM    = Color.parseColor("#8A8A8A")
        val COLOR_SIDEBAR_BG  = Color.parseColor("#161616")
        val COLOR_ITEM        = Color.parseColor("#212121")
        val COLOR_ITEM_ACTIVE = Color.parseColor("#2A4A6E")
        val COLOR_ACCENT      = Color.parseColor("#3A8DDE")
        val COLOR_DOT_OK      = Color.parseColor("#4CAF50")
        val COLOR_DOT_DEAD    = Color.parseColor("#F44336")
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics
    ).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    // ---------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(activity)
        wv.setBackgroundColor(COLOR_BG)
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.settings.javaScriptEnabled = true
        wv.settings.allowContentAccess = false
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        wv.isLongClickable = true
        wv.isHapticFeedbackEnabled = true
        wv.addJavascriptInterface(Bridge(), "Android")
        wv.loadUrl("file:///android_asset/terminal.html")
        return wv
    }

    // ---------------------------------------------------------- 初始化

    init {
        root = FrameLayout(activity)

        val main = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            fitsSystemWindows = true
        }
        web = createWebView()
        main.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- 单行工具栏 ----
        mainBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setBackgroundColor(COLOR_TOOLBAR)
        }

        mainBar.addView(toolBtn("☰") { toggleSidebar() })
        ctrlButton = toolBtn("Ctrl") { setCtrl(!ctrl) }.also { mainBar.addView(it) }

        fun raw(label: String, seq: String) {
            mainBar.addView(toolBtn(label) { onKey(currentId, seq) })
        }
        fun jsCall(label: String, code: String) {
            mainBar.addView(toolBtn(label) { if (!destroyed) web.evaluateJavascript(code, null) })
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
        mainBar.addView(toolBtn("复制") { web.evaluateJavascript("copySelectionOrAll()", null) })
        mainBar.addView(toolBtn("粘贴") { paste() })
        mainBar.addView(toolBtn("新建") { onNewSession() })

        main.addView(
            HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(mainBar)
            },
            LinearLayout.LayoutParams(-1, -2)
        )

        root.addView(main, FrameLayout.LayoutParams(-1, -1))

        // ---- 遮罩 ----
        scrim = View(activity).apply {
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
            setOnClickListener { hideSidebar() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        // ---- 侧边栏 ----
        sidebar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_SIDEBAR_BG)
            visibility = View.GONE
        }

        // 头部：标题 + 新建
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(12), dp(14))
        }
        header.addView(
            TextView(activity).apply {
                text = "终端"
                setTextColor(Color.WHITE)
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        header.addView(
            Button(activity).apply {
                text = "＋"
                isAllCaps = false
                textSize = 20f
                minWidth = dp(40); minimumWidth = dp(40)
                setPadding(0, 0, 0, 0)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(COLOR_ACCENT, 20)
                setOnClickListener { onNewSession() }
            },
            LinearLayout.LayoutParams(dp(40), dp(40))
        )
        sidebar.addView(header)

        // 列表
        sessionList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        sidebar.addView(
            ScrollView(activity).apply {
                addView(sessionList)
                isVerticalScrollBarEnabled = false
            },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        val sidebarLp = FrameLayout.LayoutParams(dp(SIDEBAR_WIDTH_DP), -1)
        sidebarLp.gravity = Gravity.START
        root.addView(sidebar, sidebarLp)
    }

    // ---------------------------------------------------------- 按钮样式

    /** 工具栏按钮：圆角灰底，按压变色。 */
    private fun toolBtn(label: String, action: () -> Unit): Button {
        val b = Button(activity).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            minimumWidth = dp(44)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT)
            background = rounded(COLOR_BTN, 8)
            setOnClickListener { action() }
        }
        // 按压变色
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.background = rounded(COLOR_BTN_PRESS, 8)
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    v.background = rounded(COLOR_BTN, 8)
            }
            false
        }
        return b
    }

    /** 侧边栏里的小图标按钮（透明底）。 */
    private fun iconBtn(label: String, color: Int, action: () -> Unit): Button =
        Button(activity).apply {
            text = label
            isAllCaps = false
            minWidth = dp(32); minimumWidth = dp(32)
            setPadding(0, 0, 0, 0)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(color)
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
        sidebar.animate().translationX(0f).setDuration(200).start()
    }

    private fun hideSidebar() {
        if (sidebar.visibility != View.VISIBLE) return
        sidebar.animate()
            .translationX(-dp(SIDEBAR_WIDTH_DP).toFloat())
            .setDuration(200)
            .withEndAction {
                sidebar.visibility = View.GONE
                scrim.visibility = View.GONE
            }
            .start()
    }

    private fun makeSessionItem(id: Int, name: String): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(6), dp(12))
            isClickable = true
            background = rounded(COLOR_ITEM, 12)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                marginStart = dp(10); marginEnd = dp(10)
                topMargin = dp(3); bottomMargin = dp(3)
            }
        }

        // 状态点
        val dot = View(activity).apply {
            background = circle(COLOR_DOT_OK)
        }
        row.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply {
            marginEnd = dp(12)
        })

        // 名称
        val label = TextView(activity).apply {
            text = name
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))

        // 编辑
        row.addView(iconBtn("✎", COLOR_TEXT_DIM) { showRenameDialog(id) })
        // 关闭
        row.addView(iconBtn("✕", COLOR_TEXT_DIM) { onCloseSession(id) })

        row.setOnClickListener { onSwitchSession(id) }
        row.setOnLongClickListener { showRenameDialog(id); true }

        itemStates[id] = ItemState(row, dot, label)
        sessionNames[id] = name
        return row
    }

    private fun refreshActiveHighlight() {
        for ((id, st) in itemStates) {
            st.row.background = rounded(
                if (id == currentId) COLOR_ITEM_ACTIVE else COLOR_ITEM,
                12
            )
        }
    }

    /** 更新某个终端的运行状态点。running=false 时点变红。 */
    fun setSessionStatus(id: Int, running: Boolean) {
        activity.runOnUiThread {
            itemStates[id]?.dot?.background = circle(
                if (running) COLOR_DOT_OK else COLOR_DOT_DEAD
            )
        }
    }

    private fun showRenameDialog(id: Int) {
        val current = sessionNames[id] ?: return
        val input = EditText(activity).apply {
            setText(current)
            setSelection(current.length)
            setSingleLine()
            setHint("终端名称")
            setTextColor(Color.WHITE)
            setHintTextColor(COLOR_TEXT_DIM)
        }
        val pad = dp(20)
        val container = FrameLayout(activity).apply {
            setPadding(pad, dp(4), pad, 0)
            addView(input, FrameLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(activity)
            .setTitle("重命名终端")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val newName = input.text.toString().trim().ifEmpty { current }
                sessionNames[id] = newName
                itemStates[id]?.label?.text = newName
                onRenameSession(id, newName)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------- 会话 API

    fun createTab(id: Int, name: String, activate: Boolean) {
        sessionList.addView(makeSessionItem(id, name))
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
        val st = itemStates.remove(id)
        if (st != null) sessionList.removeView(st.row)
        sessionNames.remove(id)
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
        ctrlButton?.background = rounded(
            if (on) COLOR_ACCENT else COLOR_BTN, 8
        )
        ctrlButton?.setTextColor(if (on) Color.WHITE else COLOR_TEXT)
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