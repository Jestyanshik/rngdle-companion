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
}

sealed interface PageState {
    data object Loading : PageState
    data object Ready : PageState
    data object Offline : PageState
    data object Failed : PageState
}
