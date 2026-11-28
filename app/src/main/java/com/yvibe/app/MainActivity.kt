package com.yvibe.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.PopupMenu
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
    private lateinit var header: View
    private lateinit var normalRow: View
    private lateinit var searchRow: View
    private lateinit var searchInput: EditText
    private lateinit var bottomNav: View
    private lateinit var insetsController: WindowInsetsControllerCompat

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var statusTop = 0
    private var navInset = 0
    private var watchMode = false
    private var hasBrowsed = false   // true once you open anything other than the welcome page

    private class Tab(
        val box: View, val icon: ImageView, val label: TextView, val bar: View,
        val url: String, val outline: Int, val filled: Int
    )
    private val tabs = mutableListOf<Tab>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_YVIBE)          // leave the splash theme
        super.onCreate(savedInstanceState)
        hasBrowsed = getSharedPreferences("yvibe", MODE_PRIVATE).getBoolean("used", false)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        web = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        header = findViewById(R.id.header)
        normalRow = findViewById(R.id.normalRow)
        searchRow = findViewById(R.id.searchRow)
        searchInput = findViewById(R.id.searchInput)
        bottomNav = findViewById(R.id.bottomNav)

        insetsController = WindowInsetsControllerCompat(window, root)
        insetsController.isAppearanceLightStatusBars = false      // white icons on the purple header
        insetsController.isAppearanceLightNavigationBars = true

        // header goes under the status bar, bottom nav floats above the gesture bar
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusTop = bars.top
            navInset = bars.bottom
            applyHeaderPadding()
            val lp = bottomNav.layoutParams as FrameLayout.LayoutParams
            lp.bottomMargin = bars.bottom + dp(14)
            bottomNav.layoutParams = lp
            insets
        }

        web.setBackgroundColor(Color.TRANSPARENT)

        setupHeader()
        setupTabs()
        setupWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    searchRow.visibility == View.VISIBLE -> closeSearch()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })

        if (savedInstanceState == null) web.loadUrl(if (hasBrowsed) BASE else START_URL) else web.restoreState(savedInstanceState)
    }

    private fun applyHeaderPadding() {
        if (watchMode) header.setPadding(header.paddingLeft, statusTop, header.paddingRight, 0)
        else header.setPadding(header.paddingLeft, statusTop + dp(4), header.paddingRight, dp(10))
    }

    /** While a video is open: no purple header, no bottom bar (like the official app). */
    private fun setWatchMode(on: Boolean) {
        if (on == watchMode) return
        watchMode = on
        val container = findViewById<View>(R.id.webContainer)
        val clp = container.layoutParams as LinearLayout.LayoutParams
        val corners = listOf<View>(findViewById(R.id.cornerTl), findViewById(R.id.cornerTr))
        if (on) {
            searchInput.clearFocus()
            normalRow.visibility = View.GONE
            searchRow.visibility = View.GONE
            header.setBackgroundColor(Color.BLACK)
            clp.topMargin = 0
            corners.forEach { it.visibility = View.GONE }
            bottomNav.visibility = View.GONE
        } else {
            header.setBackgroundResource(R.drawable.bg_header)
            normalRow.visibility = View.VISIBLE
            clp.topMargin = 0
            corners.forEach { it.visibility = View.VISIBLE }
            bottomNav.visibility = View.VISIBLE
        }
        container.layoutParams = clp
        applyHeaderPadding()
    }

    // ---------- Header: menu, search, bell ----------
    private fun setupHeader() {
        findViewById<View>(R.id.menuBtn).setOnClickListener { v ->
            val m = PopupMenu(this, v)
            m.menu.add(0, 0, 0, "Home")
            m.menu.add(0, 1, 1, "Subscriptions")
            m.menu.add(0, 2, 2, "History")
            m.menu.add(0, 3, 3, "Playlists")
            m.menu.add(0, 4, 4, "Liked videos")
            m.menu.add(0, 5, 5, "Watch later")
            m.setOnMenuItemClickListener { item ->
                val url = when (item.itemId) {
                    0 -> if (hasBrowsed) BASE else START_URL
                    1 -> "$BASE/feed/subscriptions"
                    2 -> "$BASE/feed/history"
                    3 -> "$BASE/feed/library"
                    4 -> "$BASE/playlist?list=LL"
                    else -> "$BASE/playlist?list=WL"
                }
                web.loadUrl(url)
                true
            }
            m.show()
        }

        findViewById<View>(R.id.bellBtn).setOnClickListener {
            findViewById<View>(R.id.bellDot).visibility = View.GONE   // the red dot is cosmetic
            web.loadUrl("$BASE/feed/notifications")
        }

        findViewById<View>(R.id.searchBtn).setOnClickListener { openSearch() }
        findViewById<View>(R.id.searchBack).setOnClickListener { closeSearch() }
        findViewById<View>(R.id.searchGo).setOnClickListener { doSearch() }
        searchInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }
    }

    private fun imm() = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

    private fun openSearch() {
        normalRow.visibility = View.GONE
        searchRow.visibility = View.VISIBLE
        searchInput.requestFocus()
        imm().showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        searchRow.visibility = View.GONE
        normalRow.visibility = View.VISIBLE
        searchInput.clearFocus()
        imm().hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun doSearch() {
        val q = searchInput.text.toString().trim()
        if (q.isEmpty()) return
        web.loadUrl("$BASE/results?search_query=" + Uri.encode(q))
        closeSearch()
    }

    // ---------- Bottom navigation ----------
    private fun tab(id: String, url: String, outline: Int, filled: Int): Tab {
        fun v(suffix: String) = findViewById<View>(resources.getIdentifier("nav$id$suffix", "id", packageName))
        return Tab(v(""), v("Icon") as ImageView, v("Label") as TextView, v("Bar"), url, outline, filled)
    }

    private fun setupTabs() {
        tabs += tab("Home", BASE, R.drawable.ic_home, R.drawable.ic_home_f)
        tabs += tab("Shorts", "$BASE/shorts", R.drawable.ic_shorts, R.drawable.ic_shorts_f)
        tabs += tab("Subs", "$BASE/feed/subscriptions", R.drawable.ic_subs, R.drawable.ic_subs_f)
        tabs += tab("You", "$BASE/feed/library", R.drawable.ic_you, R.drawable.ic_you_f)
        tabs.forEachIndexed { i, t ->
            t.box.setOnClickListener {
                selectTab(i)
                // Home shows the welcome page until you have actually used the app,
                // after that it is the normal home feed.
                web.loadUrl(if (i == 0 && !hasBrowsed) START_URL else t.url)
            }
        }
        selectTab(0)
    }

    private fun selectTab(index: Int) {
        val purple = ContextCompat.getColor(this, R.color.purple)
        val sub = ContextCompat.getColor(this, R.color.ink_sub)
        tabs.forEachIndexed { i, t ->
            val on = i == index
            t.icon.setImageResource(if (on) t.filled else t.outline)
            t.icon.setColorFilter(if (on) purple else sub)
            t.label.setTextColor(if (on) purple else sub)
            t.label.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            t.bar.visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun tabForUrl(url: String): Int {
        val path = Uri.parse(url).path ?: "/"
        return when {
            path.startsWith("/shorts") -> 1
            path.startsWith("/feed/subscriptions") -> 2
            path.startsWith("/feed/library") || path.startsWith("/feed/history") ||
                path.startsWith("/playlist") -> 3
            else -> 0
        }
    }

    // ---------- remove the empty strip above the video player ----------
    // The page tells us how tall that strip is (in CSS px); we slide the WebView up by that
    // amount so the strip is clipped off, and extend it at the bottom so nothing is lost.
    private var cropPx = 0

    private inner class CropBridge {
        @JavascriptInterface
        fun crop(cssPx: Double) {
            runOnUiThread { applyCrop(cssPx) }
        }

        @JavascriptInterface
        fun watch(on: Boolean) {
            runOnUiThread { setWatchMode(on) }
        }
    }

    private fun applyCrop(cssPx: Double) {
        val px = (cssPx * resources.displayMetrics.density).toInt().coerceIn(0, 400)
        if (px == cropPx) return
        cropPx = px
        val lp = web.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = -px
        web.layoutParams = lp
        web.translationY = -px.toFloat()
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

        web.addJavascriptInterface(CropBridge(), "YV")

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
                injectCss(view)
                if (url.startsWith("http") && url != START_URL && !hasBrowsed) {
                    hasBrowsed = true
                    getSharedPreferences("yvibe", MODE_PRIVATE).edit().putBoolean("used", true).apply()
                }
                if (!url.contains("/watch")) applyCrop(0.0)
                setWatchMode(url.contains("/watch"))
                selectTab(tabForUrl(url))
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                injectCss(view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                if (url.startsWith("http") && url != START_URL) injectUi(view)   // not on our own welcome page
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                if (u == START_URL) {
                    return WebResourceResponse(
                        "text/html", "utf-8", ByteArrayInputStream(WELCOME_HTML.toByteArray(Charsets.UTF_8))
                    )
                }
                if (u == LOGO_URL) {
                    return WebResourceResponse("image/png", null, resources.openRawResource(R.drawable.yvibe_logo))
                }
                if (BLOCKED.any { u.contains(it) }) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return false
                val internal = host == "yvibe.local" || host.endsWith("youtube.com") || host.endsWith("google.com") ||
                    host.endsWith("gstatic.com") || host.endsWith("googlevideo.com")
                if (internal) return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                injectCss(view)
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
    /** Hides the site's own bars. Called early and often so they never flash on screen. */
    private fun injectCss(view: WebView) {
        val url = view.url ?: return
        if (!url.startsWith("http") || url == START_URL) return
        val css = JSONObject.quote(HIDE_SITE_BARS_CSS)
        view.evaluateJavascript(
            "(function(){var s=document.getElementById('yvibe-css');" +
                "if(!s){s=document.createElement('style');s.id='yvibe-css';" +
                "(document.head||document.documentElement).appendChild(s);}" +
                "s.textContent=$css;})();", null
        )
    }

    private fun injectUi(view: WebView) {
        injectCss(view)
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
        const val LOGO_URL = "https://yvibe.local/logo.png"

        const val WELCOME_HTML = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>YVIBE</title>
<style>
  *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
  html,body{margin:0;height:100%}
  body{
    font-family:system-ui,-apple-system,"Noto Sans",Roboto,sans-serif;
    color:#1e1b3a;
    background:linear-gradient(180deg,#f8f5ff 0%,#efe6ff 55%,#fae6ff 100%);
    min-height:100vh;overflow-x:hidden;position:relative;
  }
  .orb{position:fixed;border-radius:50%;filter:blur(60px);opacity:.45;z-index:0;animation:float 9s ease-in-out infinite}
  .o1{width:240px;height:240px;background:#a25bff;left:-80px;top:-40px}
  .o2{width:220px;height:220px;background:#7c3aed;right:-90px;top:42%;animation-delay:-3s}
  .o3{width:240px;height:240px;background:#ff7ad9;left:-40px;bottom:-60px;animation-delay:-6s}
  @keyframes float{0%,100%{transform:translateY(0)}50%{transform:translateY(-18px)}}
  @keyframes bob{0%,100%{transform:translateY(3px)}50%{transform:translateY(-3px)}}
  main{position:relative;z-index:1;display:flex;flex-direction:column;align-items:center;
       padding:40px 24px 32px;text-align:center;min-height:100vh}
  .logo-img{width:250px;max-width:82%;height:auto;margin-top:6px}
  h1{margin:16px 0 10px;font-size:32px;line-height:1.15;letter-spacing:-.8px;font-weight:800}
  h1 span{background:linear-gradient(90deg,#6d28d9,#c026d3);-webkit-background-clip:text;background-clip:text;color:transparent}
  .sub{margin:0;max-width:300px;font-size:16px;color:#6b6890;line-height:1.55}
  .pointer{margin-top:22px;display:inline-flex;align-items:center;gap:8px;padding:11px 18px;border-radius:24px;
           background:rgba(255,255,255,.72);border:1px solid #fff;font-weight:700;font-size:14px;color:#3b2a6b;
           box-shadow:0 10px 24px rgba(124,58,237,.18)}
  .pointer svg{animation:bob 1.4s ease-in-out infinite}
  .label{margin:32px 0 12px;font-size:13px;font-weight:700;color:#6b6890;letter-spacing:.4px;text-transform:uppercase}
  .grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;width:100%;max-width:360px}
  a.card{display:flex;align-items:center;gap:10px;padding:14px;border-radius:22px;text-decoration:none;color:#1e1b3a;
         background:rgba(255,255,255,.62);border:1px solid rgba(255,255,255,.95);
         box-shadow:0 10px 26px rgba(124,58,237,.16);font-weight:700;font-size:14px}
  a.card:active{transform:scale(.97)}
  .dot{width:34px;height:34px;border-radius:12px;flex:none}
  .d1{background:linear-gradient(135deg,#ff7ad9,#ffb36e)}
  .d2{background:linear-gradient(135deg,#7c3aed,#a855f7)}
  .d3{background:linear-gradient(135deg,#6d28d9,#38bdf8)}
  .d4{background:linear-gradient(135deg,#f59e0b,#ef4444)}
  .d5{background:linear-gradient(135deg,#38bdf8,#7c3aed)}
  .d6{background:linear-gradient(135deg,#ec4899,#7c3aed)}
</style>
</head>
<body>
<div class="orb o1"></div><div class="orb o2"></div><div class="orb o3"></div>
<main>
  <img class="logo-img" src="https://yvibe.local/logo.png" alt="YVIBE">

  <h1>Search for your<br><span>next favourite video</span></h1>
  <p class="sub">Songs, shows, news, tutorials or anything you feel like watching. Just search and press play.</p>

  <div class="pointer">
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#7c3aed" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 19V5M6 11l6-6 6 6"/></svg>
    Type in the search bar above
  </div>

  <div class="label">Or start with</div>
  <div class="grid">
    <a class="card" href="https://m.youtube.com/results?search_query=trending+videos"><span class="dot d1"></span>Trending</a>
    <a class="card" href="https://m.youtube.com/results?search_query=sinhala+songs"><span class="dot d2"></span>Sinhala songs</a>
    <a class="card" href="https://m.youtube.com/results?search_query=gaming"><span class="dot d3"></span>Gaming</a>
    <a class="card" href="https://m.youtube.com/results?search_query=learn+something+new"><span class="dot d4"></span>Learning</a>
    <a class="card" href="https://m.youtube.com/results?search_query=news+today"><span class="dot d5"></span>News</a>
    <a class="card" href="https://m.youtube.com/results?search_query=live"><span class="dot d6"></span>Live</a>
  </div>
</main>
</body>
</html>
"""

        const val BASE = "https://m.youtube.com"

        // First page shown when the app opens (and for the Home tab).
        const val START_URL = "https://yvibe.local/welcome"   // served from WELCOME_HTML below, no extra file

        // Requests containing any of these are dropped (ad servers / ad tracking)
        val BLOCKED = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_info"
        )

        // Element names can change on the site; adjust if a bar reappears.
        const val HIDE_SITE_BARS_CSS = """
            /* site's own bars (we draw native ones) */
            ytm-mobile-topbar-renderer, header.mobile-topbar-header, .mobile-topbar-header,
            ytm-pivot-bar-renderer, ytm-pivot-bar-item-renderer, .pivot-bar-wrapper,
            ytm-search-header, ytm-searchbox,
            ytm-feed-nudge-renderer, ytm-home-logo { display: none !important; }

            /* ads */
            ytm-promoted-sparkles-web-renderer, ytm-promoted-video-renderer,
            ytm-companion-ad-renderer, ytm-ad-slot-renderer,
            ytm-in-feed-ad-layout-renderer, ytm-display-ad-renderer,
            ytm-statement-banner-renderer, ytm-brand-video-singleton-renderer,
            .ytp-ad-overlay-container, .ytp-ad-module, .ad-container,
            ytm-rich-item-renderer:has(ytm-ad-slot-renderer),
            ytm-item-section-renderer:has(ytm-ad-slot-renderer) { display: none !important; }

            /* ---------- YVIBE purple skin ---------- */
            html, body, ytm-app { background: #F4F0FF !important; }
            html {
                --yt-spec-base-background: #F4F0FF !important;
                --yt-spec-general-background-a: #F4F0FF !important;
                --yt-spec-general-background-b: #F4F0FF !important;
                --yt-spec-general-background-c: #F4F0FF !important;
                --yt-spec-text-primary: #1E1B3A !important;
                --yt-spec-text-secondary: #6B6890 !important;
            }
            ytm-browse, ytm-search, ytm-watch, ytm-feed-filter-chip-bar-renderer {
                background: transparent !important;
            }
            body { padding-bottom: 100px !important; }

            ytm-chip-cloud-chip-renderer .chip-container {
                background: rgba(255,255,255,0.8) !important;
                border: 1px solid #FFFFFF !important;
                border-radius: 16px !important;
               
                color: #1E1B3A !important;
            }
            ytm-chip-cloud-chip-renderer[selected] .chip-container,
            ytm-chip-cloud-chip-renderer.selected .chip-container,
            ytm-chip-cloud-chip-renderer .chip-container.selected,
            ytm-chip-cloud-chip-renderer .chip-container[aria-selected="true"] {
                background: linear-gradient(135deg, #5B21B6, #7C3AED) !important;
                color: #FFFFFF !important;
               
            }
            .media-item-thumbnail-container, ytm-thumbnail-cover, .video-thumbnail-container-large {
                border-radius: 16px !important;
                overflow: hidden !important;
            }

            /* chips, pinned bar, thumbnails (classes are set by the script below) */
            .yv-pin { top: 0 !important; transform: none !important; background: #F4F0FF !important; z-index: 5 !important; }
            .yv-gone { display: none !important; }
            .yv-chip { background: rgba(255,255,255,0.88) !important; border: 1px solid #FFFFFF !important; border-radius: 16px !important; }
            .yv-chip, .yv-chip * { color: #1E1B3A !important; }
            .yv-chip-on { background: linear-gradient(135deg, #5B21B6, #7C3AED) !important; }
            .yv-chip-on, .yv-chip-on * { color: #FFFFFF !important; }
            .yv-thumb { margin-left: 12px !important; margin-right: 12px !important; width: auto !important; border-radius: 16px !important; overflow: hidden !important; }
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

    // empty home (nothing watched yet): show our welcome page instead
    location.replace('https://yvibe.local/welcome');
    return;
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

// ---------- remove the empty space at the top (not on video pages) ----------
(function () {
  var mods = [];

  function remember(el) {
    if (el.hasAttribute('data-yv-style')) return;
    el.setAttribute('data-yv-style', el.getAttribute('style') || '');
    mods.push(el);
  }

  function restoreAll() {
    for (var i = 0; i < mods.length; i++) {
      var el = mods[i];
      if (el.hasAttribute('data-yv-style')) {
        var s = el.getAttribute('data-yv-style');
        if (s) el.setAttribute('style', s); else el.removeAttribute('style');
        el.removeAttribute('data-yv-style');
      }
    }
    mods = [];
  }

  function zeroTop() {
    var path = location.pathname;
    // leave the video / shorts pages exactly as the site built them
    if (path.indexOf('/watch') === 0 || path.indexOf('/shorts') === 0 ||
        path.indexOf('/playlist') === 0 || path.indexOf('/embed') === 0) {
      if (mods.length) restoreAll();
      return;
    }
    var target = document.querySelector('ytm-feed-filter-chip-bar-renderer') ||
                 document.querySelector('ytm-media-item');
    if (!target) return;
    if (window.scrollY > 5) return;
    remember(target);
    target.style.setProperty('top', '0px', 'important');
    // no chip bar here (search results etc.): keep a small gap under the header
    if (target.tagName.toLowerCase() === 'ytm-media-item') {
      target.style.setProperty('margin-top', '14px', 'important');
    }
    var el = target.parentElement;
    while (el && el !== document.documentElement) {
      var cs = getComputedStyle(el);
      var pt = parseFloat(cs.paddingTop) > 0;
      var mt = parseFloat(cs.marginTop) > 0;
      if (pt || mt) remember(el);
      if (pt) el.style.setProperty('padding-top', '0', 'important');
      if (mt) el.style.setProperty('margin-top', '0', 'important');
      var sib = el.previousElementSibling;
      while (sib) {
        var empty = sib.textContent.trim() === '' && !sib.querySelector('img, video, svg, input');
        if (empty && sib.getBoundingClientRect().height > 0) {
          remember(sib);
          sib.style.setProperty('display', 'none', 'important');
        }
        sib = sib.previousElementSibling;
      }
      el = el.parentElement;
    }
  }
  setInterval(zeroTop, 500);
})();

// ---------- tell the app when a video page is open ----------
(function () {
  if (window.__yvMode) return;
  window.__yvMode = true;
  var last = null;
  function tick() {
    var w = location.pathname.indexOf('/watch') === 0;
    if (w !== last) { last = w; try { YV.watch(w); } catch (e) {} }
  }
  tick();
  setInterval(tick, 300);
})();

// ---------- cut the empty strip above the video player ----------
(function () {
  if (window.__yvCrop) return;
  window.__yvCrop = true;
  var last = -1;

  function report(v) {
    if (v === last) return;
    last = v;
    try { YV.crop(v); } catch (e) {}
  }

  function measure() {
    if (location.pathname.indexOf('/watch') !== 0) { report(0); return; }
    var box = document.querySelector('.html5-video-player') || document.querySelector('video');
    if (!box) return;
    if (window.scrollY > 1) return;          // only trust it while at the top
    var r = box.getBoundingClientRect();
    if (r.height < 50) return;
    var g = Math.round(r.top);
    if (g < 0 || g > 150) return;
    report(g <= 2 ? 0 : g);
  }
  setInterval(measure, 400);
})();

// ---------- hide the site's own top / bottom bars whenever they appear ----------
(function () {
  if (window.__yvBars) return;
  window.__yvBars = true;
  var sel = 'ytm-app > *, body > *, header, nav, [class*="topbar"], [class*="pivot"], ' +
            '[class*="bottom-bar"], [class*="search-header"]';

  function hide(el) {
    el.style.setProperty('display', 'none', 'important');
    el.setAttribute('data-yv-bar', '1');
  }

  function sweep() {
    var vh = window.innerHeight, vw = window.innerWidth;
    var els = document.querySelectorAll(sel);
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.getAttribute('data-yv-bar')) continue;
      var cs = getComputedStyle(el);
      if (cs.position !== 'fixed' && cs.position !== 'sticky') continue;
      if (cs.display === 'none') continue;
      var r = el.getBoundingClientRect();
      if (r.width < vw * 0.8 || r.height > 140) continue;
      if (el.querySelector('video, .html5-video-player')) continue;   // never touch the player
      var txt = (el.textContent || '').replace(/\s+/g, ' ').trim();
      var atBottom = r.bottom >= vh - 4 && r.top > vh - 150;
      var atTop = r.top <= 4;
      if (atBottom && /Home/.test(txt) && /(Shorts|You|Subscriptions)/.test(txt)) {
        hide(el);
      } else if (atTop && (el.querySelector('input, [role="search"], ytm-searchbox, ytm-home-logo') ||
                 /topbar|search-header/i.test(el.tagName + ' ' + el.className))) {
        hide(el);
      }
    }
  }
  setInterval(sweep, 300);
})();

// ---------- purple skin: chips, pinned chip bar, thumbnails ----------
(function () {
  if (window.__yvSkin) return;
  window.__yvSkin = true;

  function isDark(rgb) {
    var m = (rgb || '').match(/[\d.]+/g);
    if (!m || m.length < 3) return false;
    var a = m.length > 3 ? parseFloat(m[3]) : 1;
    if (a < 0.3) return false;
    return (+m[0] + +m[1] + +m[2]) < 330;
  }

  function findAllChip() {
    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    var n;
    while ((n = w.nextNode())) {
      if (n.nodeValue.trim() !== 'All') continue;
      var e = n.parentElement;
      for (var i = 0; i < 6 && e; i++) {
        var r = e.getBoundingClientRect();
        var cs = getComputedStyle(e);
        if (r.top < 420 && r.height >= 26 && r.height <= 60 &&
            parseFloat(cs.borderTopLeftRadius) >= 6) return e;
        e = e.parentElement;
      }
    }
    return null;
  }

  function skin() {
    var p = location.pathname;
    if (p.indexOf('/watch') === 0 || p.indexOf('/shorts') === 0) return;
    if (!document.body) return;

    // chips
    var first = findAllChip();
    if (first) {
      var cls = first.classList.length ? first.classList[0] : '';
      var sel = first.tagName + (cls ? '.' + CSS.escape(cls) : '');
      var top = first.getBoundingClientRect().top;
      var list = document.querySelectorAll(sel);
      for (var i = 0; i < list.length; i++) {
        var el = list[i];
        var r = el.getBoundingClientRect();
        if (Math.abs(r.top - top) > 6 || r.height < 20) continue;
        el.classList.remove('yv-chip', 'yv-chip-on');     // read the site's own colour first
        var dark = isDark(getComputedStyle(el).backgroundColor);
        el.classList.add('yv-chip');
        if (dark) el.classList.add('yv-chip-on');
      }
      // remove the compass / drawer chip that sits before "All"
      var up = first;
      for (var u = 0; u < 3 && up && up !== document.body; u++) {
        var sb = up.previousElementSibling;
        while (sb) {
          if (sb.textContent.trim() === '' && sb.getBoundingClientRect().height < 80) {
            sb.classList.add('yv-gone');
          }
          sb = sb.previousElementSibling;
        }
        up = up.parentElement;
      }
      // keep the chip bar pinned at the very top while scrolling
      var a = first.parentElement;
      for (var j = 0; j < 10 && a && a !== document.body; j++) {
        var pos = getComputedStyle(a).position;
        if (pos === 'sticky' || pos === 'fixed') { a.classList.add('yv-pin'); break; }
        a = a.parentElement;
      }
    }

    // big video thumbnails: side margins + rounded corners
    var imgs = document.querySelectorAll('img');
    for (var k = 0; k < imgs.length; k++) {
      var img = imgs[k];
      var ir = img.getBoundingClientRect();
      if (ir.width < 250 || ir.height < 120) continue;
      var best = img, cur = img;
      for (var d = 0; d < 4; d++) {
        var par = cur.parentElement;
        if (!par || par === document.body) break;
        var pr = par.getBoundingClientRect();
        if (Math.abs(pr.width - ir.width) <= 4 && pr.height >= ir.height - 4 && pr.height <= ir.height + 8) {
          best = par; cur = par;
        } else break;
      }
      if (best !== document.body && !best.classList.contains('yv-thumb')) best.classList.add('yv-thumb');
    }
  }
  setInterval(skin, 600);
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
