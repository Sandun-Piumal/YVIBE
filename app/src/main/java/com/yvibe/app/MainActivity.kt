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

        if (savedInstanceState == null) web.loadUrl(START_URL) else web.restoreState(savedInstanceState)
    }

    // ---------- Bottom navigation ----------
    private fun setupTabs() {
        tabs += Tab(findViewById(R.id.navHome), findViewById(R.id.navHomeIcon), findViewById(R.id.navHomeLabel), START_URL)
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

        // First page shown when the app opens (and for the Home tab).
        const val START_URL = BASE

        // Element names can change on the site; adjust if a bar reappears.
        const val HIDE_SITE_BARS_CSS = """
            ytm-mobile-topbar-renderer { display: none !important; }
            ytm-pivot-bar-renderer { display: none !important; }
            ytm-feed-nudge-renderer { display: none !important; }
            ytm-home-logo { display: none !important; }
            body { padding-top: 0 !important; }

            /* ---------- YVIBE light glass skin ---------- */
            html, body, ytm-app {
                background: linear-gradient(180deg, #EDEBFF 0%, #DCEBFF 50%, #FBE3F3 100%) fixed !important;
            }
            html {
                --yt-spec-base-background: transparent !important;
                --yt-spec-general-background-a: transparent !important;
                --yt-spec-general-background-b: transparent !important;
                --yt-spec-general-background-c: transparent !important;
                --yt-spec-text-primary: #1B1A33 !important;
                --yt-spec-text-secondary: #58567A !important;
            }
            ytm-browse, ytm-search, ytm-watch, ytm-feed-filter-chip-bar-renderer {
                background: transparent !important;
            }
            ytm-media-item {
                background: rgba(255,255,255,0.6) !important;
                border: 1px solid rgba(255,255,255,0.9) !important;
                border-radius: 22px !important;
                margin: 10px 12px !important;
                padding: 8px !important;
                box-shadow: 0 10px 28px rgba(90,80,160,0.18) !important;
                overflow: hidden !important;
            }
            .media-item-thumbnail-container, ytm-thumbnail-cover, .video-thumbnail-container-large {
                border-radius: 18px !important;
                overflow: hidden !important;
            }
            ytm-chip-cloud-chip-renderer .chip-container {
                background: rgba(255,255,255,0.6) !important;
                border: 1px solid rgba(255,255,255,0.9) !important;
                border-radius: 20px !important;
                color: #1B1A33 !important;
            }
            ytm-chip-cloud-chip-renderer[selected] .chip-container {
                background: #1B1A33 !important;
                color: #FFFFFF !important;
            }
        """

        // PASTE YOUR EXISTING SCRIPT BETWEEN THE QUOTES BELOW
        const val USER_SCRIPT = """
(function () {
  if (window.__yvibeClean) return;
  window.__yvibeClean = true;

  var st = document.createElement('style');
  st.textContent =
    'ytm-home-logo, ytm-searchbox, ytm-feed-nudge-renderer {display:none !important;}';
  (document.head || document.documentElement).appendChild(st);

  function hideUp(el, levels) {
    var n = 0;
    while (el.parentElement && n < levels && el.parentElement !== document.body) {
      el = el.parentElement;
      n++;
    }
    el.style.display = 'none';
  }

  function clean() {
    if (!document.body) return;

    // "Try searching to get started" card
    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    var node;
    while ((node = w.nextNode())) {
      if (node.nodeValue && node.nodeValue.indexOf('Try searching to get started') > -1) {
        hideUp(node.parentElement, 4);
      }
    }

    // The site's own search row (compass + "Search YouTube" + mic)
    var inputs = document.querySelectorAll('input[placeholder], input[type="search"]');
    for (var i = 0; i < inputs.length; i++) {
      var ph = (inputs[i].getAttribute('placeholder') || '').toLowerCase();
      if (ph.indexOf('search') > -1) hideUp(inputs[i], 3);
    }
  }

  clean();
  setInterval(clean, 400);
})();
"""
    }
}
