package app.rngdle.companion.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SiteTest {
    @Test fun onlyFirstPartyHttpsUrlsCanEnterTheAccountWebView() {
        assertNotNull(Site.firstParty("https://www.rngdle.com/"))
        assertNotNull(Site.firstParty("https://rngdle.com:443/path"))
        listOf(
            "https://rngdle.com.evil.test/",
            "https://www.rngdle.com@evil.test/",
            "https://evil.test@www.rngdle.com/",
            "https://www.rngdle.com:444/",
            "http://www.rngdle.com/",
            "javascript:alert(1)",
            "file:///tmp/roll",
        ).forEach { assertNull(it, Site.firstParty(it)) }
    }

    @Test fun navigationRetainsItsSectionForNestedRoutesAndIgnoresUnknownPages() {
        assertEquals(SitePage.PLAY, SitePage.fromPath("/"))
        assertEquals(SitePage.FRIENDS, SitePage.fromPath("/friends/requests"))
        assertNull(SitePage.fromPath("/friends-elsewhere"))
        assertNull(SitePage.fromPath("/settings"))
    }
}
