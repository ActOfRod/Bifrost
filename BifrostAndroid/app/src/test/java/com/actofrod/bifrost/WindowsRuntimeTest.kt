package com.actofrod.bifrost

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowsRuntimeTest {
    @Test
    fun forceReinstallDoesNotSkipAnInstalledRuntime() {
        assertTrue(shouldSkipRuntimeInstall(installed = true, force = false))
        assertFalse(shouldSkipRuntimeInstall(installed = true, force = true))
        assertFalse(shouldSkipRuntimeInstall(installed = false, force = false))
    }

    @Test
    fun reclaimableBytesMirrorsRuntimeCleanupAndDoesNotFollowSymlinks() {
        val rootDir = Files.createTempDirectory("bifrost-runtime-size").toFile()
        try {
            fun write(relativePath: String, contents: String): File {
                return File(rootDir, relativePath).apply {
                    parentFile?.mkdirs()
                    writeText(contents)
                }
            }

            write("home/preserved", "home")
            write("opt/installed-wine/preserved", "wine")
            val otherOptFile = write("opt/other/file", "opt")
            val usrFile = write("usr/lib/x", "usr")
            Files.createSymbolicLink(File(rootDir, "usr/lib/link").toPath(), usrFile.toPath())

            assertEquals((otherOptFile.length() + usrFile.length()), reclaimableBytes(rootDir))
        } finally {
            rootDir.deleteRecursively()
        }
    }
}
