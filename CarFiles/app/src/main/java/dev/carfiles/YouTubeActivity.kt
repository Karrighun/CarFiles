package dev.carfiles

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity

class YouTubeActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var layout: LinearLayout
    private lateinit var status: TextView
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goBack() }
        })
        layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setBackgroundColor(Theme.bg) }
        val toolbar = LinearLayout(this).apply { setPadding(dp(12), dp(8), dp(12), dp(4)) }
        fun tool(title: String, action: () -> Unit) = toolbar.addView(label(title, 15f, Theme.text, bold = true).apply {
            gravity = android.view.Gravity.CENTER; minHeight = dp(44); background = rounded(Theme.surfaceHi, 14, Theme.stroke); setOnClickListener { action() }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        tool("Назад") { goBack() }
        tool("YouTube") { web.loadUrl("https://m.youtube.com/") }
        tool("Обновить") { web.reload() }
        layout.addView(toolbar)
        status = TextView(this).apply { text = "YouTube · встроенная веб-версия"; setTextColor(Theme.muted); setPadding(dp(16), dp(8), dp(16), dp(8)) }
        layout.addView(status)
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (YouTubeNavigation.isAllowed(uri.scheme, uri.host)) return false
                if (uri.scheme == "https") runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { status.text = "Загрузка YouTube…" }
            override fun onPageFinished(view: WebView?, url: String?) { status.text = "YouTube · веб-версия" }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) status.text = "Ошибка сети: ${error?.description}. Можно повторить загрузку."
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || fullView != null) { callback?.onCustomViewHidden(); return }
                fullView = view; fullCallback = callback
                (layout.parent as? android.view.ViewGroup)?.removeView(layout)
                setContentView(view)
            }
            override fun onHideCustomView() { exitFullScreen() }
        }
        layout.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        if (state == null) web.loadUrl("https://m.youtube.com/") else web.restoreState(state)
    }
    private fun exitFullScreen() {
        fullView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        fullView = null
        val callback = fullCallback; fullCallback = null; callback?.onCustomViewHidden()
        setContentView(layout)
    }
    private fun goBack() { if (fullView != null) exitFullScreen() else if (web.canGoBack()) web.goBack() else finish() }
    override fun onSaveInstanceState(outState: Bundle) { web.saveState(outState); super.onSaveInstanceState(outState) }
    override fun onResume() { super.onResume(); if (::web.isInitialized) web.onResume() }
    override fun onPause() {
        if (::web.isInitialized) { web.onPause(); web.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(v){v.pause()})", null) }
        super.onPause()
    }
    override fun onDestroy() {
        fullCallback?.onCustomViewHidden(); fullCallback = null
        if (::web.isInitialized) { (web.parent as? android.view.ViewGroup)?.removeView(web); web.destroy() }
        super.onDestroy()
    }
}
