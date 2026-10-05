package com.mobiledeb

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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

    /** 已移除的标签 id：晚到的输出直接丢弃，不再重建缓冲区。 */
    private val closedIds: MutableSet<Int> = ConcurrentHashMap.newKeySet<Int>()

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
    @Volatile private var alt = false
    private var ctrlButton: Button? = null
    private var altButton: Button? = null

    private var currentId = -1
    private var sidebarOpen = false

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
        val COLOR_CTRL_ON     = Color.parseColor("#3A6EA5")
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

    /** 带按压涟漪的纯色背景（透明底也有反馈）。 */
    private fun ripple(base: Int = Color.TRANSPARENT): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            ColorDrawable(base),
            ColorDrawable(Color.WHITE)
        )

    // ---------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(activity)
        wv.setBackgroundColor(COLOR_BG)
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.settings.javaScriptEnabled = true
        wv.settings.allowContentAccess = false
        // 不影响 file:///android_asset；若页面白屏，把这行删掉再试
        wv.settings.allowFileAccess = false
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

        // 第一行：☰ Ctrl Alt Esc Tab ^C ^D
        add(row1, toolBtn("☰") { toggleSidebar() })
        ctrlButton = toolBtn("Ctrl") { setCtrl(!ctrl) }
        add(row1, ctrlButton!!)
        altButton = toolBtn("Alt") { setAlt(!alt) }
        add(row1, altButton!!)
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
        // 方向键交给 JS 的 arrowActive：无修饰按 application cursor 模式选 ESC[ / ESC O，
        // 带 Ctrl/Alt 修饰时用 CSI 参数形式（mod 值 = 1 + Shift + Alt*2 + Ctrl*4）
        add(row2, repeatBtn("↑") { arrow('A') })
        add(row2, repeatBtn("↓") { arrow('B') })
        add(row2, repeatBtn("←") { arrow('D') })
        add(row2, repeatBtn("→") { arrow('C') })
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
            background = ripple()
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
            background = ripple()
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
            background = ripple()
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
            background = ripple()
            setOnClickListener { action() }
        }

    /**
     * 方向键：带上 Ctrl/Alt 时一次性编码进 CSI 参数（mod 值 = 1 + Shift + Alt*2 + Ctrl*4），
     * 由 JS 决定用 CSI 参数形式还是 application cursor 前缀。
     */
    private fun arrow(c: Char) {
        if (destroyed || !webReady) return
        var mod = 1
        if (alt) mod += 2
        if (ctrl) mod += 4
        val needsMod = mod > 1
        clearMods()
        web.evaluateJavascript("arrowActive('$c', ${if (needsMod) mod else 0})", null)
    }

    // ---------------------------------------------------------- 侧边栏

    private fun toggleSidebar() {
        if (sidebarOpen) hideSidebar() else showSidebar()
    }

    private fun showSidebar() {
        // 先 cancel：会同步触发上一次 hide 的 endAction，随后再置为可见，顺序不会反
        sidebar.animate().cancel()
        sidebarOpen = true
        sidebar.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        sidebar.translationX = -dp(SIDEBAR_WIDTH_DP).toFloat()
        sidebar.animate().translationX(0f).setDuration(200).start()
    }

    private fun hideSidebar() {
        if (!sidebarOpen) return
        sidebarOpen = false
        sidebar.animate().cancel()
        sidebar.animate()
            .translationX(-dp(SIDEBAR_WIDTH_DP).toFloat())
            .setDuration(200)
            .withEndAction {
                if (!sidebarOpen) {
                    sidebar.visibility = View.GONE
                    scrim.visibility = View.GONE
                }
            }
            .start()
    }

    /** 返回键：侧栏开着就先关侧栏。返回 true 表示已处理。 */
    fun handleBack(): Boolean {
        if (!sidebarOpen) return false
        hideSidebar()
        return true
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
        closedIds.remove(id)
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
        closedIds.add(id)
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
        if (id in closedIds) return
        val buf = buffers.computeIfAbsent(id) { Buf() }
        synchronized(buf) { buf.baos.write(b, 0, b.size) }
        scheduleFlush(id, buf)
    }

    fun printText(id: Int, s: String) {
        val n = s.replace("\r\n", "\n").replace("\n", "\r\n")
        writeBytes(id, n.toByteArray(Charsets.UTF_8))
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

    // ---------------------------------------------------------- 修饰键（Ctrl / Alt）

    private fun setCtrl(on: Boolean) {
        ctrl = on
        ctrlButton?.background = ripple(if (on) COLOR_CTRL_ON else Color.TRANSPARENT)
    }

    private fun setAlt(on: Boolean) {
        alt = on
        altButton?.background = ripple(if (on) COLOR_CTRL_ON else Color.TRANSPARENT)
    }

    /** 修饰键是一次性的，输入后自动弹起。 */
    private fun clearMods() {
        if (!ctrl && !alt) return
        ctrl = false
        alt = false
        activity.runOnUiThread {
            setCtrl(false)
            setAlt(false)
        }
    }

    /**
     * 软键盘输入：Ctrl 转控制码、Alt 加 ESC 前缀，两者可叠加；消费后自动清空。
     * 多字符（输入法合成等）只处理 Alt 前缀，Ctrl 不做转义。
     */
    private fun applyMod(d: String): String {
        if (!ctrl && !alt) return d
        val hadCtrl = ctrl
        val hadAlt = alt
        clearMods()
        if (d.isEmpty()) return d

        var out = d
        if (hadCtrl && d.length == 1) {
            val c = d[0]
            val code = when (c) {
                in 'a'..'z' -> c.code - 96
                in 'A'..'Z' -> c.code - 64
                '[' -> 27; '\\' -> 28; ']' -> 29; '^' -> 30; '_' -> 31
                ' ', '@' -> 0
                else -> -1
            }
            if (code >= 0) out = code.toChar().toString()
        }
        if (hadAlt) out = "\u001B$out"
        return out
    }

    fun destroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        web.stopLoading()
        web.removeJavascriptInterface("Android")
        // WebView 必须先从父容器摘下再 destroy，否则会有 "destroy() called while still attached"
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
    }

    // ---------------------------------------------------------- JS 桥

    inner class Bridge {
        @JavascriptInterface
        fun input(id: String, d: String) {
            if (destroyed) return
            val i = id.toIntOrNull() ?: return
            onInput(i, applyMod(d))
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
                // 页面就绪前积压的输出此前没人触发 flush，这里补一次
                for ((id, buf) in buffers) scheduleFlush(id, buf)
                onPageReady()
            }
        }
    }
}