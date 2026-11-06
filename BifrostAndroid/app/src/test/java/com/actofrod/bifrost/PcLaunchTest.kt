package com.actofrod.bifrost

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class PcLaunchTest {
    @Test
    fun selectsDxWrapperByApiPriority() {
        assertEquals("vkd3d", dxwrapperFor(listOf("Direct3D 8", "Direct3D 12")))
        assertEquals("dxvk", dxwrapperFor(listOf("Direct3D 9", "Direct3D 8")))
        assertEquals("dxvk", dxwrapperFor(listOf("Direct3D 8")))
        assertEquals("cnc-ddraw", dxwrapperFor(listOf("Direct3D 7")))
        assertEquals("cnc-ddraw", dxwrapperFor(listOf("DirectDraw")))
        assertEquals("dxvk", dxwrapperFor(listOf("OpenGL")))
    }

    @Test
    fun appendsGameDriveAndRejectsDriveDelimiterInPath() {
        assertEquals(
            "D:/downloadsE:/storageG:/data/user/0/com.actofrod.bifrost/files/games/id/game",
            containerDrives(
                "D:/downloadsE:/storage",
                "/data/user/0/com.actofrod.bifrost/files/games/id/game",
            ),
        )
        try {
            containerDrives("D:/downloads", "/data/game:with-colon")
            throw AssertionError("Expected a colon-containing path to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun resolvesRelativeGameDirectoriesUnderGamesDirectory() {
        val filesDirectory = createTempDir(prefix = "bifrost-game-dir")
        try {
            val gameDirectory = File(filesDirectory, "games/test-id/game").apply { mkdirs() }
            assertEquals(gameDirectory.canonicalFile, resolveGameDirectory(filesDirectory, "test-id/game"))
            assertEquals(gameDirectory.canonicalFile, resolveGameDirectory(filesDirectory, gameDirectory.absolutePath))
        } finally {
            filesDirectory.deleteRecursively()
        }
    }

    @Test
    fun resolvesExternalStorageDocumentIdSchemesSafely() {
        val temp = Files.createTempDirectory("bifrost-external-storage").toFile()
        try {
            val externalRoot = File(temp, "emulated/0").apply { mkdirs() }
            val storageRoot = File(temp, "storage").apply { mkdirs() }
            val primaryGame = File(externalRoot, "Android/data/game").canonicalFile
            val homeGame = File(externalRoot, "Documents/Games/game").canonicalFile
            val removableGame = File(storageRoot, "0123-4567/Games/game").canonicalFile

            assertEquals(
                primaryGame,
                resolveDocumentPath("com.android.externalstorage.documents", "primary:Android/data/game", externalRoot, storageRoot),
            )
            assertEquals(
                homeGame,
                resolveDocumentPath("com.android.externalstorage.documents", "home:Games/game", externalRoot, storageRoot),
            )
            assertEquals(
                removableGame,
                resolveDocumentPath("com.android.externalstorage.documents", "0123-4567:Games/game", externalRoot, storageRoot),
            )
            assertEquals(
                null,
                resolveDocumentPath("com.android.externalstorage.documents", "primary:../../outside", externalRoot, storageRoot),
            )
            assertEquals(
                null,
                resolveDocumentPath("com.android.externalstorage.documents", "home:../../outside", externalRoot, storageRoot),
            )
            assertEquals(
                null,
                resolveDocumentPath("com.android.externalstorage.documents", "0123-4567:../../outside", externalRoot, storageRoot),
            )
            assertEquals(
                null,
                resolveDocumentPath("com.android.externalstorage.documents", "primary:Games\\game", externalRoot, storageRoot),
            )
            assertEquals(
                null,
                resolveDocumentPath("com.android.externalstorage.documents", "primary:/absolute/path", externalRoot, storageRoot),
            )
            assertEquals(
                null,
                resolveDocumentPath("com.unknown.documents", "primary:Games/game", externalRoot, storageRoot),
            )
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun resolvesDownloadsDocumentIdsOnlyInsideStorageRoots() {
        val temp = Files.createTempDirectory("bifrost-downloads").toFile()
        try {
            val externalRoot = File(temp, "emulated/0").apply { mkdirs() }
            val storageRoot = File(temp, "storage").apply { mkdirs() }
            val rawPath = File(externalRoot, "Download/Game.exe").canonicalFile
            val mediaPath = File(storageRoot, "0123-4567/Game.exe").canonicalFile
            val outsidePath = File(temp, "outside/Game.exe").canonicalPath
            var lookedUpId: String? = null

            assertEquals(
                rawPath,
                resolveDocumentPath(
                    "com.android.providers.downloads.documents",
                    "raw:${rawPath.path}",
                    externalRoot,
                    storageRoot,
                ),
            )
            assertEquals(
                mediaPath,
                resolveDocumentPath(
                    "com.android.providers.downloads.documents",
                    "msf:42",
                    externalRoot,
                    storageRoot,
                ) { id ->
                    lookedUpId = id
                    mediaPath.path
                },
            )
            assertEquals("42", lookedUpId)
            assertEquals(
                null,
                resolveDocumentPath(
                    "com.android.providers.downloads.documents",
                    "msf:43",
                    externalRoot,
                    storageRoot,
                ) { outsidePath },
            )
            assertEquals(
                null,
                resolveDocumentPath(
                    "com.android.providers.downloads.documents",
                    "raw:$outsidePath",
                    externalRoot,
                    storageRoot,
                ),
            )
            assertEquals(
                null,
                resolveDocumentPath("com.android.providers.downloads.documents", "raw:relative/path", externalRoot, storageRoot),
            )
            assertEquals(
                null,
                resolveDocumentPath("com.android.providers.downloads.documents", "1234", externalRoot, storageRoot),
            )
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun matchesPickedExecutablePathCaseInsensitivelyAndFallsBackToMain() {
        val executables = listOf("Game.exe", "bin/Launcher.exe")

        assertEquals(1, executableIndexForPath(executables, "BIN/launcher.EXE", fallbackIndex = 0))
        assertEquals(0, executableIndexForPath(executables, "other.exe", fallbackIndex = 0))
        assertEquals(1, executableIndexForPath(executables, null, fallbackIndex = 1))
    }
}
