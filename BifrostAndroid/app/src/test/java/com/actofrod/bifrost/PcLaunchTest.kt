package com.actofrod.bifrost

import java.io.File
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
}
