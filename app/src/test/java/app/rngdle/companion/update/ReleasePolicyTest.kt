package app.rngdle.companion.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasePolicyTest {
    @Test fun recognizesOnlyVersionedApkNames() {
        assertEquals(12L, ReleasePolicy.versionCode("rngdle-companion-vc12.apk"))
        assertNull(ReleasePolicy.versionCode("rngdle-companion-vc0.apk"))
        assertNull(ReleasePolicy.versionCode("rngdle-companion-vc999999999999999999999999.apk"))
        assertNull(ReleasePolicy.versionCode("rngdle-companion-vc12.apk.zip"))
        assertNull(ReleasePolicy.versionCode("app-debug.apk"))
    }

    @Test fun onlyStrictlyNewerVersionCodesCanBeInstalled() {
        assertTrue(ReleasePolicy.isUpdate(9, 10))
        assertFalse(ReleasePolicy.isUpdate(10, 10))
        assertFalse(ReleasePolicy.isUpdate(11, 10))
    }

    @Test fun onlyOlderVersionCodesNeedTheRollbackFlow() {
        assertTrue(ReleasePolicy.isRollback(10, 9))
        assertFalse(ReleasePolicy.isRollback(10, 10))
        assertFalse(ReleasePolicy.isRollback(10, 11))
    }

    @Test fun requiresFullSha256Metadata() {
        assertEquals("ab".repeat(32), ReleasePolicy.sha256("sha256:" + "AB".repeat(32)))
        assertNull(ReleasePolicy.sha256("sha256:12345"))
        assertNull(ReleasePolicy.sha256("md5:" + "0".repeat(64)))
    }
}
