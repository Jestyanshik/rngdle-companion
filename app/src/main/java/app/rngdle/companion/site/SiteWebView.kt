package app.rngdle.companion.site

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
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
import org.json.JSONTokener

class SiteWebView(
    context: Context,
    private val onState: (PageState) -> Unit,
    private val onPage: (SitePage) -> Unit,
) : WebView(context) {
    private var failure = false
    private var magicLinkInFlight = false
    private var safeRetryUrl = SitePage.PLAY.url
    private var homeReadyDay: Long? = null

    init {
        setWebContentsDebuggingEnabled(false)
        setBackgroundColor(0xff121117.toInt())
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
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
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (Site.firstParty(request.url.toString()) != null) return false
                if (request.hasGesture() && request.url.scheme == "https") {
                    openBrowser(context, request.url)
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                failure = false
                if (Site.firstParty(url) == null) {
                    view.stopLoading()
                    fail(PageState.Failed)
                    return
                }
                onState(PageState.Loading)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url != view.url || Site.firstParty(url) == null) return
                if (magicLinkInFlight) {
                    magicLinkInFlight = false
                    view.clearHistory()
                }
                persistSession()
                if (!failure) {
                    homeReadyDay = if (Site.firstParty(url)?.path in listOf("", "/")) {
                        ReminderPolicy.day(Instant.now())
                    } else null
                    onState(PageState.Ready)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                val path = url?.let(Site::firstParty)?.path ?: return
                SitePage.fromPath(path.ifEmpty { "/" })?.let(onPage)
                if (!magicLinkInFlight && !failure && url == Site.origin + path &&
                    SitePage.fromPath(path) != null
                ) safeRetryUrl = url
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                fail(
                    when (error.errorCode) {
                        ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT, ERROR_IO -> PageState.Offline
                        else -> PageState.Failed
                    }
                )
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (request.isForMainFrame && errorResponse.statusCode in listOf(403, 429, 500, 502, 503, 504)) {
                    fail(PageState.Failed)
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                if (error.url == view.url) fail(PageState.Failed)
            }
        }
    }

    private fun fail(state: PageState) {
        failure = true
        onState(state)
    }

    fun open(page: SitePage) {
        safeRetryUrl = page.url
        loadUrl(page.url)
    }

    fun retry() = loadUrl(safeRetryUrl)

    fun openMagicLink(value: String): Boolean {
        val link = Site.firstParty(value.trim()) ?: return false
        magicLinkInFlight = true
        safeRetryUrl = SitePage.PLAY.url
        loadUrl(link.toASCIIString())
        return true
    }

    fun readVisibleCountdown(onCountdown: (String) -> Unit) {
        val current = url?.let(Site::firstParty) ?: return
        if (failure || magicLinkInFlight || current.path !in listOf("", "/") || current.rawQuery != null) return
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
            if (!failure && url?.let(Site::firstParty) == current) {
                val day = ReminderPolicy.day(Instant.now())
                val countdown = runCatching { JSONTokener(encoded).nextValue() }.getOrNull() as? String
                if (countdown == null) homeReadyDay = day
                else if (homeReadyDay == day) onCountdown(countdown)
            }
        }
    }

    fun persistSession() = CookieManager.getInstance().flush()

    companion object {
        fun openBrowser(context: Context, uri: Uri): Boolean =
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
    }
}
