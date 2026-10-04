package com.mobiledeb

import android.annotation.SuppressLint
import android.app.Activity
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * WebView + xterm.js 终端界面，底部是快捷键栏。
 * 输出：Kotlin -> base64 -> JS w() -> xterm；输入：xterm onData -> Android.input()。
 */
class TerminalUi(private val activity: Activity) {

    var onInput: (String) -> Unit = {}          // 键盘输入（未处理 Ctrl）
    var onKey: (String) -> Unit = {}            // 快捷键栏的原始序列
    var onResize: (Int, Int) -> Unit = { _, _ -> }
    var onReady: (Int, Int) -> Unit = { _, _ -> }
    var onRestart: () -> Unit = {}

    val root: LinearLayout
    private val web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private val pending = ByteArrayOutputStream()
    private var flushScheduled = false
    @Volatile private var pageReady = false
    @Volatile private var destroyed = false
    @Volatile private var ctrl = false
    private var ctrlButton: Button? = null

    private companion object {
        const val CHUNK = 32 * 1024
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics
    ).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() = WebView(activity).apply {
        setBackgroundColor(0xFF121212.toInt())
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.javaScriptEnabled = true
        settings.allowContentAccess = false
        webViewClient = object : WebViewClient() {
            // 不允许跳转到任何外部页面
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        addJavascriptInterface(Bridge(), "Android")
        loadUrl("file:///android_asset/terminal.html")
    }

    init {
        root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            fitsSystemWindows = true
        }
        web = createWebView()
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))

        val bar = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        ctrlButton = button("Ctrl") { setCtrl(!ctrl) }.also { bar.addView(it) }
        fun raw(label: String, seq: String) = bar.addView(button(label) { onKey(seq) })
        fun js(label: String, code: String) = bar.addView(button(label) { if (!destroyed) web.evaluateJavascript(code, null) })

        raw("Esc", "\u001B")
        raw("Tab", "\t")
        js("↑", "arrow('A')")
        js("↓", "arrow('B')")
        js("←", "arrow('D')")
        js("→", "arrow('C')")
        js("Home", "arrow('H')")
        js("End", "arrow('F')")
        raw("PgUp", "\u001B[5~")
        raw("PgDn", "\u001B[6~")
        raw("^C", "\u0003")
        raw("^D", "\u0004")
        raw("/", "/")
        raw("-", "-")
        raw("|", "|")
        raw("~", "~")
        js("A-", "fontDelta(-1)")
        js("A+", "fontDelta(1)")
        bar.addView(button("粘贴") { paste() })
        bar.addView(button("重启") { onRestart() })

        root.addView(
            HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(Color.parseColor("#1E1E1E"))
                addView(bar)
            },
            LinearLayout.LayoutParams(-1, -2)
        )
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

    // ------------------------------------------------------------ Ctrl 粘滞键

    private fun setCtrl(on: Boolean) {
        ctrl = on
        ctrlButton?.setBackgroundColor(if (on) Color.parseColor("#3A6EA5") else Color.TRANSPARENT)
    }

    /** Ctrl 打开时，把下一个字符转成控制字符（Ctrl+C 等），随后自动关闭。 */
    fun applyCtrl(d: String): String {
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
        ctrl = false // 同步重置，避免连按时第二个字符仍被当作 Ctrl 组合
        activity.runOnUiThread { setCtrl(false) }
        return if (code >= 0) code.toChar().toString() else d
    }

    // ------------------------------------------------------------ 输出

    /** 线程安全。原始字节（终端输出）。 */
    fun writeBytes(b: ByteArray) {
        synchronized(pending) { pending.write(b, 0, b.size) }
        scheduleFlush()
    }

    /**
     * 线程安全。显示普通文字（提示、错误信息）。
     * 只把裸 \n 规范成 \r\n；保留原本的 \r，避免破坏进度条这类带 \r 的输出。
     */
    fun printText(s: String) {
        val normalized = s.replace("\r\n", "\n").replace("\n", "\r\n")
        writeBytes(normalized.toByteArray(Charsets.UTF_8))
    }

    private fun scheduleFlush() {
        synchronized(pending) {
            if (flushScheduled) return
            flushScheduled = true
        }
        if (destroyed) return
        handler.postDelayed({ flush() }, 16)
    }

    private fun flush() {
        if (destroyed) return
        val data: ByteArray? = synchronized(pending) {
            flushScheduled = false
            if (!pageReady) null else pending.toByteArray().also { pending.reset() }
        }
        if (data == null || data.isEmpty()) return
        var off = 0
        while (off < data.size) {
            val n = minOf(CHUNK, data.size - off)
            val b64 = Base64.encodeToString(data, off, n, Base64.NO_WRAP)
            web.evaluateJavascript("w('$b64')", null)
            off += n
        }
    }

    private fun paste() {
        if (destroyed) return
        val cm = activity.getSystemService(ClipboardManager::class.java)
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(activity)?.toString()
        if (!text.isNullOrEmpty()) {
            web.evaluateJavascript("paste(${JSONObject.quote(text)})", null)
        }
    }

    fun destroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        web.destroy()
    }

    // ------------------------------------------------------------ JS 桥

    inner class Bridge {
        @JavascriptInterface
        fun input(d: String) { if (!destroyed) onInput(d) }

        @JavascriptInterface
        fun key(d: String) { if (!destroyed) onKey(d) }

        @JavascriptInterface
        fun resize(cols: Int, rows: Int) { if (!destroyed) onResize(cols, rows) }

        @JavascriptInterface
        fun ready(cols: Int, rows: Int) {
            activity.runOnUiThread {
                if (destroyed) return@runOnUiThread
                pageReady = true
                flush()
                onReady(cols, rows)
            }
        }
    }
}