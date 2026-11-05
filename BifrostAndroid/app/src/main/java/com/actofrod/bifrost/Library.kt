package com.actofrod.bifrost

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val DEFAULT_MIN_FREE_BYTES = 512L * 1024 * 1024
internal const val STORAGE_CHECK_INTERVAL_BYTES = 64L * 1024 * 1024

@Serializable
enum class Kind { NATIVE, PC }

@Serializable
enum class ControllerMode { OVERLAY, SPLIT }

@Serializable
data class LauncherSystem(
    val id: String,
    val name: String,
    val icon: String? = null,
    val accent: Long? = null,
)

@Serializable
data class LibraryEntry(
    val id: String,
    val kind: Kind,
    val title: String,
    val packageName: String? = null,
    val gameDir: String? = null,
    val exe: String? = null,
    val arch: String? = null,
    val apis: List<String> = emptyList(),
    val cover: String? = null,
    val addedAt: Long,
    val lastPlayed: Long? = null,
    val containerId: Int? = null,
    val controlsProfileId: Int? = null,
    val systemId: String? = null,
    val playCount: Int = 0,
    val controllerMode: ControllerMode = ControllerMode.OVERLAY,
)

@Serializable
data class LibrarySnapshot(
    val entries: List<LibraryEntry> = emptyList(),
    val dismissed: Set<String> = emptySet(),
    val systems: List<LauncherSystem> = emptyList(),
)

internal fun LibrarySnapshot.currentEntry(entry: LibraryEntry): LibraryEntry =
    entries.firstOrNull { it.id == entry.id } ?: entry

internal fun LibrarySnapshot.deleteSystem(systemId: String): LibrarySnapshot =
    copy(
        systems = systems.filterNot { it.id == systemId },
        entries = entries.map { if (it.systemId == systemId) it.copy(systemId = null) else it },
    )

internal fun LibrarySnapshot.recordPlay(entryId: String, timestamp: Long): LibrarySnapshot =
    copy(entries = entries.map {
        if (it.id == entryId) it.copy(lastPlayed = timestamp, playCount = it.playCount + 1) else it
    })

internal fun topPlayedGames(entries: List<LibraryEntry>): List<LibraryEntry> =
    entries.asSequence()
        .filter { it.playCount > 0 }
        .sortedWith(
            compareByDescending<LibraryEntry> { it.playCount }
                .thenByDescending { it.lastPlayed ?: 0L }
                .thenBy { it.title.lowercase() },
        )
        .take(3)
        .toList()

internal data class InstalledApp(
    val packageName: String,
    val label: String,
    val isGame: Boolean,
    val isBifrostPort: Boolean,
    val cover: String? = null,
)

internal fun defaultGamesOnly(apps: List<InstalledApp>): Boolean = apps.any { it.isGame }

internal fun filterInstalledApps(
    apps: List<InstalledApp>,
    gamesOnly: Boolean,
    query: String,
    linkedPackages: Set<String>,
): List<InstalledApp> {
    val normalizedQuery = query.trim().lowercase()
    return apps.filter { app ->
        (!gamesOnly || app.isGame) &&
            app.packageName !in linkedPackages &&
            (normalizedQuery.isEmpty() ||
                app.label.lowercase().contains(normalizedQuery) ||
                app.packageName.lowercase().contains(normalizedQuery))
    }
}

internal data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal data class SplitControllerRects(val game: PixelRect, val controls: PixelRect)

internal fun splitControllerRects(
    width: Int,
    height: Int,
    foldBounds: PixelRect? = null,
    gameRatio: Float = 0.55f,
): SplitControllerRects {
    val safeWidth = width.coerceAtLeast(1)
    val safeHeight = height.coerceAtLeast(2)
    val horizontalFold = foldBounds?.takeIf {
        it.left >= 0 && it.right <= safeWidth && it.top >= 0 && it.bottom <= safeHeight &&
            it.width >= safeWidth / 2 && it.height > 0 && it.height <= safeHeight / 5 &&
            it.top > 0 && it.bottom < safeHeight
    }
    val gameBottom = horizontalFold?.top
        ?: (safeHeight * gameRatio.coerceIn(0.1f, 0.9f)).toInt().coerceIn(1, safeHeight - 1)
    val controlsTop = horizontalFold?.bottom ?: gameBottom
    return SplitControllerRects(
        game = PixelRect(0, 0, safeWidth, gameBottom),
        controls = PixelRect(0, controlsTop, safeWidth, safeHeight),
    )
}

@Serializable
data class BifrostManifest(
    val format: Int,
    val title: String,
    val exe: String,
    val arch: String,
    val apis: List<String> = emptyList(),
    val created_by: String = "",
)

data class ImportedPackage(
    val manifest: BifrostManifest,
    val gameDir: String,
    val cover: File?,
)

class LibraryStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun read(): LibrarySnapshot {
        if (!file.isFile) return LibrarySnapshot()
        return json.decodeFromString(file.readText())
    }

    @Synchronized
    fun write(snapshot: LibrarySnapshot) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        try {
            java.nio.file.Files.move(
                temp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal fun parseManifest(value: String): BifrostManifest {
    val manifest = Json { ignoreUnknownKeys = true }.decodeFromString<BifrostManifest>(value)
    require(manifest.format == 1) { "Unsupported .bifrost format ${manifest.format}" }
    require(manifest.title.isNotBlank()) { "Package title is missing" }
    require(manifest.arch == "x86" || manifest.arch == "x64") { "Package architecture is invalid" }
    require(isSafeRelativePath(manifest.exe)) { "Package executable path is invalid" }
    return manifest
}

internal fun isSafeRelativePath(name: String): Boolean =
    name.isNotBlank() &&
        !name.startsWith("/") &&
        !name.contains('\\') &&
        !name.split('/').any { it == ".." || it == "." || it.isEmpty() } &&
        !name.substringBefore('/').contains(':')

internal fun requireFreeStorage(availableBytes: Long, minFreeBytes: Long) {
    if (availableBytes < minFreeBytes) throw IOException("Not enough free storage to import this game")
}

private fun validateEntry(name: String, root: File): File {
    val normalized = name.removeSuffix("/")
    if (!isSafeRelativePath(normalized)) throw IOException("Unsafe archive path: $name")
    val target = File(root, normalized)
    val canonicalRoot = root.canonicalFile
    val canonicalTarget = target.canonicalFile
    if (canonicalTarget != canonicalRoot && !canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) {
        throw IOException("Archive path escapes its destination: $name")
    }
    return target
}

fun extractBifrostPackage(
    archive: InputStream,
    gamesDirectory: File,
    id: String = UUID.randomUUID().toString(),
    minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
    freeSpace: () -> Long = { gamesDirectory.usableSpace },
    progress: (Long) -> Unit = {},
): ImportedPackage {
    require(id.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid game id" }
    gamesDirectory.mkdirs()
    val destination = File(gamesDirectory, id)
    val partial = File(gamesDirectory, "$id.partial")
    if (destination.exists() || partial.exists()) throw IOException("Game import destination already exists")
    partial.mkdirs()
    var coverFile: File? = null
    try {
        var manifest: BifrostManifest? = null
        val names = mutableSetOf<String>()
        var copied = 0L
        var bytesSinceSpaceCheck = 0L
        fun checkFreeSpace() {
            requireFreeStorage(freeSpace(), minFreeBytes)
            bytesSinceSpaceCheck = 0L
        }
        fun accountCopied(count: Int) {
            copied += count
            bytesSinceSpaceCheck += count
            if (bytesSinceSpaceCheck >= STORAGE_CHECK_INTERVAL_BYTES) checkFreeSpace()
            progress(copied)
        }
        ZipInputStream(archive.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                checkFreeSpace()
                val name = entry.name
                val target = validateEntry(name, partial)
                if (!names.add(name)) throw IOException("Duplicate archive entry: $name")
                when {
                    name == "bifrost.json" && !entry.isDirectory -> {
                        val content = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            if (content.size() + count > 1024 * 1024) throw IOException("Package manifest is too large")
                            content.write(buffer, 0, count)
                        }
                        manifest = parseManifest(content.toString(Charsets.UTF_8.name()))
                    }
                    name == "cover.png" || name == "cover.jpg" -> {
                        val coverDir = File(gamesDirectory.parentFile, "covers")
                        coverDir.mkdirs()
                        val ext = name.substringAfterLast('.')
                        val stored = File(coverDir, "$id.$ext")
                        coverFile = stored
                        FileOutputStream(stored).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                accountCopied(count)
                            }
                        }
                    }
                    name == "game" && entry.isDirectory -> target.mkdirs()
                    name.startsWith("game/") -> {
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    accountCopied(count)
                                }
                            }
                        }
                    }
                    else -> throw IOException("Unexpected package entry: $name")
                }
                zip.closeEntry()
            }
        }
        checkFreeSpace()
        val validManifest = manifest ?: throw IOException("Package is missing bifrost.json")
        val gameExe = File(partial, "game/${validManifest.exe}")
        if (!gameExe.isFile) throw IOException("Package executable game/${validManifest.exe} is missing")
        if (!partial.renameTo(destination)) throw IOException("Could not finalize imported game")
        return ImportedPackage(validManifest, "$id/game", coverFile)
    } catch (error: Throwable) {
        partial.deleteRecursively()
        coverFile?.delete()
        throw error
    }
}

fun extractBifrostPackage(
    archive: File,
    gamesDirectory: File,
    id: String = UUID.randomUUID().toString(),
    minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
    freeSpace: () -> Long = { gamesDirectory.usableSpace },
    progress: (Long) -> Unit = {},
): ImportedPackage = archive.inputStream().use { input ->
    extractBifrostPackage(input, gamesDirectory, id, minFreeBytes, freeSpace, progress)
}
