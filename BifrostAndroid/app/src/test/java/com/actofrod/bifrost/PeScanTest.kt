package com.actofrod.bifrost

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Test

class PeScanTest {
    @Test
    fun parsesImportsFromOneExecutable() {
        val root = Files.createTempDirectory("bifrost-pe-single-file").toFile()
        try {
            val executable = File(root, "Game.exe")
            executable.writeBytes(tinyPe(0x014c, listOf("dinput8.dll", "xinput1_4.dll")))

            assertEquals(setOf("dinput8.dll", "xinput1_4.dll"), PeScan.parseImports(executable))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun scansX86AndX64MachineTypes() {
        val root = Files.createTempDirectory("bifrost-pe-arch").toFile()
        try {
            File(root, "x86.exe").writeBytes(tinyPe(0x014c))
            File(root, "x64.exe").writeBytes(tinyPe(0x8664))
            val scan = PeScan.scan(root)
            assertEquals(setOf("x86", "x64"), scan.executables.map { it.arch }.toSet())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun parsesSparseMultiGiBPickedExecutableWithoutLoadingWholeFile() {
        val root = Files.createTempDirectory("bifrost-pe-sparse").toFile()
        try {
            val executable = File(root, "LargeGame.exe")
            val expectedSize = writeSparsePe(executable)

            val scan = PeScan.scan(root, executable)
            val selectedIndex = executableIndexForPath(
                scan.executables.map { it.path },
                "LargeGame.exe",
                scan.mainIndex,
            )

            assertEquals("LargeGame.exe", scan.executables[selectedIndex].path)
            assertEquals(expectedSize, scan.executables[selectedIndex].size)
            assertEquals(listOf("Direct3D 11"), PeScan.detectApis(root, "LargeGame.exe"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun includesPickedSetupExecutableAndSelectsItsPath() {
        val root = Files.createTempDirectory("bifrost-pe-picked-setup").toFile()
        try {
            File(root, "Game.exe").writeBytes(tinyPe(0x014c))
            val picked = File(root, "GameSetup.exe")
            picked.writeBytes(tinyPe(0x014c))

            val scan = PeScan.scan(root, picked)

            assertEquals(setOf("Game.exe", "GameSetup.exe"), scan.executables.map { it.path }.toSet())
            assertEquals(
                1,
                executableIndexForPath(scan.executables.map { it.path }, "GameSetup.exe", scan.mainIndex),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun includesPickedExecutableBeyondWalkDepth() {
        val root = Files.createTempDirectory("bifrost-pe-picked-deep").toFile()
        try {
            val picked = File(root, "one/two/three/four/five/six/DeepGame.exe")
            picked.parentFile?.mkdirs()
            picked.writeBytes(tinyPe(0x014c))

            val scan = PeScan.scan(root, picked)

            assertEquals(listOf("one/two/three/four/five/six/DeepGame.exe"), scan.executables.map { it.path })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsPickedNonPeExecutable() {
        val root = Files.createTempDirectory("bifrost-pe-picked-invalid").toFile()
        try {
            val picked = File(root, "GameSetup.exe")
            picked.writeText("not a Windows program")

            val error = runCatching { PeScan.scan(root, picked) }.exceptionOrNull()

            assertEquals(IllegalArgumentException::class.java, error?.javaClass)
            assertEquals(
                "GameSetup.exe isn't a 32- or 64-bit Windows program, so Bifrost can't run it.",
                error?.message,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsTruncatedPickedPeHeader() {
        val root = Files.createTempDirectory("bifrost-pe-picked-truncated").toFile()
        try {
            val picked = File(root, "Truncated.exe")
            picked.writeBytes(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte()))

            val error = runCatching { PeScan.scan(root, picked) }.exceptionOrNull()

            assertEquals(IllegalArgumentException::class.java, error?.javaClass)
            assertEquals(
                "Truncated.exe isn't a 32- or 64-bit Windows program, so Bifrost can't run it.",
                error?.message,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun doesNotDuplicatePickedExecutableAlreadyFoundByWalk() {
        val root = Files.createTempDirectory("bifrost-pe-picked-duplicate").toFile()
        try {
            val picked = File(root, "Game.exe")
            picked.writeBytes(tinyPe(0x014c))

            val scan = PeScan.scan(root, picked)

            assertEquals(1, scan.executables.count { it.path.equals("Game.exe", ignoreCase = true) })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun scanWithoutPickedExecutableStillSkipsSetupNames() {
        val root = Files.createTempDirectory("bifrost-pe-no-picked").toFile()
        try {
            File(root, "Game.exe").writeBytes(tinyPe(0x014c))
            File(root, "Setup.exe").writeBytes(tinyPe(0x014c))

            val scan = PeScan.scan(root)

            assertEquals(setOf("Game.exe"), scan.executables.map { it.path }.toSet())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun detectsD3dAndDirectDrawImportsWithManifestLabels() {
        val root = Files.createTempDirectory("bifrost-pe-apis").toFile()
        try {
            File(root, "Game.exe").writeBytes(tinyPe(0x014c, listOf("d3d8.dll", "d3d9.dll", "ddraw.dll")))
            assertEquals(
                listOf("DirectDraw", "Direct3D 8", "Direct3D 9"),
                PeScan.detectApis(root, "Game.exe"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun detectsDirect3d12AndSelectsVkd3d() {
        val root = Files.createTempDirectory("bifrost-pe-d3d12").toFile()
        try {
            File(root, "Game.exe").writeBytes(tinyPe(0x014c, listOf("d3d12.dll")))
            File(root, "Launcher.exe").writeBytes(tinyPe(0x014c).copyOf(0x4000))

            val apis = PeScan.detectApis(root, "Game.exe")
            assertEquals(listOf("Direct3D 12"), apis)
            assertEquals("vkd3d", dxwrapperFor(apis))
            assertEquals("Game.exe", PeScan.scan(root).main.path)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun detectsLegacyDirect3dInterfaceGuid() {
        val root = Files.createTempDirectory("bifrost-pe-guid").toFile()
        try {
            File(root, "Game.exe").writeBytes(
                tinyPe(
                    0x8664,
                    listOf("ddraw.dll"),
                    legacyGuid = byteArrayOf(
                        0x77, 0x9e.toByte(), 0x04, 0xf5.toByte(), 0x61, 0x48, 0xd2.toByte(), 0x11,
                        0xa4.toByte(), 0x07, 0x00, 0xa0.toByte(), 0xc9.toByte(), 0x06, 0x29, 0xa8.toByte(),
                    ),
                ),
            )
            assertEquals(listOf("DirectDraw", "Direct3D 7 or older"), PeScan.detectApis(root, "Game.exe"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun detectsDelayLoadedImports() {
        val root = Files.createTempDirectory("bifrost-pe-delay").toFile()
        try {
            File(root, "Game.exe").writeBytes(tinyPe(0x014c, delayDlls = listOf("d3d9.dll")))
            assertEquals(listOf("Direct3D 9"), PeScan.detectApis(root, "Game.exe"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun skipsWrapperDllsWhenDetectingApisFromSiblingFiles() {
        val root = Files.createTempDirectory("bifrost-pe-wrapper").toFile()
        try {
            File(root, "Game.exe").writeBytes(tinyPe(0x014c))
            File(root, "d3d9.dll").writeBytes(tinyPe(0x014c, listOf("opengl32.dll")))
            File(root, "renderer.dll").writeBytes(tinyPe(0x014c, listOf("dsound.dll")))
            assertEquals(listOf("DirectSound"), PeScan.detectApis(root, "Game.exe"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeSparsePe(file: File): Long {
        val candidateLengths = listOf(
            3L * 1024 * 1024 * 1024,
            5L * 1024 * 1024 * 1024 / 2,
        )
        var length: Long? = null
        RandomAccessFile(file, "rw").use { randomAccessFile ->
            randomAccessFile.write(tinyPe(0x014c, listOf("d3d11.dll")))
            for (candidate in candidateLengths) {
                try {
                    randomAccessFile.setLength(candidate)
                } catch (_: IOException) {
                    continue
                }
                if (randomAccessFile.length() == candidate) {
                    length = candidate
                    break
                }
            }
        }
        Assume.assumeTrue("Sparse files larger than 2 GiB are unavailable", length != null && length > Int.MAX_VALUE)
        return length ?: throw AssertionError("Assumption did not abort")
    }

    private fun tinyPe(
        machine: Int,
        dlls: List<String> = emptyList(),
        legacyGuid: ByteArray? = null,
        delayDlls: List<String> = emptyList(),
    ): ByteArray {
        val pe = 0x80
        val coff = pe + 4
        val optional = coff + 20
        val optionalSize = if (machine == 0x8664) 240 else 224
        val section = optional + optionalSize
        val rawStart = 0x200
        val bytes = ByteArray(0x1200)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        put32(bytes, 0x3c, pe)
        bytes[pe] = 'P'.code.toByte()
        bytes[pe + 1] = 'E'.code.toByte()
        put16(bytes, coff, machine)
        put16(bytes, coff + 2, 1)
        put16(bytes, coff + 16, optionalSize)
        put16(bytes, coff + 18, 0x0102)
        put16(bytes, optional, if (machine == 0x8664) 0x20b else 0x10b)
        if (machine == 0x8664) put64(bytes, optional + 24, 0x140000000L)
        else put32(bytes, optional + 28, 0x400000)
        put16(bytes, optional + 68, 2)
        val directoryCount = optional + if (machine == 0x8664) 108 else 92
        val directories = optional + if (machine == 0x8664) 112 else 96
        put32(bytes, directoryCount, 16)
        if (dlls.isNotEmpty()) {
            put32(bytes, directories + 8, 0x1000)
            put32(bytes, directories + 12, (dlls.size + 1) * 20)
        }
        if (delayDlls.isNotEmpty()) {
            put32(bytes, directories + 13 * 8, 0x1200)
            put32(bytes, directories + 13 * 8 + 4, (delayDlls.size + 1) * 32)
        }
        ".rdata".toByteArray().copyInto(bytes, section)
        put32(bytes, section + 8, 0x1000)
        put32(bytes, section + 12, 0x1000)
        put32(bytes, section + 16, 0x1000)
        put32(bytes, section + 20, rawStart)
        dlls.forEachIndexed { index, name ->
            val descriptor = rawStart + index * 20
            val nameRva = 0x1100 + index * 32
            put32(bytes, descriptor + 12, nameRva)
            name.toByteArray().copyInto(bytes, rawStart + nameRva - 0x1000)
        }
        delayDlls.forEachIndexed { index, name ->
            val descriptor = rawStart + 0x200 + index * 32
            val nameRva = 0x1300 + index * 32
            put32(bytes, descriptor, 1)
            put32(bytes, descriptor + 4, nameRva)
            name.toByteArray().copyInto(bytes, rawStart + nameRva - 0x1000)
        }
        legacyGuid?.copyInto(bytes, 0x800)
        return bytes
    }

    private fun put16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value shr 8).toByte()
    }

    private fun put32(target: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 4) target[offset + index] = (value shr (8 * index)).toByte()
    }

    private fun put64(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) target[offset + index] = (value shr (8 * index)).toByte()
    }
}
