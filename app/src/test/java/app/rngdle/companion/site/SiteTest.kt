package app.rngdle.companion.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SiteTest {
    @Test fun onlyADrawnFrameFromTheCurrentDocumentCanFinishLoading() {
        assertEquals(PageState.Loading, PageState.Loading.observeFrame(2, null))
        assertEquals(PageState.Loading, PageState.Loading.observeFrame(2, 1))
        assertEquals(PageState.Ready, PageState.Loading.observeFrame(2, 2))
        val failed = PageState.Failed(SiteFailure.HTTP)
        assertEquals(failed, failed.observeFrame(2, 2))
    }

    @Test fun onlyAGuestProfileCoversTheReadyWebViewWithASignInPanel() {
        assertEquals(true, SitePage.PROFILE.needsSignIn(AuthState.SIGNED_OUT))
        assertEquals(false, SitePage.PROFILE.needsSignIn(AuthState.ENTER_EMAIL))
        assertEquals(false, SitePage.PROFILE.needsSignIn(AuthState.SIGNED_IN))
        assertEquals(false, SitePage.RANKINGS.needsSignIn(AuthState.SIGNED_OUT))
    }

    @Test fun pastedLinkRetainsTheOriginalEncodingAndRejectsExternalLinks() {
        val original = "https://www.rngdle.com/example?value=a%2Bb%26c#fragment"
        assertEquals(original, Site.emailLink(" $original\n")?.toASCIIString())
        assertNull(Site.emailLink("https://www.rngdle.com/"))
        assertNull(Site.emailLink("https://mail.example.test/?url=$original"))
        assertNull(Site.emailLink("https://rngdle.com.example.test/example"))
    }

    @Test fun callbackIsOnlyConfirmedByTheSitesSignedInMarker() {
        assertEquals(AuthState.COMPLETING, AuthState.COMPLETING.observe(AccountMarker.SIGNED_OUT))
        assertEquals(AuthState.COMPLETING, AuthState.COMPLETING.observe(AccountMarker.UNKNOWN))
        assertEquals(AuthState.SIGNED_IN, AuthState.COMPLETING.observe(AccountMarker.SIGNED_IN))
    }

    @Test fun emailStatusFollowsTheOriginalFormAndSurvivesClosingIt() {
        assertEquals(AuthState.ENTER_EMAIL, AuthState.OPENING_FORM.observe(AccountMarker.EMAIL_FORM))
        assertEquals(AuthState.CHECK_MAIL, AuthState.ENTER_EMAIL.observe(AccountMarker.MAIL_SENT))
        assertEquals(AuthState.CHECK_MAIL, AuthState.CHECK_MAIL.observe(AccountMarker.SIGNED_OUT))
        assertEquals(AuthState.LINK_FAILED, AuthState.LINK_FAILED.observe(AccountMarker.SIGNED_OUT))
        assertEquals(AuthState.SIGNED_OUT, AuthState.SIGNED_IN.observe(AccountMarker.SIGNED_OUT))
    }

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
