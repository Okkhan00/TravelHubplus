package com.tabeer.travelhub

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.net.Uri

class PortalActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private val allowedHosts = setOf(
        "traveladvisor.com.pk",
        "www.traveladvisor.com.pk",
        "travelpointaviation.com",
        "www.travelpointaviation.com"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("portal_name") ?: "Travel Portal"
        val url = intent.getStringExtra("portal_url") ?: run { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val header = TextView(this).apply {
            text = "‹   $name"
            textSize = 18f
            setTextColor(Color.rgb(20, 43, 70))
            setPadding(20, 18, 20, 18)
            setOnClickListener { finish() }
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true // Required for modern portal pages; only allowlisted hosts may load.
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val host = uri.host?.lowercase()
                if (uri.scheme == "https" && host in allowedHosts) return false
                Toast.makeText(this@PortalActivity, "Blocked external page for safety. Open it in your browser if needed.", Toast.LENGTH_LONG).show()
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                progress.visibility = ProgressBar.VISIBLE
            }
            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = ProgressBar.GONE
            }
        }
        root.addView(header)
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 5))
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        webView.loadUrl(url)
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
