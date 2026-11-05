package com.actofrod.bifrost

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun writeAtomically(destination: File, tempDirectory: File, contents: String) {
    val parent = destination.parentFile ?: throw IOException("Profile path has no parent directory")
    if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create profile directory")
    if (!tempDirectory.isDirectory && !tempDirectory.mkdirs()) throw IOException("Could not create controls temporary directory")

    val prefix = "${destination.name.take(3).padEnd(3, '_')}-"
    val temp = File.createTempFile(prefix, ".tmp", tempDirectory)
    try {
        FileOutputStream(temp).use { stream ->
            stream.write(contents.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        if (temp.exists()) temp.delete()
    }
}
