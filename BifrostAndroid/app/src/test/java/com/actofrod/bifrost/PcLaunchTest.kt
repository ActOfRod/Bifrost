package com.actofrod.bifrost

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Test

class PcLaunchTest {
    @Test
    fun detectsGodotFromSparseExecutableTrailer() {
        withTempRoot("bifrost-godot-tail") { root ->
            val executable = File(root, "GodotGame.exe")
            val expectedLength = 3L * 1024 * 1024 * 1024
            var sparseFileSupported = false
            RandomAccessFile(executable, "rw").use { file ->
                try {
                    file.setLength(expectedLength)
                    file.seek(expectedLength - 4L)
                    file.write("GDPC".toByteArray(Charsets.US_ASCII))
                    sparseFileSupported = file.length() == expectedLength
                } catch (_: IOException) {
                    sparseFileSupported = false
                }
            }
            Assume.assumeTrue("Sparse 3 GiB files are unavailable", sparseFileSupported)

            assertEquals(GameEngine.GODOT, detectEngine(root, executable.name))
        }
    }

    @Test
    fun detectsGodotFromSiblingPackAndDotNetDataDirectory() {
        withTempRoot("bifrost-godot-markers") { root ->
            val packedExe = writeExecutable(root, "Packed/PackedGame.exe")
            File(packedExe.parentFile, "PACKEDGAME.PCK").writeBytes(byteArrayOf())
            assertEquals(GameEngine.GODOT, detectEngine(root, "Packed/PackedGame.exe"))

            val dotnetExe = writeExecutable(root, "DotNet/DotNetGame.exe")
            File(dotnetExe.parentFile, "data_game_WINDOWS_x86_64").mkdirs()
            assertEquals(GameEngine.GODOT, detectEngine(root, "DotNet/DotNetGame.exe"))
        }
    }

    @Test
    fun detectsUnityFromPlayerDllOrDataDirectory() {
        withTempRoot("bifrost-unity-markers") { root ->
            val playerExe = writeExecutable(root, "Player/PlayerGame.exe")
            File(playerExe.parentFile, "unityplayer.DLL").writeBytes(byteArrayOf())
            assertEquals(GameEngine.UNITY, detectEngine(root, "Player/PlayerGame.exe"))

            val dataExe = writeExecutable(root, "Data/DataGame.exe")
            File(dataExe.parentFile, "DATAGAME_data").mkdirs()
            assertEquals(GameEngine.UNITY, detectEngine(root, "Data/DataGame.exe"))
        }
    }

    @Test
    fun detectsUnrealFromShippingNameOrEngineBinariesDirectory() {
        withTempRoot("bifrost-unreal-markers") { root ->
            val shippingExe = writeExecutable(root, "Game-Win64-Shipping.EXE")
            assertEquals(GameEngine.UNREAL, detectEngine(root, shippingExe.name))

            writeExecutable(root, "UnrealGame.exe")
            File(root, "Engine/Binaries").mkdirs()
            assertEquals(GameEngine.UNREAL, detectEngine(root, "UnrealGame.exe"))
        }
    }

    @Test
    fun unknownEngineHasNoMarkers() {
        withTempRoot("bifrost-unknown-engine") { root ->
            writeExecutable(root, "Unknown.exe")

            assertEquals(GameEngine.UNKNOWN, detectEngine(root, "Unknown.exe"))
        }
    }

    @Test
    fun selectsGraphicsDriverFromPreferenceAndAdrenoModel() {
        assertEquals("turnip,gladio", graphicsDriverFor(GpuDriver.AUTO, 830))
        assertEquals("vortek,gladio", graphicsDriverFor(GpuDriver.AUTO, 0))
        assertEquals("turnip,gladio", graphicsDriverFor(GpuDriver.TURNIP, 0))
        assertEquals("vortek,gladio", graphicsDriverFor(GpuDriver.VORTEK, 830))
    }

    @Test
    fun selectsDinputMapperByControllerApiAndExecutableImports() {
        assertEquals(1, dinputMapperType(ControllerApi.XINPUT, setOf("dinput8.dll")))
        assertEquals(0, dinputMapperType(ControllerApi.DIRECTINPUT, setOf("xinput1_3.dll")))
        assertEquals(0, dinputMapperType(ControllerApi.AUTO, setOf("DINPUT.DLL")))
        assertEquals(0, dinputMapperType(ControllerApi.AUTO, setOf("dinput8.dll", "user32.dll")))
        assertEquals(1, dinputMapperType(ControllerApi.AUTO, setOf("dinput8.dll", "XInput1_4.dll")))
        assertEquals(1, dinputMapperType(ControllerApi.AUTO, setOf("xinput1_3.dll")))
        assertEquals(1, dinputMapperType(ControllerApi.AUTO, emptySet()))
    }

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

    @Test
    fun returnsOrderedGameRootCandidatesForCommonExecutableDirectories() {
        val temp = Files.createTempDirectory("bifrost-game-root-candidates").toFile().canonicalFile
        fun executable(relativePath: String): File {
            val file = File(temp, relativePath)
            file.parentFile?.mkdirs()
            file.createNewFile()
            return file
        }

        try {
            val gameRoot = File(temp, "Game").canonicalFile
            assertEquals(
                listOf(
                    gameRoot,
                    File(gameRoot, "Binaries").canonicalFile,
                    File(gameRoot, "Binaries/Win64").canonicalFile,
                ),
                gameRootCandidates(executable("Game/Binaries/Win64/Game.exe"), listOf(temp)),
            )
            assertEquals(
                listOf(gameRoot, File(gameRoot, "System").canonicalFile),
                gameRootCandidates(executable("Game/System/Game.exe"), listOf(temp)),
            )
            assertEquals(
                listOf(gameRoot),
                gameRootCandidates(executable("Game/Game.exe"), listOf(temp)),
            )

            val stopAtRoot = File(temp, "StopAt").apply { mkdirs() }
            val binExecutable = executable("StopAt/Bin/Game.exe")
            assertEquals(
                listOf(File(stopAtRoot, "Bin").canonicalFile),
                gameRootCandidates(binExecutable, listOf(stopAtRoot)),
            )
            assertEquals(
                listOf(
                    gameRoot,
                    File(gameRoot, "bin").canonicalFile,
                    File(gameRoot, "bin/x64").canonicalFile,
                ),
                gameRootCandidates(executable("Game/bin/x64/Game.exe"), listOf(temp)),
            )
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun withTempRoot(prefix: String, block: (File) -> Unit) {
        val root = Files.createTempDirectory(prefix).toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeExecutable(root: File, path: String): File =
        File(root, path).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0, 0, 0, 0))
        }
}
