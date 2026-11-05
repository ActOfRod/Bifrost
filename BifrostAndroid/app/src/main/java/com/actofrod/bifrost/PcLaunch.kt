package com.actofrod.bifrost

import java.io.File

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

internal fun resolvePrimaryStoragePath(documentId: String, externalStorageRoot: File): File? {
    if (!documentId.startsWith("primary:")) return null
    val relativePath = documentId.removePrefix("primary:")
    if (relativePath.startsWith('/') || relativePath.contains('\\')) return null
    val root = externalStorageRoot.canonicalFile
    val candidate = File(root, relativePath).canonicalFile
    return candidate.takeIf {
        it == root || it.path.startsWith(root.path + File.separator)
    }
}
