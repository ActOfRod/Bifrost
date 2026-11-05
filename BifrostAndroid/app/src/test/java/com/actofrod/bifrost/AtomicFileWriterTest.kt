package com.actofrod.bifrost

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class AtomicFileWriterTest {
    @Test
    fun replacesProfileAndRemovesTemporaryFile() {
        val root = Files.createTempDirectory("bifrost-atomic-profile").toFile()
        try {
            val profile = File(root, "profiles/controls-7.icp").apply {
                checkNotNull(parentFile).mkdirs()
                writeText("old")
            }
            val temporaryDirectory = File(root, "controls-tmp")

            writeAtomically(profile, temporaryDirectory, "new profile")

            assertEquals("new profile", profile.readText())
            assertEquals(emptyList<File>(), temporaryDirectory.listFiles()?.toList().orEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
