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
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
 * 工具栏两行，无横向滚动。
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

    private val handler = Handler(Looper.getMainLooper())

    private class Buf {
        val baos = ByteArrayOutputStream()
        var scheduled = false
    }

    private val buffers = ConcurrentHashMap<Int, Buf>()

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
        const val SIDEBAR_WIDTH_DP = 240
        const val REPEAT_DELAY_MS = 500L
        const val REPEAT_INTERVAL_MS = 80L

        val COLOR_BG          = Color.parseColor("#121212")
        val COLOR_TOOLBAR_BG  = Color.parseColor("#1E1E1E")
        val COLOR_SIDEBAR_BG  = Color.parseColor("#181818")
        val COLOR_ITEM        = Color.parseColor("#242424")
        val COLOR_ITEM_ACTIVE = Color.parseColor("#2E3E52")
        val COLOR_TEXT        = Color.WHITE
        val COLOR_TEXT_DIM    = Color.parseColor("#888888")
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

        // ---- 两行工具栏 ----
        val toolbar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_TOOLBAR_BG)
        }

        val row1 = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

        fun add(row: LinearLayout, v: View) {
            row.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
        }

        // 第一行：☰ Ctrl Esc Tab ^C ^D
        add(row1, toolBtn("☰") { toggleSidebar() })
        ctrlButton = toolBtn("Ctrl") { setCtrl(!ctrl) }
        add(row1, ctrlButton!!)
        add(row1, toolBtn("Esc") { onKey(currentId, "\u001B") })
        add(row1, toolBtn("Tab") { onKey(currentId, "\t") })
        add(row1, toolBtn("^C") { onKey(currentId, "\u0003") })
        add(row1, toolBtn("^D") { onKey(currentId, "\u0004") })

        // 第二行：A- A+ ↑ ↓ ← → ⌫
        add(row2, toolBtn("A-") {
            if (!destroyed) web.evaluateJavascript("fontDeltaActive(-1)", null)
        })
        add(row2, toolBtn("A+") {
            if (!destroyed) web.evaluateJavascript("fontDeltaActive(1)", null)
        })
        add(row2, repeatBtn("↑") { onKey(currentId, "\u001B[A") })
        add(row2, repeatBtn("↓") { onKey(currentId, "\u001B[B") })
        add(row2, repeatBtn("←") { onKey(currentId, "\u001B[D") })
        add(row2, repeatBtn("→") { onKey(currentId, "\u001B[C") })
        add(row2, repeatBtn("⌫") { onKey(currentId, "\u007F") })

        toolbar.addView(row1, LinearLayout.LayoutParams(-1, -2))
        toolbar.addView(row2, LinearLayout.LayoutParams(-1, -2))

        main.addView(toolbar, LinearLayout.LayoutParams(-1, -2))

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

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(10))
        }
        header.addView(
            TextView(activity).apply {
                text = "终端列表"
                setTextColor(COLOR_TEXT)
                textSize = 16f
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        header.addView(toolbarBtn("+") { onNewSession() })
        sidebar.addView(header)

        sessionList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
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

    // ---------------------------------------------------------- 按钮

    /** 工具栏按钮：等宽、12sp、无最小宽度限制。 */
    private fun toolBtn(label: String, action: () -> Unit): Button =
        Button(activity).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(2), dp(8), dp(2), dp(8))
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { action() }
        }

    /** 长按连发按钮（方向键、退格）。 */
    private fun repeatBtn(label: String, action: () -> Unit): Button {
        val b = Button(activity).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(2), dp(8), dp(2), dp(8))
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT)
            setBackgroundColor(Color.TRANSPARENT)
        }
        b.setOnTouchListener(object : View.OnTouchListener {
            private var pressed = false
            private val runnable = object : Runnable {
                override fun run() {
                    if (!pressed) return
                    action()
                    b.postDelayed(this, REPEAT_INTERVAL_MS)
                }
            }
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pressed = true
                        v.isPressed = true
                        action()
                        b.postDelayed(runnable, REPEAT_DELAY_MS)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        pressed = false
                        v.isPressed = false
                        b.removeCallbacks(runnable)
                        v.performClick()
                        return true
                    }
                }
                return false
            }
        })
        return b
    }

    /** 侧边栏里的小按钮（新建用）。 */
    private fun toolbarBtn(label: String, action: () -> Unit): Button =
        Button(activity).apply {
            text = label
            isAllCaps = false
            minWidth = dp(40)
            minimumWidth = dp(40)
            setPadding(0, 0, 0, 0)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { action() }
        }

    /** 侧边栏条目里的小图标按钮。 */
    private fun iconBtn(label: String, action: () -> Unit): Button =
        Button(activity).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(6), 0, dp(6), 0)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_DIM)
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
            setPadding(dp(12), dp(10), dp(6), dp(10))
            isClickable = true
            background = rounded(COLOR_ITEM, 8)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                marginStart = dp(12); marginEnd = dp(12)
                topMargin = dp(3); bottomMargin = dp(3)
            }
        }

        val dot = View(activity).apply { background = circle(COLOR_DOT_OK) }
        row.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply {
            marginEnd = dp(10)
        })

        val label = TextView(activity).apply {
            text = name
            setTextColor(COLOR_TEXT)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))

        row.addView(iconBtn("改名") { showRenameDialog(id) })
        row.addView(iconBtn("✕") { onCloseSession(id) })

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
                8
            )
        }
    }

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

    // ---------------------------------------------------------- Ctrl

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