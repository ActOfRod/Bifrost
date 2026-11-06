package com.actofrod.bifrost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.runBlocking

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

    @Test
    fun unparseableInstalledVersionIsOlderThanAnyParseableRelease() {
        assertTrue(isNewer("v0.2.2", "dev-abc1234"))
        assertFalse(isNewer("dev-abc1234", "dev-abc1234"))
    }

    @Test
    fun incompleteDownloadDeletesPartialFileAndDoesNotRename() = runBlocking {
        val root = Files.createTempDirectory("bifrost-update-download").toFile()
        try {
            val cases = listOf(5L to 3L, 0L to 5L)
            cases.forEachIndexed { index, (releaseSize, contentLength) ->
                val cacheDirectory = File(root, "case-$index")
                val connection = StubHttpURLConnection(
                    URL("https://example.invalid/update.apk"),
                    byteArrayOf(1, 2, 3),
                    contentLength,
                )
                val checker = UpdateChecker("ActOfRod/Bifrost") { connection }
                val release = ReleaseInfo(
                    "v0.2.2",
                    "https://example.invalid/release",
                    "Bifrost-Android-v0.2.2.apk",
                    "https://example.invalid/update.apk",
                    releaseSize,
                )

                val error = try {
                    checker.download(release, cacheDirectory) {}
                    null
                } catch (exception: IOException) {
                    exception
                }

                assertEquals("The update download was incomplete. Try again.", error?.message)
                assertFalse(File(cacheDirectory, "updates/update.apk.part").exists())
                assertFalse(File(cacheDirectory, "updates/update.apk").exists())
            }
        } finally {
            root.deleteRecursively()
        }
    }
}

private class StubHttpURLConnection(
    url: URL,
    private val contents: ByteArray,
    private val contentLength: Long,
) : HttpURLConnection(url) {
    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun usingProxy(): Boolean = false
    override fun getResponseCode(): Int = HTTP_OK
    override fun getInputStream() = ByteArrayInputStream(contents)
    override fun getContentLengthLong(): Long = contentLength
}
