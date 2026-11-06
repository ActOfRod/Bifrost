package com.actofrod.bifrost

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class ReleaseInfo(
    val tagName: String,
    val htmlUrl: String,
    val assetName: String,
    val downloadUrl: String,
    val size: Long,
)

internal class UpdateRequestException(val statusCode: Int) : IOException("GitHub returned HTTP $statusCode")

internal sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    data class Latest(val version: String) : UpdateStatus()
    data class Available(val release: ReleaseInfo) : UpdateStatus()
    data class Downloading(val release: ReleaseInfo, val progress: Int) : UpdateStatus()
    data class Error(val message: String) : UpdateStatus()
}

private val apkAssetName = Regex("^Bifrost-Android-.*\\.apk$")
private val numericVersionPrefix = Regex("^v?(\\d+(?:\\.\\d+)*)", RegexOption.IGNORE_CASE)

internal fun parseRelease(json: String): ReleaseInfo? = runCatching {
    val release = Json.parseToJsonElement(json).jsonObject
    val tagName = release["tag_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val htmlUrl = release["html_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val assets = release["assets"]?.jsonArray ?: return null
    val asset = assets.firstOrNull { item ->
        val name = item.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        apkAssetName.matches(name)
    }?.jsonObject ?: return null
    val assetName = asset["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val downloadUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val size = asset["size"]?.jsonPrimitive?.longOrNull ?: return null
    ReleaseInfo(tagName, htmlUrl, assetName, downloadUrl, size)
}.getOrNull()

internal fun isNewer(remoteTag: String, currentVersionName: String): Boolean {
    fun components(value: String): List<Long>? {
        val match = numericVersionPrefix.find(value) ?: return null
        return match.groupValues[1].split('.').map { it.toLongOrNull() ?: return null }
    }
    val remote = components(remoteTag) ?: return false
    val current = components(currentVersionName) ?: return false
    for (index in 0 until maxOf(remote.size, current.size)) {
        val remotePart = remote.getOrElse(index) { 0L }
        val currentPart = current.getOrElse(index) { 0L }
        if (remotePart != currentPart) return remotePart > currentPart
    }
    return false
}

internal class UpdateChecker(private val repo: String) {
    suspend fun latestRelease(): ReleaseInfo = withContext(Dispatchers.IO) {
        val connection = openConnection(URL("https://api.github.com/repos/$repo/releases/latest"))
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw UpdateRequestException(status)
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseRelease(response) ?: throw IOException("The latest release does not contain a Bifrost Android APK")
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(
        release: ReleaseInfo,
        cacheDirectory: File,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val directory = File(cacheDirectory, "updates")
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Could not create update cache")
        directory.listFiles()?.forEach { it.deleteRecursively() }
        val partial = File(directory, "update.apk.part")
        val target = File(directory, "update.apk")
        val connection = URL(release.downloadUrl).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "Bifrost-Android")
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        var lastReportedPercent = -1
        fun reportProgress(percent: Int) {
            val normalized = percent.coerceIn(0, 100)
            if (normalized != lastReportedPercent) {
                lastReportedPercent = normalized
                onProgress(normalized)
            }
        }
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw UpdateRequestException(status)
            val totalBytes = release.size.takeIf { it > 0 } ?: connection.contentLengthLong
            var downloaded = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (totalBytes > 0) reportProgress((downloaded * 100 / totalBytes).toInt())
                    }
                    output.fd.sync()
                }
            }
            if (!partial.renameTo(target)) throw IOException("Could not finish saving the update")
            reportProgress(100)
            target
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: URL): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Bifrost-Android")
            connectTimeout = 15000
            readTimeout = 15000
        }
}
