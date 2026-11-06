package com.actofrod.bifrost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun parsesLatestReleaseAndMatchingApkAsset() {
        val release = parseRelease(
            """{"tag_name":"v0.2.10","html_url":"https://github.com/ActOfRod/Bifrost/releases/tag/v0.2.10","assets":[{"name":"checksums.txt","browser_download_url":"https://example/checksums","size":12},{"name":"Bifrost-Android-v0.2.10.apk","browser_download_url":"https://example/bifrost.apk","size":1234}]}""",
        )

        assertNotNull(release)
        assertEquals("v0.2.10", release!!.tagName)
        assertEquals("https://github.com/ActOfRod/Bifrost/releases/tag/v0.2.10", release.htmlUrl)
        assertEquals("Bifrost-Android-v0.2.10.apk", release.assetName)
        assertEquals("https://example/bifrost.apk", release.downloadUrl)
        assertEquals(1234L, release.size)
    }

    @Test
    fun rejectsReleaseWithoutMatchingCompleteApkAsset() {
        assertNull(
            parseRelease(
                """{"tag_name":"v1.0","html_url":"https://example/release","assets":[{"name":"Bifrost-Android-v1.zip","browser_download_url":"https://example/file","size":42}]}""",
            ),
        )
        assertNull(parseRelease("""{"tag_name":"v1.0","assets":[]}"""))
    }

    @Test
    fun comparesNumericVersionsAndIgnoresSuffixes() {
        assertTrue(isNewer("v0.2.10", "0.2.9"))
        assertTrue(isNewer("0.3.0-beta.1", "0.2.99"))
        assertFalse(isNewer("v0.2.9", "0.2.10"))
        assertFalse(isNewer("0.2.10-rc1", "0.2.10"))
        assertFalse(isNewer("not-a-version", "0.2.9"))
    }
}
