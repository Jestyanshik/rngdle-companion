package app.rngdle.companion.site

import java.net.URI

enum class SitePage(val path: String) {
    PLAY("/"),
    RANKINGS("/leaderboard"),
    FRIENDS("/friends"),
    PROFILE("/profile");

    val url: String get() = Site.origin + path

    companion object {
        fun fromPath(path: String): SitePage? = entries.firstOrNull {
            it.path == path || it != PLAY && path.startsWith(it.path + "/")
        }
    }
}

object Site {
    const val origin = "https://www.rngdle.com"

    fun firstParty(value: String): URI? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        return uri.takeIf {
            it.scheme.equals("https", ignoreCase = true) &&
                (host.equals("www.rngdle.com", true) || host.equals("rngdle.com", true)) &&
                it.rawUserInfo == null && it.port in listOf(-1, 443)
        }
    }

    fun emailLink(value: String): URI? = firstParty(value.trim())?.takeIf {
        it.rawPath !in listOf("", "/") || it.rawQuery != null || it.rawFragment != null
    }
}

sealed interface PageState {
    data object Loading : PageState
    data object Ready : PageState
    data class Failed(val reason: SiteFailure) : PageState
}

enum class SiteFailure { OFFLINE, HTTP, TLS, TIMEOUT, RENDERING, NAVIGATION, WEBVIEW }

internal fun PageState.observeFrame(document: Int, drawnDocument: Int?): PageState =
    if (this == PageState.Loading && drawnDocument == document) PageState.Ready else this

enum class AuthState { CHECKING, SIGNED_OUT, OPENING_FORM, ENTER_EMAIL, CHECK_MAIL, COMPLETING, SIGNED_IN, FORM_UNAVAILABLE, LINK_FAILED }

enum class AccountMarker { UNKNOWN, SIGNED_OUT, EMAIL_FORM, MAIL_SENT, SIGNED_IN }

internal fun SitePage.needsSignIn(auth: AuthState): Boolean = this == SitePage.PROFILE && when (auth) {
    AuthState.SIGNED_OUT, AuthState.CHECK_MAIL, AuthState.FORM_UNAVAILABLE, AuthState.LINK_FAILED -> true
    else -> false
}

internal fun AuthState.observe(marker: AccountMarker): AuthState = when {
    marker == AccountMarker.SIGNED_IN -> AuthState.SIGNED_IN
    this == AuthState.COMPLETING -> this
    marker == AccountMarker.MAIL_SENT -> AuthState.CHECK_MAIL
    marker == AccountMarker.EMAIL_FORM -> AuthState.ENTER_EMAIL
    marker == AccountMarker.SIGNED_OUT && this !in listOf(
        AuthState.OPENING_FORM, AuthState.CHECK_MAIL, AuthState.FORM_UNAVAILABLE, AuthState.LINK_FAILED,
    ) -> AuthState.SIGNED_OUT
    else -> this
}
