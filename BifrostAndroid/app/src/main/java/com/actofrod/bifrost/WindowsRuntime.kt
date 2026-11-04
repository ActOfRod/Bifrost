package com.actofrod.bifrost

import android.content.Context
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.core.DefaultVersion
import com.winlator.core.FileUtils
import com.winlator.core.TarCompressorUtils
import com.winlator.core.WineInfo
import com.winlator.xenvironment.RootFS
import com.winlator.xenvironment.RootFSInstaller
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val STORAGE_RESERVE_BYTES = 512L * 1024 * 1024

object WindowsRuntime {
    fun isInstalled(context: Context): Boolean {
        val rootFs = RootFS.find(context.applicationContext)
        return runCatching {
            rootFs.isValid() && rootFs.getVersion() >= RootFSInstaller.LATEST_VERSION.toInt()
        }.getOrDefault(false)
    }

    suspend fun install(context: Context, progress: (Int) -> Unit) {
        val appContext = context.applicationContext
        if (isInstalled(appContext)) {
            progress(100)
            return
        }

        withContext(Dispatchers.IO) {
            val rootFs = RootFS.find(appContext)
            val rootDir = rootFs.getRootDir()
            val contentLength = TarCompressorUtils.getContentLength(
                TarCompressorUtils.Type.ZSTD,
                appContext,
                RootFSInstaller.FILENAME,
                rootDir,
            )
            if (contentLength <= 0L) throw IOException("Could not read Windows runtime system files")
            val requiredSpace = contentLength + STORAGE_RESERVE_BYTES
            val storageDirectory = rootDir.parentFile ?: rootDir
            if (storageDirectory.usableSpace < requiredSpace) {
                throw IOException(
                    "Not enough free storage to install Windows runtime; " +
                        "requires ${requiredSpace / (1024 * 1024)} MiB including reserve",
                )
            }

            resetPreferenceVersions(appContext)
            clearRootDir(rootDir)

            progress(0)
            val totalSize = AtomicLong()
            val installed = TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                appContext,
                RootFSInstaller.FILENAME,
                rootDir,
            ) { file, size ->
                if (size > 0L) {
                    val copied = totalSize.addAndGet(size)
                    progress(((copied.toDouble() / contentLength) * 100).toInt().coerceIn(0, 100))
                }
                file
            }
            if (!installed) throw IOException("Unable to install Windows runtime system files")

            rootFs.createRFSVersionFile(RootFSInstaller.LATEST_VERSION.toInt())
            resetContainerRfsVersions(appContext)
            progress(100)
        }
    }

    private fun resetPreferenceVersions(context: Context) {
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
            .edit()
            .putString("box64_version", DefaultVersion.BOX64)
            .remove("current_box64_version")
            .remove("current_graphics_driver")
            .apply()
    }

    private fun resetContainerRfsVersions(context: Context) {
        val manager = ContainerManager(context)
        for (container in manager.getContainers()) {
            val rfsVersion = container.getExtra("rfsVersion")
            val wineVersion = container.getWineVersion()
            if (
                rfsVersion.isNotEmpty() &&
                WineInfo.isMainWineVersion(wineVersion) &&
                rfsVersion.toShort() <= RootFSInstaller.UPDATE_WINEPREFIX_VERSION.toShort()
            ) {
                container.putExtra("wineprefixNeedsUpdate", "t")
            }
            container.putExtra("rfsVersion", null)
            container.saveData()
        }
    }

    private fun clearOptDir(optDir: File) {
        optDir.listFiles()?.forEach { file ->
            if (file.name != "installed-wine") FileUtils.delete(file)
        }
    }

    private fun clearRootDir(rootDir: File) {
        if (!rootDir.isDirectory) {
            rootDir.mkdirs()
            return
        }
        rootDir.listFiles()?.forEach { file ->
            if (file.isDirectory && (file.name == "home" || file.name == "opt")) {
                if (file.name == "opt") clearOptDir(file)
            } else {
                FileUtils.delete(file)
            }
        }
    }
}
