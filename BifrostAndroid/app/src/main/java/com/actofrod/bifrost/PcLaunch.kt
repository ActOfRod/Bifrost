package com.actofrod.bifrost

import java.io.File
import java.util.Locale

private const val DXVK = "dxvk"
private const val VKD3D = "vkd3d"
private const val CNC_DDRAW = "cnc-ddraw"
private val direct3dVersion = Regex("direct3d\\s+(\\d+)", RegexOption.IGNORE_CASE)

internal fun dxwrapperFor(apis: List<String>): String {
    val versions = apis.mapNotNull { api ->
        direct3dVersion.find(api)?.groupValues?.get(1)?.toIntOrNull()
    }
    return when {
        12 in versions -> VKD3D
        versions.any { it in 9..11 } -> DXVK
        8 in versions -> DXVK
        apis.any { it.contains("DirectDraw", ignoreCase = true) } || versions.any { it <= 7 } -> CNC_DDRAW
        else -> DXVK
    }
}

internal fun containerDrives(defaultDrives: String, gameDir: String): String {
    require(':' !in gameDir) { "Game path cannot contain ':' in Winlator drive mappings" }
    return "${defaultDrives}G:$gameDir"
}

internal fun resolveGameDirectory(filesDirectory: File, gameDir: String): File {
    val path = File(gameDir)
    return (if (path.isAbsolute) path else File(File(filesDirectory, "games"), gameDir)).canonicalFile
}

private val executableRootDirectoryNames = setOf(
    "bin",
    "bin32",
    "bin64",
    "binaries",
    "win32",
    "win64",
    "x86",
    "x64",
    "x86_64",
    "system",
)

internal fun gameRootCandidates(executable: File, stopAt: List<File>): List<File> {
    val canonicalStopAt = stopAt.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }.toSet()
    var current = executable.parentFile ?: return listOf(executable)
    val candidates = mutableListOf(current)
    while (current.name.lowercase(Locale.ROOT) in executableRootDirectoryNames) {
        val parent = current.parentFile ?: break
        val canonicalParent = runCatching { parent.canonicalFile }.getOrNull() ?: break
        if (canonicalParent in canonicalStopAt) break
        current = parent
        candidates += current
    }
    return candidates.asReversed()
}

internal fun resolveDocumentPath(
    authority: String,
    documentId: String,
    externalStorageRoot: File,
    storageRoot: File = File("/storage"),
    mediaLookup: (String) -> String? = { null },
): File? =
    when (authority) {
        "com.android.externalstorage.documents" ->
            resolveExternalStorageDocument(documentId, externalStorageRoot, storageRoot)
        "com.android.providers.downloads.documents" ->
            resolveDownloadsDocument(documentId, externalStorageRoot, storageRoot, mediaLookup)
        else -> null
    }

private val storageVolumeId = Regex("[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}")

private fun resolveExternalStorageDocument(
    documentId: String,
    externalStorageRoot: File,
    storageRoot: File,
): File? {
    val separator = documentId.indexOf(':')
    if (separator <= 0) return null
    val volume = documentId.substring(0, separator)
    val relativePath = documentId.substring(separator + 1)
    if (relativePath.startsWith('/') || relativePath.contains('\\')) return null

    val root = when {
        volume.equals("primary", ignoreCase = true) -> externalStorageRoot
        volume.equals("home", ignoreCase = true) -> File(externalStorageRoot, "Documents")
        storageVolumeId.matches(volume) -> {
            val canonicalStorageRoot = runCatching { storageRoot.canonicalFile }.getOrNull() ?: return null
            val volumeRoot = runCatching { File(canonicalStorageRoot, volume).canonicalFile }.getOrNull() ?: return null
            if (!isWithinRoot(volumeRoot, canonicalStorageRoot)) return null
            volumeRoot
        }
        else -> return null
    }
    return resolveRelativePath(root, relativePath)
}

private fun resolveDownloadsDocument(
    documentId: String,
    externalStorageRoot: File,
    storageRoot: File,
    mediaLookup: (String) -> String?,
): File? {
    val path = when {
        documentId.startsWith("raw:") -> documentId.removePrefix("raw:")
        documentId.startsWith("msf:") -> {
            val id = documentId.removePrefix("msf:")
            if (id.isBlank()) return null
            runCatching { mediaLookup(id) }.getOrNull() ?: return null
        }
        else -> return null
    }
    return resolveAbsolutePath(path, listOf(externalStorageRoot, storageRoot))
}

private fun resolveRelativePath(root: File, relativePath: String): File? {
    if (relativePath.startsWith('/') || relativePath.contains('\\')) return null
    val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
    val candidate = runCatching { File(canonicalRoot, relativePath).canonicalFile }.getOrNull() ?: return null
    return candidate.takeIf { isWithinRoot(it, canonicalRoot) }
}

private fun resolveAbsolutePath(path: String, roots: List<File>): File? {
    if (path.contains('\\')) return null
    val candidateFile = File(path)
    if (!candidateFile.isAbsolute) return null
    val candidate = runCatching { candidateFile.canonicalFile }.getOrNull() ?: return null
    return candidate.takeIf { file ->
        roots.any { root ->
            val canonicalRoot = runCatching { root.canonicalFile }.getOrNull()
            canonicalRoot != null && isWithinRoot(file, canonicalRoot)
        }
    }
}

private fun isWithinRoot(path: File, root: File): Boolean =
    path.path == root.path || path.path.startsWith(root.path + File.separator)

internal fun executableIndexForPath(
    executablePaths: List<String>,
    pickedRelativePath: String?,
    fallbackIndex: Int,
): Int =
    executablePaths.indexOfFirst { it.equals(pickedRelativePath, ignoreCase = true) }
        .takeIf { it >= 0 } ?: fallbackIndex
