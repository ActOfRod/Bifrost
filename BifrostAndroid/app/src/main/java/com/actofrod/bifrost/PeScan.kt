package com.actofrod.bifrost

import java.io.File
import java.nio.file.Files

data class PeExecutable(
    val path: String,
    val arch: String,
    val gui: Boolean,
    val imports: Set<String>,
    val size: Long,
)

data class PeScanResult(val root: File, val executables: List<PeExecutable>, val mainIndex: Int) {
    val main: PeExecutable get() = executables[mainIndex]
}

private data class PeInfo(
    val arch: String,
    val gui: Boolean,
    val imports: Set<String>,
)

private data class PeSection(val virtualAddress: Long, val size: Long, val rawOffset: Long)

object PeScan {
    private val skippedExecutableNames = listOf(
        "unins", "setup", "install", "vcredist", "vc_redist", "dxsetup", "dxwebsetup", "dotnet", "crashreport",
        "crashhandler", "bugreport", "uninst", "autorun", "launcherupdate", "updater", "physx", "oalinst",
    )
    private val apiOrder = listOf(
        "DirectDraw",
        "Direct3D 7 or older",
        "Direct3D 8",
        "Direct3D 9",
        "Direct3D 10",
        "Direct3D 11",
        "Direct3D 12",
        "DXGI",
        "OpenGL",
        "3dfx Glide",
        "DirectSound",
        "WinMM (audio/joystick)",
        "DirectInput",
        "XInput",
    )
    private val bundledWrappers = setOf("d3d8.dll", "d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll")
    private val legacyD3dGuids = listOf(
        guid(0x3bba0080, 0x2421, 0x11cf, 0xa3, 0x1a, 0x00, 0x20, 0xaf, 0x6d, 0x2a, 0x9b),
        guid(0x6aae1ec1, 0x662a, 0x11d0, 0x88, 0x9d, 0x00, 0xaa, 0x00, 0xbb, 0xb7, 0x6a),
        guid(0xbb223240, 0xe72b, 0x11d0, 0xa9, 0xb4, 0x00, 0xaa, 0x00, 0xc0, 0x99, 0x3e),
        guid(0xf5049e77, 0x4861, 0x11d2, 0xa4, 0x07, 0x00, 0xa0, 0xc9, 0x06, 0x29, 0xa8),
    )

    fun scan(gameDirectory: File): PeScanResult {
        require(gameDirectory.isDirectory) { "${gameDirectory.path} is not a game folder" }
        val root = gameDirectory.canonicalFile
        val paths = mutableListOf<File>()
        walk(root, 4, paths)
        val executables = paths.asSequence()
            .filter { it.extension.equals("exe", ignoreCase = true) }
            .filterNot { file -> skippedExecutableNames.any { file.name.lowercase().contains(it) } }
            .mapNotNull { file ->
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@mapNotNull null
                val info = parse(bytes) ?: return@mapNotNull null
                PeExecutable(file.relativeTo(root).path.replace(File.separatorChar, '/'), info.arch, info.gui, info.imports, bytes.size.toLong())
            }
            .toList()
        require(executables.isNotEmpty()) { "No Windows game executable found in ${root.path}" }
        fun score(index: Int): Long {
            val executable = executables[index]
            val usesGraphics = executable.imports.any { apiForDll(it)?.let { label -> apiOrder.indexOf(label) <= 9 } == true }
            return (if (usesGraphics) 1L shl 40 else 0L) +
                (if (executable.gui) 1L shl 39 else 0L) + executable.size
        }
        var mainIndex = 0
        for (index in 1 until executables.size) if (score(index) >= score(mainIndex)) mainIndex = index
        return PeScanResult(root, executables, mainIndex)
    }

    fun detectApis(root: File, executable: String): List<String> {
        val exe = File(root, executable)
        val files = buildList {
            add(exe)
            exe.parentFile?.listFiles()
                ?.filter { it.isFile && it.extension.equals("dll", ignoreCase = true) }
                ?.sortedBy { it.name.lowercase() }
                ?.forEach(::add)
        }
        val found = mutableSetOf<String>()
        for (file in files) {
            val dllName = file.name.lowercase()
            if (dllName in bundledWrappers) continue
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            val pe = parse(bytes) ?: continue
            pe.imports.mapNotNullTo(found, ::apiForDll)
            if (usesLegacyD3d(bytes)) found += "Direct3D 7 or older"
        }
        return apiOrder.filter { it in found }
    }

    private fun parse(bytes: ByteArray): PeInfo? {
        if (bytes.size < 64 || bytes[0] != 'M'.code.toByte() || bytes[1] != 'Z'.code.toByte()) return null
        val peOffset = u32(bytes, 0x3c)?.toInt() ?: return null
        if (peOffset < 0 || !bytes.hasRange(peOffset, 24) ||
            bytes[peOffset] != 'P'.code.toByte() || bytes[peOffset + 1] != 'E'.code.toByte() ||
            bytes[peOffset + 2] != 0.toByte() || bytes[peOffset + 3] != 0.toByte()
        ) return null
        val coff = peOffset + 4
        val arch = when (u16(bytes, coff) ?: return null) {
            0x014c -> "x86"
            0x8664 -> "x64"
            else -> return null
        }
        val sectionCount = u16(bytes, coff + 2) ?: return null
        val optionalSize = u16(bytes, coff + 16) ?: return null
        val characteristics = u16(bytes, coff + 18) ?: return null
        val optional = coff + 20
        val magic = u16(bytes, optional) ?: return null
        val is64 = when (magic) {
            0x10b -> false
            0x20b -> true
            else -> return null
        }
        val imageBase = if (is64) u64(bytes, optional + 24) ?: return null else u32(bytes, optional + 28) ?: return null
        val subsystem = u16(bytes, optional + 68) ?: return null
        val directoryCountOffset = optional + if (is64) 108 else 92
        val directoryOffset = optional + if (is64) 112 else 96
        val directoryCount = u32(bytes, directoryCountOffset)?.toInt() ?: return null
        fun directory(index: Int): Long {
            if (index >= directoryCount) return 0L
            return u32(bytes, directoryOffset + index * 8) ?: 0L
        }
        val sectionStart = optional + optionalSize
        val sections = (0 until sectionCount).mapNotNull { index ->
            val offset = sectionStart + index * 40
            if (!bytes.hasRange(offset, 40)) return@mapNotNull null
            val virtualSize = u32(bytes, offset + 8) ?: return@mapNotNull null
            val virtualAddress = u32(bytes, offset + 12) ?: return@mapNotNull null
            val rawSize = u32(bytes, offset + 16) ?: return@mapNotNull null
            val rawOffset = u32(bytes, offset + 20) ?: return@mapNotNull null
            PeSection(virtualAddress, maxOf(virtualSize, rawSize), rawOffset)
        }
        fun rawOffset(rva: Long): Int? {
            val section = sections.firstOrNull { rva >= it.virtualAddress && rva - it.virtualAddress < it.size } ?: return null
            val offset = rva - section.virtualAddress + section.rawOffset
            return offset.takeIf { it <= Int.MAX_VALUE }?.toInt()
        }
        fun cString(rva: Long): String? {
            val offset = rawOffset(rva) ?: return null
            if (offset !in bytes.indices) return null
            val end = (offset until minOf(bytes.size, offset + 260)).firstOrNull { bytes[it] == 0.toByte() } ?: return null
            return bytes.copyOfRange(offset, end).toString(Charsets.ISO_8859_1).lowercase()
        }
        val imports = linkedSetOf<String>()
        var importOffset = rawOffset(directory(1))
        while (importOffset != null && bytes.hasRange(importOffset, 20) &&
            bytes.sliceArray(importOffset until importOffset + 20).any { it != 0.toByte() }
        ) {
            val nameRva = u32(bytes, importOffset + 12) ?: break
            cString(nameRva)?.let(imports::add)
            importOffset += 20
        }
        var delayOffset = rawOffset(directory(13))
        while (delayOffset != null && bytes.hasRange(delayOffset, 32) &&
            bytes.sliceArray(delayOffset until delayOffset + 32).any { it != 0.toByte() }
        ) {
            val attributes = u32(bytes, delayOffset) ?: break
            var name = u32(bytes, delayOffset + 4) ?: break
            if (attributes and 1L == 0L) name = (name - imageBase) and 0xffff_ffffL
            cString(name)?.let(imports::add)
            delayOffset += 32
        }
        return PeInfo(arch, subsystem == 2, imports)
    }

    private fun walk(directory: File, depth: Int, output: MutableList<File>) {
        val children = directory.listFiles()?.filterNot { Files.isSymbolicLink(it.toPath()) }?.sortedBy { it.name } ?: return
        for (child in children) {
            when {
                child.isDirectory && depth > 0 -> walk(child, depth - 1, output)
                child.isFile -> output += child
            }
        }
    }

    private fun apiForDll(dll: String): String? = when (dll.lowercase()) {
        "ddraw.dll" -> "DirectDraw"
        "d3d8.dll" -> "Direct3D 8"
        "d3d9.dll" -> "Direct3D 9"
        "d3d10.dll", "d3d10_1.dll", "d3d10core.dll" -> "Direct3D 10"
        "d3d11.dll" -> "Direct3D 11"
        "d3d12.dll" -> "Direct3D 12"
        "dxgi.dll" -> "DXGI"
        "opengl32.dll" -> "OpenGL"
        "glide.dll", "glide2x.dll", "glide3x.dll" -> "3dfx Glide"
        "dsound.dll" -> "DirectSound"
        "winmm.dll" -> "WinMM (audio/joystick)"
        "dinput.dll", "dinput8.dll" -> "DirectInput"
        else -> if (dll.startsWith("xinput")) "XInput" else null
    }

    private fun usesLegacyD3d(bytes: ByteArray): Boolean = legacyD3dGuids.any { guid ->
        (0..bytes.size - guid.size).any { offset ->
            guid.indices.all { index -> bytes[offset + index] == guid[index] }
        }
    }

    private fun guid(a: Long, b: Int, c: Int, vararg tail: Int): ByteArray =
        byteArrayOf(
            a.toByte(), (a shr 8).toByte(), (a shr 16).toByte(), (a shr 24).toByte(),
            b.toByte(), (b shr 8).toByte(), c.toByte(), (c shr 8).toByte(),
            *tail.map(Int::toByte).toByteArray(),
        )

    private fun u16(bytes: ByteArray, offset: Int): Int? {
        if (!bytes.hasRange(offset, 2)) return null
        return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long? {
        if (!bytes.hasRange(offset, 4)) return null
        return (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)
    }

    private fun u64(bytes: ByteArray, offset: Int): Long? {
        val low = u32(bytes, offset) ?: return null
        val high = u32(bytes, offset + 4) ?: return null
        return low or (high shl 32)
    }

    private fun ByteArray.hasRange(offset: Int, length: Int): Boolean =
        offset >= 0 && length >= 0 && offset <= size - length
}
