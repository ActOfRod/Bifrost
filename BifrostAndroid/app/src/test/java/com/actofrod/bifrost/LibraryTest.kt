package com.actofrod.bifrost

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    @Test
    fun manifestParsingValidatesFormatAndExecutable() {
        val valid = """{"format":1,"title":"Game","exe":"bin/Game.exe","arch":"x64","apis":["Direct3D 11"]}"""
        assertEquals("bin/Game.exe", parseManifest(valid).exe)
        assertFails { parseManifest(valid.replace("\"format\":1", "\"format\":2")) }
        assertFails { parseManifest(valid.replace("\"exe\":\"bin/Game.exe\",", "")) }
    }

    @Test
    fun rejectsZipSlipPaths() {
        listOf("../x", "/abs", "a/../../x", """a\b""").forEach { path ->
            assertFalse("$path should be rejected", isSafeRelativePath(path))
        }
    }

    @Test
    fun extractsPackageAndWritesLibraryAtomically() {
        val temp = createTempDir(prefix = "bifrost-library-test")
        try {
            val archive = File(temp, "game.bifrost")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("bifrost.json"))
                zip.write("""{"format":1,"title":"Game","exe":"Game.exe","arch":"x86","apis":["Direct3D 8"]}""".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("game/"))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("game/bin/"))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("game/Game.exe"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
            val imported = extractBifrostPackage(archive.inputStream(), File(temp, "files/games"), "test-id")
            assertTrue(File(temp, "files/games/test-id/game/Game.exe").isFile)
            assertEquals("test-id/game", imported.gameDir)

            val store = LibraryStore(File(temp, "files/library.json"))
            val entry = LibraryEntry("test-id", Kind.PC, "Game", gameDir = imported.gameDir, exe = "Game.exe", arch = "x86", addedAt = 1)
            store.write(LibrarySnapshot(listOf(entry), setOf("dismissed.app")))
            assertEquals(listOf(entry), store.read().entries)
            assertTrue("dismissed.app" in store.read().dismissed)
            assertTrue(File(temp, "files/library.json").isFile)
            assertFalse(File(temp, "files/library.json.tmp").exists())
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun decodesLibraryEntriesWithoutControlsProfileId() {
        val temp = Files.createTempDirectory("bifrost-old-library").toFile()
        try {
            val library = File(temp, "library.json")
            library.writeText("""{"entries":[{"id":"old","kind":"PC","title":"Old game","addedAt":1}]}""")
            val entry = LibraryStore(library).read().entries.single()
            assertEquals("Old game", entry.title)
            assertEquals(null, entry.controlsProfileId)
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun decodesPreHubLibraryWithNewFieldsDefaulted() {
        val temp = Files.createTempDirectory("bifrost-pre-hub-library").toFile()
        try {
            val library = File(temp, "library.json")
            library.writeText(
                """{"entries":[{"id":"old","kind":"NATIVE","title":"Old app","addedAt":1}],"dismissed":[]}""",
            )
            val snapshot = LibraryStore(library).read()
            val entry = snapshot.entries.single()
            assertTrue(snapshot.systems.isEmpty())
            assertEquals(null, entry.systemId)
            assertEquals(0, entry.playCount)
            assertEquals(ControllerMode.OVERLAY, entry.controllerMode)
            assertEquals(ControllerApi.AUTO, entry.controllerApi)
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun deletingSystemUnassignsItsGames() {
        val snapshot = LibrarySnapshot(
            entries = listOf(
                LibraryEntry("one", Kind.PC, "One", addedAt = 1, systemId = "retro"),
                LibraryEntry("two", Kind.NATIVE, "Two", addedAt = 2, systemId = "other"),
            ),
            systems = listOf(LauncherSystem("retro", "Retro"), LauncherSystem("other", "Other")),
        )

        val updated = snapshot.deleteSystem("retro")

        assertEquals(listOf(LauncherSystem("other", "Other")), updated.systems)
        assertEquals(null, updated.entries.first { it.id == "one" }.systemId)
        assertEquals("other", updated.entries.first { it.id == "two" }.systemId)
    }

    @Test
    fun recordsPlayCountAndLastPlayed() {
        val snapshot = LibrarySnapshot(
            entries = listOf(LibraryEntry("game", Kind.PC, "Game", addedAt = 1, playCount = 2)),
        )

        val updated = snapshot.recordPlay("game", 1234L)

        assertEquals(3, updated.entries.single().playCount)
        assertEquals(1234L, updated.entries.single().lastPlayed)
    }

    @Test
    fun recordingPcLaunchPreservesPlayAndContainerUpdates() {
        val snapshot = LibrarySnapshot(
            entries = listOf(LibraryEntry("game", Kind.PC, "Game", addedAt = 1, playCount = 2)),
        )

        val updated = snapshot.recordPlayAndUpdate("game", 1234L) { it.copy(containerId = 42) }

        assertEquals(3, updated.entries.single().playCount)
        assertEquals(1234L, updated.entries.single().lastPlayed)
        assertEquals(42, updated.entries.single().containerId)
    }

    @Test
    fun artworkCleanupOnlyDeletesUnreferencedFilesFromItsOwnedDirectory() {
        val ownedArtwork = "/app/files/hub/banner-1.png"
        val referencedArtwork = "/app/files/hub/banner-2.png"

        assertTrue(
            shouldDeleteUnreferencedOwnedArtwork(
                ownedArtwork,
                "/app/files/hub",
                setOf(referencedArtwork),
            ),
        )
        assertFalse(
            shouldDeleteUnreferencedOwnedArtwork(
                referencedArtwork,
                "/app/files/hub",
                setOf(referencedArtwork),
            ),
        )
        assertFalse(
            shouldDeleteUnreferencedOwnedArtwork(
                "/app/files/covers/cover.png",
                "/app/files/hub",
                emptySet(),
            ),
        )
    }

    @Test
    fun topPlayedGamesUsesPlayCountAndCapsAtThree() {
        val entries = listOf(
            LibraryEntry("a", Kind.PC, "A", addedAt = 1, playCount = 4),
            LibraryEntry("b", Kind.PC, "B", addedAt = 2, playCount = 9),
            LibraryEntry("c", Kind.PC, "C", addedAt = 3, playCount = 0),
            LibraryEntry("d", Kind.PC, "D", addedAt = 4, playCount = 2),
            LibraryEntry("e", Kind.PC, "E", addedAt = 5, playCount = 5),
        )

        assertEquals(listOf("b", "e", "a"), topPlayedGames(entries).map { it.id })
    }

    @Test
    fun installedAppsCanBeFilteredByGameSearchAndLinkedState() {
        val apps = listOf(
            InstalledApp("games.one", "One Game", isGame = true, isBifrostPort = false),
            InstalledApp("tools.two", "Two Tool", isGame = false, isBifrostPort = false),
            InstalledApp("ports.three", "Three Port", isGame = true, isBifrostPort = true),
            InstalledApp("com.actofrod", "Bifrost", isGame = true, isBifrostPort = false),
            InstalledApp("com.actofrod.launcher", "Bifrost Launcher", isGame = true, isBifrostPort = false),
        )

        assertTrue(defaultGamesOnly(apps))
        assertEquals(
            listOf("games.one"),
            filterInstalledApps(apps, gamesOnly = true, query = "one", linkedPackages = emptySet())
                .map { it.packageName },
        )
        assertEquals(
            listOf("tools.two"),
            filterInstalledApps(apps, gamesOnly = false, query = "tools", linkedPackages = setOf("games.one"))
                .map { it.packageName },
        )
        assertTrue(filterInstalledApps(apps, gamesOnly = false, query = "", linkedPackages = emptySet())
            .none { it.packageName.startsWith("com.actofrod") })
        assertFalse(defaultGamesOnly(apps.filterNot { it.isGame }))
        assertFalse(defaultGamesOnly(listOf(InstalledApp("com.actofrod.clone", "Launcher", isGame = true, isBifrostPort = false))))
    }

    @Test
    fun splitControllerRectsUseFoldBoundsOrDefaultRatio() {
        val folded = splitControllerRects(1000, 1800, PixelRect(0, 980, 1000, 1020))
        assertEquals(PixelRect(0, 0, 1000, 980), folded.game)
        assertEquals(PixelRect(0, 1020, 1000, 1800), folded.controls)

        val default = splitControllerRects(1000, 1800)
        assertEquals(990, default.game.bottom)
        assertEquals(default.game.bottom, default.controls.top)
    }

    @Test
    fun resolvesStaleEntryFromCurrentLibrarySnapshot() {
        val current = LibraryEntry(
            id = "game",
            kind = Kind.PC,
            title = "Game",
            addedAt = 1,
            controlsProfileId = 7,
        )
        val stale = current.copy(controlsProfileId = null)

        assertEquals(current, LibrarySnapshot(entries = listOf(current)).currentEntry(stale))
    }

    @Test
    fun zipSlipFailureRemovesPartialImport() {
        val temp = createTempDir(prefix = "bifrost-zip-slip")
        try {
            val archive = File(temp, "unsafe.bifrost")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("bifrost.json"))
                zip.write("""{"format":1,"title":"Game","exe":"Game.exe","arch":"x86"}""".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("../escape"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
            try {
                extractBifrostPackage(archive, File(temp, "files/games"), "unsafe")
                throw AssertionError("Expected zip-slip package to fail")
            } catch (_: java.io.IOException) {
            }
            assertFalse(File(temp, "files/escape").exists())
            assertFalse(File(temp, "files/games/unsafe.partial").exists())
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun insufficientStorageRemovesPartialImportAndCover() {
        val temp = createTempDir(prefix = "bifrost-insufficient-storage")
        try {
            val archive = File(temp, "large.bifrost")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("bifrost.json"))
                zip.write("""{"format":1,"title":"Game","exe":"Game.exe","arch":"x86"}""".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("cover.png"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("game/Game.exe"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }

            try {
                extractBifrostPackage(archive, File(temp, "files/games"), "low-space", Long.MAX_VALUE)
                throw AssertionError("Expected insufficient-storage import to fail")
            } catch (error: IOException) {
                assertEquals("Not enough free storage to import this game", error.message)
            }
            assertFalse(File(temp, "files/games/low-space.partial").exists())
            assertFalse(File(temp, "files/covers/low-space.png").exists())
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun storageReserveIsCheckedAfterArchiveEntries() {
        val temp = createTempDir(prefix = "bifrost-final-storage-check")
        try {
            val archive = File(temp, "game.bifrost")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("bifrost.json"))
                zip.write("""{"format":1,"title":"Game","exe":"Game.exe","arch":"x86"}""".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("cover.png"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("game/Game.exe"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
            var checks = 0

            try {
                extractBifrostPackage(
                    archive,
                    File(temp, "files/games"),
                    "end-low",
                    minFreeBytes = 1L,
                    freeSpace = {
                        checks++
                        if (checks <= 3) 1L else 0L
                    },
                )
                throw AssertionError("Expected final storage check to fail")
            } catch (error: IOException) {
                assertEquals("Not enough free storage to import this game", error.message)
            }
            assertEquals(4, checks)
            assertFalse(File(temp, "files/games/end-low.partial").exists())
            assertFalse(File(temp, "files/covers/end-low.png").exists())
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected validation to fail")
        } catch (_: IllegalArgumentException) {
        } catch (_: kotlinx.serialization.SerializationException) {
        }
    }
}
