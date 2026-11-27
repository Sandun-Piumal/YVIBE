package com.yvibe.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var topBar: View
    private lateinit var bottomNav: View
    private lateinit var webContainer: View
    private lateinit var searchInput: EditText
    private lateinit var insetsController: WindowInsetsControllerCompat

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private class Tab(val box: View, val icon: ImageView, val label: TextView, val url: String)
    private val tabs = mutableListOf<Tab>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_YVIBE)          // leave the splash theme
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        web = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        topBar = findViewById(R.id.topBar)
        bottomNav = findViewById(R.id.bottomNav)
        webContainer = findViewById(R.id.webContainer)
        searchInput = findViewById(R.id.searchInput)

        insetsController = WindowInsetsControllerCompat(window, root)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        setupTabs()
        setupSearch()
        setupWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })

        if (savedInstanceState == null) web.loadUrl(BASE) else web.restoreState(savedInstanceState)
    }

    // ---------- Bottom navigation ----------
    private fun setupTabs() {
        tabs += Tab(findViewById(R.id.navHome), findViewById(R.id.navHomeIcon), findViewById(R.id.navHomeLabel), BASE)
        tabs += Tab(findViewById(R.id.navShorts), findViewById(R.id.navShortsIcon), findViewById(R.id.navShortsLabel), "$BASE/shorts")
        tabs += Tab(findViewById(R.id.navSubs), findViewById(R.id.navSubsIcon), findViewById(R.id.navSubsLabel), "$BASE/feed/subscriptions")
        tabs += Tab(findViewById(R.id.navLib), findViewById(R.id.navLibIcon), findViewById(R.id.navLibLabel), "$BASE/feed/library")
        tabs.forEachIndexed { i, t ->
            t.box.setOnClickListener { selectTab(i); web.loadUrl(t.url) }
        }
        selectTab(0)
    }

    private fun selectTab(index: Int) {
        val ink = ContextCompat.getColor(this, R.color.ink)
        val white = ContextCompat.getColor(this, R.color.white)
        tabs.forEachIndexed { i, t ->
            val on = i == index
            t.box.setBackgroundResource(if (on) R.drawable.bg_nav_active else 0)
            t.icon.setColorFilter(if (on) white else ink)
            t.label.visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    private fun tabForUrl(url: String): Int {
        val path = Uri.parse(url).path ?: "/"
        return when {
            path.startsWith("/shorts") -> 1
            path.startsWith("/feed/subscriptions") -> 2
            path.startsWith("/feed/library") || path.startsWith("/feed/history") -> 3
            else -> 0
        }
    }

    // ---------- Search ----------
    private fun setupSearch() {
        val go = {
            val q = searchInput.text.toString().trim()
            if (q.isNotEmpty()) {
                web.loadUrl("$BASE/results?search_query=" + Uri.encode(q))
                searchInput.clearFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(searchInput.windowToken, 0)
            }
        }
        searchInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { go(); true } else false
        }
        findViewById<View>(R.id.searchBtn).setOnClickListener { go() }
    }

    // ---------- WebView ----------
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // NOTE: keep any extra WebView settings you already had in your old app
        // (user agent, cookies, etc.) -- paste them here.
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
                selectTab(tabForUrl(url))
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                injectUi(view)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return false
                val internal = host.endsWith("youtube.com") || host.endsWith("google.com") ||
                    host.endsWith("gstatic.com") || host.endsWith("googlevideo.com")
                if (internal) return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
            }

            // Fullscreen video
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view
                customCallback = callback
                (window.decorView as ViewGroup).addView(
                    view, FrameLayout.LayoutParams(-1, -1)
                )
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            override fun onHideCustomView() = hideCustomView()
        }
    }

    private fun hideCustomView() {
        val v = customView ?: return
        (window.decorView as ViewGroup).removeView(v)
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        insetsController.show(WindowInsetsCompat.Type.systemBars())
    }

    /** CSS to hide the website's own top bar / bottom bar (we draw native ones). */
    private fun injectUi(view: WebView) {
        val css = JSONObject.quote(HIDE_SITE_BARS_CSS)
        view.evaluateJavascript(
            "(function(){var s=document.getElementById('yvibe-css');" +
                "if(!s){s=document.createElement('style');s.id='yvibe-css';document.head.appendChild(s);}" +
                "s.textContent=$css;})();", null
        )
        // >>> Your existing script (the one you already use in the old app) runs here.
        view.evaluateJavascript(USER_SCRIPT, null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val BASE = "https://m.youtube.com"

        // Element names can change on the site; adjust if a bar reappears.
        const val HIDE_SITE_BARS_CSS = """
            ytm-mobile-topbar-renderer { display: none !important; }
            ytm-pivot-bar-renderer { display: none !important; }
            body { padding-top: 0 !important; }
        """

        // PASTE YOUR EXISTING SCRIPT BETWEEN THE QUOTES BELOW
        const val USER_SCRIPT = """
            // your existing script goes here
        """
    }
}
