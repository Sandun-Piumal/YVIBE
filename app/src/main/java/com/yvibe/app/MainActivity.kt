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
import android.webkit.WebResourceResponse
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
import java.io.ByteArrayInputStream

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

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                if (BLOCKED.any { u.contains(it) }) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
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

        // Requests containing any of these are dropped (ad servers / ad tracking)
        val BLOCKED = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_info"
        )

        // Element names can change on the site; adjust if a bar reappears.
        const val HIDE_SITE_BARS_CSS = """
            ytm-mobile-topbar-renderer { display: none !important; }
            ytm-pivot-bar-renderer { display: none !important; }
            ytm-feed-nudge-renderer { display: none !important; }
            ytm-home-logo { display: none !important; }
            body { padding-top: 0 !important; }
            ytm-promoted-sparkles-web-renderer, ytm-promoted-video-renderer,
            ytm-companion-ad-renderer, ytm-ad-slot-renderer,
            ytm-in-feed-ad-layout-renderer, ytm-display-ad-renderer,
            ytm-statement-banner-renderer, ytm-brand-video-singleton-renderer,
            .ytp-ad-overlay-container, .ytp-ad-module, .ad-container,
            ytm-rich-item-renderer:has(ytm-ad-slot-renderer),
            ytm-item-section-renderer:has(ytm-ad-slot-renderer) { display: none !important; }
        """

        // PASTE YOUR EXISTING SCRIPT BETWEEN THE QUOTES BELOW
        const val USER_SCRIPT = """
(function () {
  if (window.__yvibeClean) return;
  window.__yvibeClean = true;

  function findText(str) {
    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    var n;
    while ((n = w.nextNode())) {
      if (n.nodeValue && n.nodeValue.indexOf(str) > -1) return n.parentElement;
    }
    return null;
  }

  function up(el, levels) {
    var i = 0;
    while (el.parentElement && i < levels && el.parentElement !== document.body) {
      el = el.parentElement;
      i++;
    }
    return el;
  }

  function hide(el) {
    el.style.display = 'none';
    el.setAttribute('data-yv', '1');
  }

  function clean() {
    if (!document.body) return;

    // put back whatever we hid last time, then decide again
    var prev = document.querySelectorAll('[data-yv]');
    for (var i = 0; i < prev.length; i++) {
      prev[i].style.display = '';
      prev[i].removeAttribute('data-yv');
    }

    // only the empty home page of a signed-out user
    if (location.pathname !== '/') return;
    var nudge = findText('Try searching to get started');
    if (!nudge) return;

    var nudgeBox = up(nudge, 4);

    // the site's own search row (placeholder text or input)
    var search = document.querySelector('input[placeholder*="Search"]') ||
                 findText('Search YouTube');

    if (search) {
      // hide everything inside the block that holds both the search row and the card
      var a = nudgeBox;
      while (a.parentElement && !a.contains(search)) a = a.parentElement;
      var kids = a.children;
      for (var j = 0; j < kids.length; j++) hide(kids[j]);
    } else {
      hide(nudgeBox);
    }
  }

  clean();
  setInterval(clean, 400);
})();

// ---------- remove the empty space at the top ----------
(function () {
  function zeroTop() {
    var target = document.querySelector('ytm-feed-filter-chip-bar-renderer') ||
                 document.querySelector('ytm-media-item');
    if (!target) return;
    if (window.scrollY > 5) return;
    target.style.setProperty('top', '0px', 'important');
    var el = target.parentElement;
    while (el && el !== document.documentElement) {
      var cs = getComputedStyle(el);
      if (parseFloat(cs.paddingTop) > 0) el.style.setProperty('padding-top', '0', 'important');
      if (parseFloat(cs.marginTop) > 0) el.style.setProperty('margin-top', '0', 'important');
      // empty spacer blocks before it
      var sib = el.previousElementSibling;
      while (sib) {
        var empty = sib.textContent.trim() === '' && !sib.querySelector('img, video, svg, input');
        if (empty && sib.getBoundingClientRect().height > 0) {
          sib.style.setProperty('display', 'none', 'important');
        }
        sib = sib.previousElementSibling;
      }
      el = el.parentElement;
    }
  }
  setInterval(zeroTop, 500);
})();

// ---------- ads ----------
(function () {
  if (window.__yvibeAds) return;
  window.__yvibeAds = true;

  // strip ad data from the player/feed JSON before the page uses it
  function strip(o) {
    try {
      if (!o || typeof o !== 'object') return o;
      if (o.adPlacements) o.adPlacements = [];
      if (o.playerAds) o.playerAds = [];
      if (o.adSlots) o.adSlots = [];
      if (o.playerResponse) strip(o.playerResponse);
      if (o.response) strip(o.response);
    } catch (e) {}
    return o;
  }
  var origParse = JSON.parse;
  JSON.parse = function () { return strip(origParse.apply(this, arguments)); };
  if (window.Response && Response.prototype.json) {
    var origJson = Response.prototype.json;
    Response.prototype.json = function () {
      return origJson.apply(this, arguments).then(strip);
    };
  }

  // skip video ads that still get through
  var muteSaved = null;
  function tick() {
    var player = document.querySelector('.html5-video-player');
    var v = document.querySelector('video');
    var adOn = player && player.classList.contains('ad-showing');
    if (adOn && v) {
      if (muteSaved === null) muteSaved = v.muted;
      v.muted = true;
      if (isFinite(v.duration) && v.duration > 0) v.currentTime = v.duration;
      v.playbackRate = 16;
    } else if (v && muteSaved !== null) {
      v.muted = muteSaved;
      v.playbackRate = 1;
      muteSaved = null;
    }
    var btns = document.querySelectorAll(
      '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, ' +
      '.ytp-ad-skip-button-container button, .ytp-ad-overlay-close-button'
    );
    for (var i = 0; i < btns.length; i++) btns[i].click();
  }
  setInterval(tick, 300);
})();
"""
    }
}
