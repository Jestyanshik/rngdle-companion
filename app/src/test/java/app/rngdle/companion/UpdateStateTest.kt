package app.rngdle.companion

import app.rngdle.companion.update.Release
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateStateTest {
    private val release = Release("0.2.0", "Example notes", 2, 5, "00".repeat(32), URI("https://github.com/"))

    @Test fun checkCannotInterruptAnActiveCheckOrDownload() {
        assertTrue(UpdateState.Idle.canCheck)
        assertFalse(UpdateState.Checking.canCheck)
        assertFalse(UpdateState.Downloading(.5f).canCheck)
        assertTrue(UpdateState.Failed().canCheck)
    }

    @Test fun onlyAnOfferedReleaseOrAFailedDownloadCanBeDownloaded() {
        assertEquals(release, UpdateState.Available(release).releaseToDownload)
        assertEquals(release, UpdateState.Failed(release).releaseToDownload)
        assertNull(UpdateState.Failed().releaseToDownload)
        assertNull(UpdateState.Downloading(0f).releaseToDownload)
        assertNull(UpdateState.Current.releaseToDownload)
    }
}
