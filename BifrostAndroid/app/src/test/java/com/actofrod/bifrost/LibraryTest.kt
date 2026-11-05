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
