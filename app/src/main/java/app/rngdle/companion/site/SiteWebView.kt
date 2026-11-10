package app.rngdle.companion.site

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import app.rngdle.companion.reminder.ReminderPolicy
import java.time.Instant
import org.json.JSONObject
import org.json.JSONTokener

class SiteWebView(
    context: Context,
    initialPage: SitePage,
    initialAuth: AuthState,
    private val onState: (PageState) -> Unit,
    private val onPage: (SitePage) -> Unit,
    private val onAuth: (AuthState) -> Unit,
    private val onRendererGone: (SiteWebView) -> Unit,
) : WebView(context) {
    private var pageState: PageState = PageState.Loading
    private var auth = if (initialAuth == AuthState.CHECK_MAIL) initialAuth else AuthState.CHECKING
    private var requestedPage = initialPage
    private var started = false
    private var committed = false
    private var documentVersion = 0
    private var inspectedVersion: Int? = null
    private var visualRequestVersion: Int? = null
    private var drawableVersion: Int? = null
    private var loginClicked = false
    private var clearAuthHistory = false
    private var jsErrorReported = false
    private var resourceErrorReported = false
    private var homeReadyDay: Long? = null
    private val observeScript = context.assets.open("site-observer.js").bufferedReader().use { it.readText() }
    private val loginScript = context.assets.open("site-open-login.js").bufferedReader().use { it.readText() }
    private val pageDeadline = Runnable {
        if (pageState == PageState.Loading) {
            fail(if (visualRequestVersion == documentVersion) SiteFailure.RENDERING else SiteFailure.TIMEOUT)
        }
    }
    private val authDeadline = Runnable {
        when (auth) {
            AuthState.OPENING_FORM -> setAuth(AuthState.FORM_UNAVAILABLE)
            AuthState.COMPLETING -> {
                setAuth(AuthState.LINK_FAILED)
                if (pageState == PageState.Loading) fail(SiteFailure.TIMEOUT)
            }
            else -> Unit
        }
    }

    init {
        Log.i(TAG, "created")
        setWebContentsDebuggingEnabled(false)
        isSaveEnabled = false
        setBackgroundColor(0xff121117.toInt())
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@SiteWebView, false)
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (!jsErrorReported && consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    jsErrorReported = true
                    Log.w(TAG, "javascript_error")
                }
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (Site.firstParty(request.url.toString()) != null) return false
                Log.w(TAG, "external_navigation")
                if (auth == AuthState.COMPLETING || !request.hasGesture()) {
                    fail(SiteFailure.NAVIGATION)
                } else if (request.url.scheme == "https") {
                    openBrowser(context, request.url)
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                Log.i(TAG, "load_started")
                if (Site.firstParty(url) == null) {
                    view.stopLoading()
                    fail(SiteFailure.NAVIGATION)
                    return
                }
                beginLoad()
            }

            override fun onPageCommitVisible(view: WebView, url: String) = documentAvailable(url)

            override fun onPageFinished(view: WebView, url: String) {
                if (Site.firstParty(url) == null) return
                Log.i(TAG, "load_finished")
                persistSession()
                documentAvailable(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (clearAuthHistory) return
                val path = url?.let(Site::firstParty)?.path ?: return
                val destination = SitePage.fromPath(path.ifEmpty { "/" }) ?: return
                // The site redirects a guest profile request to its home page.
                if (requestedPage == SitePage.PROFILE && destination == SitePage.PLAY &&
                    auth != AuthState.SIGNED_IN
                ) return
                requestedPage = destination
                onPage(destination)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                Log.w(TAG, "navigation_error code=${error.errorCode}")
                fail(
                    when (error.errorCode) {
                        ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT, ERROR_IO -> SiteFailure.OFFLINE
                        else -> SiteFailure.NAVIGATION
                    }
                )
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (request.isForMainFrame) {
                    Log.w(TAG, "http_error status=${errorResponse.statusCode}")
                    fail(SiteFailure.HTTP)
                } else if (!resourceErrorReported) {
                    resourceErrorReported = true
                    Log.w(TAG, "resource_http_error status=${errorResponse.statusCode}")
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                Log.w(TAG, "tls_error")
                if (error.url == view.url) fail(SiteFailure.TLS)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "renderer_gone")
                fail(SiteFailure.WEBVIEW)
                onRendererGone(this@SiteWebView)
                return true
            }
        }
    }

    private fun setPage(state: PageState) {
        if (pageState != state) {
            pageState = state
            onState(state)
        }
    }

    private fun setAuth(state: AuthState) {
        if (auth == state) return
        auth = state
        if (state !in listOf(AuthState.OPENING_FORM, AuthState.COMPLETING)) removeCallbacks(authDeadline)
        Log.i(TAG, "auth_state=${state.name}")
        onAuth(state)
    }

    private fun beginLoad() {
        documentVersion++
        committed = false
        visualRequestVersion = null
        drawableVersion = null
        jsErrorReported = false
        resourceErrorReported = false
        homeReadyDay = null
        setPage(PageState.Loading)
        schedulePageDeadline()
    }

    private fun schedulePageDeadline() {
        removeCallbacks(pageDeadline)
        if (pageState == PageState.Loading && visibility == VISIBLE) postDelayed(pageDeadline, 30_000)
    }

    private fun fail(reason: SiteFailure) {
        removeCallbacks(pageDeadline)
        Log.w(TAG, "load_failed reason=${reason.name}")
        setPage(PageState.Failed(reason))
        if (auth == AuthState.COMPLETING) setAuth(AuthState.LINK_FAILED)
        if (auth == AuthState.OPENING_FORM) setAuth(AuthState.FORM_UNAVAILABLE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { startWhenLaidOut() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { startWhenLaidOut() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val drawn = drawableVersion ?: return
        drawableVersion = null
        val accelerated = canvas.isHardwareAccelerated
        post {
            val nextState = pageState.observeFrame(documentVersion, drawn)
            if (nextState != pageState && visibility == VISIBLE) {
                removeCallbacks(pageDeadline)
                setPage(nextState)
                Log.i(TAG, "frame_drawn width=$width height=$height hardware=$accelerated")
            }
        }
    }

    fun showSite(show: Boolean) {
        val target = if (show) VISIBLE else INVISIBLE
        if (visibility == target) return
        visibility = target
        if (pageState == PageState.Loading) {
            resetFrameRequest()
            schedulePageDeadline()
        }
        if (show) {
            inspectPage()
            postInvalidateOnAnimation()
        }
    }

    fun setCompatibleRendering(enabled: Boolean) {
        val layer = if (enabled) LAYER_TYPE_SOFTWARE else LAYER_TYPE_NONE
        if (layerType == layer) return
        setLayerType(layer, null)
        Log.i(TAG, "compatible_rendering=$enabled")
        if (!started || pageState is PageState.Failed) return
        resetFrameRequest()
        setPage(PageState.Loading)
        schedulePageDeadline()
        inspectPage()
    }

    private fun resetFrameRequest() {
        documentVersion++
        visualRequestVersion = null
        drawableVersion = null
    }

    private fun awaitFrame(version: Int) {
        if (pageState != PageState.Loading || !isShown || width <= 0 || height <= 0 ||
            visualRequestVersion == version
        ) return
        visualRequestVersion = version
        postVisualStateCallback(version.toLong(), object : VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (requestId != documentVersion.toLong() || pageState != PageState.Loading || !isShown) return
                drawableVersion = version
                postInvalidateOnAnimation()
            }
        })
    }

    private fun startWhenLaidOut() {
        if (started || width <= 0 || height <= 0 || !isAttachedToWindow) return
        started = true
        Log.i(TAG, "attached width=$width height=$height")
        open(requestedPage)
    }

    private fun documentAvailable(url: String) {
        if (pageState is PageState.Failed || Site.firstParty(url) == null) return
        if (this.url?.let(Site::firstParty) != Site.firstParty(url)) return
        committed = true
        inspectPage()
    }

    private fun loadAddress(address: String) {
        beginLoad()
        loadUrl(address)
    }

    fun open(page: SitePage) {
        requestedPage = page
        onPage(page)
        if (!started) return
        if (auth == AuthState.COMPLETING) setAuth(AuthState.LINK_FAILED)
        if (auth == AuthState.ENTER_EMAIL) setAuth(AuthState.CHECKING)
        Log.i(TAG, "open_section=${page.name}")
        loadAddress(page.url)
    }

    fun retry() = open(requestedPage)

    fun requestLogin() {
        if (auth == AuthState.SIGNED_IN || auth == AuthState.COMPLETING) return
        if (pageState is PageState.Failed) open(SitePage.PLAY)
        loginClicked = false
        setAuth(AuthState.OPENING_FORM)
        removeCallbacks(authDeadline)
        postDelayed(authDeadline, 30_000)
        inspectPage()
    }

    fun openMagicLink(value: String): Boolean {
        val link = Site.emailLink(value) ?: return false
        if (!started) return false
        requestedPage = SitePage.PLAY
        onPage(requestedPage)
        clearAuthHistory = true
        setAuth(AuthState.COMPLETING)
        removeCallbacks(authDeadline)
        postDelayed(authDeadline, 35_000)
        Log.i(TAG, "email_link_accepted")
        loadAddress(link.toASCIIString())
        return true
    }

    fun canNavigateBack(): Boolean = !clearAuthHistory && canGoBack()

    fun inspectPage() {
        val current = url?.let(Site::firstParty) ?: return
        val version = documentVersion
        if (!committed || pageState is PageState.Failed || inspectedVersion == version) return
        inspectedVersion = version
        evaluateJavascript(observeScript) { encoded ->
            if (inspectedVersion == version) inspectedVersion = null
            if (version != documentVersion || pageState is PageState.Failed ||
                url?.let(Site::firstParty) != current
            ) return@evaluateJavascript
            val snapshot = runCatching { JSONObject(encoded) }.getOrNull() ?: return@evaluateJavascript
            val marker = AccountMarker.entries.firstOrNull { it.name == snapshot.optString("account") }
                ?: AccountMarker.UNKNOWN
            val isHome = current.path in listOf("", "/") && current.rawQuery == null && current.rawFragment == null
            val wasCompleting = auth == AuthState.COMPLETING
            setAuth(auth.observe(marker))
            if (wasCompleting && auth == AuthState.SIGNED_IN) {
                persistSession()
                if (!isHome) {
                    open(SitePage.PLAY)
                    return@evaluateJavascript
                }
            }
            if (snapshot.optBoolean("content") && pageState == PageState.Loading) {
                if (isHome && homeReadyDay == null) homeReadyDay = ReminderPolicy.day(Instant.now())
                awaitFrame(version)
            }
            if (clearAuthHistory && auth != AuthState.COMPLETING && isHome && pageState == PageState.Ready) {
                clearHistory()
                clearAuthHistory = false
            }
            if (auth == AuthState.OPENING_FORM && marker == AccountMarker.SIGNED_OUT && !loginClicked) {
                loginClicked = true
                evaluateJavascript(loginScript) { result ->
                    if (version == documentVersion && result != "true") loginClicked = false
                }
            }
        }
    }

    fun readVisibleCountdown(onCountdown: (String) -> Unit) {
        val current = url?.let(Site::firstParty) ?: return
        if (pageState != PageState.Ready || clearAuthHistory || current.path !in listOf("", "/") ||
            current.rawQuery != null || current.rawFragment != null
        ) return
        // This samples only the public label and its visible countdown. It cannot act on the page.
        evaluateJavascript(
            """
            (() => {
                if (!['https://www.rngdle.com','https://rngdle.com'].includes(location.origin) ||
                    location.pathname !== '/' || location.search) return null;
                for (const label of document.querySelectorAll('span')) {
                    if (label.children.length || label.textContent.trim() !== 'Next roll in') continue;
                    const value = label.nextElementSibling;
                    const visible = (element) => element.getClientRects().length &&
                        getComputedStyle(element).visibility === 'visible';
                    if (value && visible(label) && visible(value)) {
                        return value.textContent.trim();
                    }
                }
                return null;
            })()
            """.trimIndent()
        ) { encoded ->
            if (pageState == PageState.Ready && url?.let(Site::firstParty) == current) {
                val day = ReminderPolicy.day(Instant.now())
                val countdown = runCatching { JSONTokener(encoded).nextValue() }.getOrNull() as? String
                if (countdown == null) homeReadyDay = day
                else if (homeReadyDay == day) onCountdown(countdown)
            }
        }
    }

    fun persistSession() = CookieManager.getInstance().flush()

    fun dispose() {
        started = false
        committed = false
        resetFrameRequest()
        removeCallbacks(pageDeadline)
        removeCallbacks(authDeadline)
        (parent as? android.view.ViewGroup)?.removeView(this)
        destroy()
    }

    companion object {
        private const val TAG = "RNGdleWeb"
        fun openBrowser(context: Context, uri: Uri): Boolean =
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
    }
}
