package com.actofrod.bifrost

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
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

private data class ParsedPe(val info: PeInfo, val size: Long)

private data class PeSection(val virtualAddress: Long, val size: Long, val rawOffset: Long)

object PeScan {
    private const val maxImportDescriptors = 4096
    private const val maxLegacyD3dFileSize = 64L * 1024 * 1024
    private const val legacyD3dChunkSize = 1024 * 1024
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

    fun parseImports(executable: File): Set<String>? =
        RandomAccessFile(executable, "r").use { parse(PeReader(it))?.imports }

    fun scan(gameDirectory: File, pickedExecutable: File? = null): PeScanResult {
        require(gameDirectory.isDirectory) { "${gameDirectory.path} is not a game folder" }
        val root = gameDirectory.canonicalFile
        val rootPath = root.toPath()
        val pickedFile = pickedExecutable?.let { file ->
            val canonical = try {
                file.canonicalFile
            } catch (error: IOException) {
                throw unreadableExecutable(file, error)
            } catch (error: SecurityException) {
                throw unreadableExecutable(file, error)
            }
            canonical.takeIf { it.toPath().startsWith(rootPath) }
        }
        fun canonicalRelativePath(file: File): String? {
            val path = file.canonicalFile.toPath()
            if (!path.startsWith(rootPath)) return null
            return rootPath.relativize(path).toString().replace(File.separatorChar, '/')
        }
        val pickedPath = pickedFile?.let {
            rootPath.relativize(it.toPath()).toString().replace(File.separatorChar, '/')
        }

        val paths = mutableListOf<File>()
        walk(root, 4, paths)
        val executables = mutableListOf<PeExecutable>()
        val scannedCanonicalPaths = mutableSetOf<String>()
        for (file in paths) {
            if (!file.extension.equals("exe", ignoreCase = true)) continue
            if (skippedExecutableNames.any { file.name.lowercase().contains(it) }) continue
            val canonicalPath = try {
                canonicalRelativePath(file)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } ?: continue
            val canonicalKey = canonicalPath.lowercase()
            val parsed = try {
                parseFile(file)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } ?: continue
            scannedCanonicalPaths += canonicalKey
            val path = file.relativeTo(root).path.replace(File.separatorChar, '/')
            executables += PeExecutable(
                path,
                parsed.info.arch,
                parsed.info.gui,
                parsed.info.imports,
                parsed.size,
            )
        }
        if (pickedFile != null && pickedPath != null && pickedPath.lowercase() !in scannedCanonicalPaths) {
            val parsed = try {
                parseFile(pickedFile)
            } catch (error: IOException) {
                throw unreadableExecutable(pickedExecutable ?: pickedFile, error)
            } catch (error: SecurityException) {
                throw unreadableExecutable(pickedExecutable ?: pickedFile, error)
            }
            if (parsed == null) {
                throw IllegalArgumentException(
                    "${pickedExecutable?.name ?: pickedFile.name} isn't a 32- or 64-bit Windows program, so Bifrost can't run it.",
                )
            }
            executables += PeExecutable(
                pickedPath,
                parsed.info.arch,
                parsed.info.gui,
                parsed.info.imports,
                parsed.size,
            )
        }
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
            val parsed = try {
                RandomAccessFile(file, "r").use { input ->
                    val reader = PeReader(input)
                    val pe = parse(reader) ?: return@use null
                    pe.imports.mapNotNull(::apiForDll).toSet() to usesLegacyD3d(reader)
                }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } ?: continue
            found += parsed.first
            if (parsed.second) found += "Direct3D 7 or older"
        }
        return apiOrder.filter { it in found }
    }

    private fun parseFile(file: File): ParsedPe? =
        RandomAccessFile(file, "r").use { input ->
            val reader = PeReader(input)
            parse(reader)?.let { ParsedPe(it, reader.length) }
        }

    private fun unreadableExecutable(file: File, error: Exception): IllegalArgumentException =
        IllegalArgumentException("Bifrost couldn't read ${file.name}: ${error.message}", error)

    private fun parse(reader: PeReader): PeInfo? {
        if (reader.u16(0L) != 0x5a4d) return null
        val peOffset = reader.u32(0x3cL) ?: return null
        if (!reader.hasRange(peOffset, 24L) || reader.u32(peOffset) != 0x00004550L) return null
        val coff = peOffset + 4L
        val arch = when (reader.u16(coff) ?: return null) {
            0x014c -> "x86"
            0x8664 -> "x64"
            else -> return null
        }
        val sectionCount = reader.u16(coff + 2L) ?: return null
        val optionalSize = reader.u16(coff + 16L) ?: return null
        if (reader.u16(coff + 18L) == null) return null
        val optional = coff + 20L
        val magic = reader.u16(optional) ?: return null
        val is64 = when (magic) {
            0x10b -> false
            0x20b -> true
            else -> return null
        }
        val imageBase = if (is64) reader.u64(optional + 24L) ?: return null else reader.u32(optional + 28L) ?: return null
        val subsystem = reader.u16(optional + 68L) ?: return null
        val directoryCountOffset = optional + if (is64) 108L else 92L
        val directoryOffset = optional + if (is64) 112L else 96L
        val directoryCount = reader.u32(directoryCountOffset) ?: return null
        fun directory(index: Int): Long {
            if (index.toLong() >= directoryCount) return 0L
            return reader.u32(directoryOffset + index.toLong() * 8L) ?: 0L
        }
        val sectionStart = optional + optionalSize.toLong()
        val sections = (0 until sectionCount).mapNotNull { index ->
            val offset = sectionStart + index.toLong() * 40L
            if (!reader.hasRange(offset, 40L)) return@mapNotNull null
            val virtualSize = reader.u32(offset + 8L) ?: return@mapNotNull null
            val virtualAddress = reader.u32(offset + 12L) ?: return@mapNotNull null
            val rawSize = reader.u32(offset + 16L) ?: return@mapNotNull null
            val rawOffset = reader.u32(offset + 20L) ?: return@mapNotNull null
            PeSection(virtualAddress, maxOf(virtualSize, rawSize), rawOffset)
        }
        fun rawOffset(rva: Long): Long? {
            val section = sections.firstOrNull { rva >= it.virtualAddress && rva - it.virtualAddress < it.size } ?: return null
            return rva - section.virtualAddress + section.rawOffset
        }
        fun cString(rva: Long): String? = rawOffset(rva)?.let(reader::cString)

        val imports = linkedSetOf<String>()
        var importOffset = rawOffset(directory(1))
        var importCount = 0
        while (importCount < maxImportDescriptors && importOffset != null &&
            reader.hasRange(importOffset, 20L) && reader.hasNonZero(importOffset, 20) == true
        ) {
            val nameRva = reader.u32(importOffset + 12L) ?: break
            cString(nameRva)?.let(imports::add)
            importOffset += 20L
            importCount++
        }
        var delayOffset = rawOffset(directory(13))
        var delayCount = 0
        while (delayCount < maxImportDescriptors && delayOffset != null &&
            reader.hasRange(delayOffset, 32L) && reader.hasNonZero(delayOffset, 32) == true
        ) {
            val attributes = reader.u32(delayOffset) ?: break
            var name = reader.u32(delayOffset + 4L) ?: break
            if (attributes and 1L == 0L) name = (name - imageBase) and 0xffff_ffffL
            cString(name)?.let(imports::add)
            delayOffset += 32L
            delayCount++
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

    private fun usesLegacyD3d(reader: PeReader): Boolean {
        if (reader.length > maxLegacyD3dFileSize) return false
        val overlap = legacyD3dGuids.maxOf { it.size } - 1
        val buffer = ByteArray(legacyD3dChunkSize + overlap)
        var offset = 0L
        var carry = 0
        while (offset < reader.length) {
            val chunkLength = minOf(legacyD3dChunkSize.toLong(), reader.length - offset).toInt()
            reader.readAt(offset, buffer, carry, chunkLength)
            val bytesRead = carry + chunkLength
            for (guid in legacyD3dGuids) {
                for (index in 0..bytesRead - guid.size) {
                    if (guid.indices.all { buffer[index + it] == guid[it] }) return true
                }
            }
            carry = minOf(overlap, bytesRead)
            System.arraycopy(buffer, bytesRead - carry, buffer, 0, carry)
            offset += chunkLength
        }
        return false
    }

    private fun guid(a: Long, b: Int, c: Int, vararg tail: Int): ByteArray =
        byteArrayOf(
            a.toByte(), (a shr 8).toByte(), (a shr 16).toByte(), (a shr 24).toByte(),
            b.toByte(), (b shr 8).toByte(), c.toByte(), (c shr 8).toByte(),
            *tail.map(Int::toByte).toByteArray(),
        )
}

private class PeReader(private val file: RandomAccessFile) {
    val length: Long = file.length()

    fun hasRange(offset: Long, size: Long): Boolean =
        offset >= 0L && size >= 0L && size <= length && offset <= length - size

    fun u16(offset: Long): Int? {
        if (!hasRange(offset, 2L)) return null
        file.seek(offset)
        return file.readUnsignedByte() or (file.readUnsignedByte() shl 8)
    }

    fun u32(offset: Long): Long? {
        if (!hasRange(offset, 4L)) return null
        file.seek(offset)
        var value = 0L
        repeat(4) { index -> value = value or (file.readUnsignedByte().toLong() shl (index * 8)) }
        return value
    }

    fun u64(offset: Long): Long? {
        if (!hasRange(offset, 8L)) return null
        val low = u32(offset) ?: return null
        val high = u32(offset + 4L) ?: return null
        return low or (high shl 32)
    }

    fun cString(offset: Long): String? {
        if (!hasRange(offset, 1L)) return null
        val bytes = ByteArray(minOf(260L, length - offset).toInt())
        file.seek(offset)
        file.readFully(bytes)
        val end = bytes.indexOf(0.toByte())
        if (end < 0) return null
        return String(bytes, 0, end, Charsets.ISO_8859_1).lowercase()
    }

    fun hasNonZero(offset: Long, size: Int): Boolean? {
        if (!hasRange(offset, size.toLong())) return null
        file.seek(offset)
        repeat(size) {
            if (file.readUnsignedByte() != 0) return true
        }
        return false
    }

    fun readAt(offset: Long, target: ByteArray, targetOffset: Int, size: Int) {
        if (targetOffset < 0 || size < 0 || targetOffset > target.size - size ||
            !hasRange(offset, size.toLong())
        ) {
            throw IOException("Read is outside the executable")
        }
        file.seek(offset)
        file.readFully(target, targetOffset, size)
    }
}
