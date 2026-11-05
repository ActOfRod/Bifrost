package com.actofrod.bifrost

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

internal fun copyGameFolder(
    context: Context,
    treeUri: Uri,
    gamesDirectory: File,
    id: String = UUID.randomUUID().toString(),
    minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
    freeSpace: () -> Long = { gamesDirectory.usableSpace },
    progress: (Long) -> Unit = {},
): File {
    require(id.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid game id" }
    val source = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IOException("Could not access selected game folder")
    if (!source.isDirectory) throw IOException("Selected game folder is not a directory")
    gamesDirectory.mkdirs()
    val destination = File(gamesDirectory, id)
    val partial = File(gamesDirectory, "$id.partial")
    if (destination.exists() || partial.exists()) throw IOException("Game import destination already exists")
    if (!partial.mkdirs()) throw IOException("Could not prepare game folder copy")
    var copied = 0L
    var bytesSinceSpaceCheck = 0L
    fun checkFreeSpace() {
        requireFreeStorage(freeSpace(), minFreeBytes)
        bytesSinceSpaceCheck = 0L
    }
    fun copyDirectory(documentDirectory: DocumentFile, destinationDirectory: File) {
        if (!destinationDirectory.exists() && !destinationDirectory.mkdirs()) {
            throw IOException("Could not create ${destinationDirectory.name}")
        }
        val children = documentDirectory.listFiles().sortedBy { it.name.orEmpty().lowercase() }
        for (child in children) {
            val name = child.name ?: throw IOException("A game file has no name")
            if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) {
                throw IOException("A game file has an invalid name")
            }
            val target = File(destinationDirectory, name)
            if (target.exists()) throw IOException("Duplicate game file name: $name")
            if (child.isDirectory) {
                copyDirectory(child, target)
            } else {
                checkFreeSpace()
                val input = context.contentResolver.openInputStream(child.uri)
                    ?: throw IOException("Could not read $name")
                input.use { sourceStream ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = sourceStream.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            bytesSinceSpaceCheck += count
                            if (bytesSinceSpaceCheck >= STORAGE_CHECK_INTERVAL_BYTES) checkFreeSpace()
                            progress(copied)
                        }
                    }
                }
            }
        }
    }
    try {
        checkFreeSpace()
        copyDirectory(source, partial)
        checkFreeSpace()
        if (!partial.renameTo(destination)) throw IOException("Could not finalize copied game folder")
        return destination
    } catch (error: Throwable) {
        partial.deleteRecursively()
        throw error
    }
}
