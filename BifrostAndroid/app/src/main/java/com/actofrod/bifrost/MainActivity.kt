package com.actofrod.bifrost

import android.app.PendingIntent
import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.winlator.XServerDisplayActivity
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.container.DXWrappers
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val PORT_META = "com.actofrod.bifrost.PORT"
private const val INSTALL_ACTION = "com.actofrod.INSTALL_RESULT"
private val GAME_COVER_ID_PATTERN =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

private fun deleteCachedInstallApk(context: Context, path: String?) {
    if (path == null) return
    runCatching {
        val cacheDirectory = context.cacheDir.canonicalFile
        val apk = File(path).canonicalFile
        if (apk.parentFile == cacheDirectory && apk.isFile && apk.name.startsWith("install-") && apk.extension == "apk") {
            apk.delete()
        }
    }
}

internal enum class Page { HOME, DETAIL, SETTINGS, LINK_APPS }
internal enum class Tab { ALL, NATIVE, PC }
internal enum class SettingsSection { ADD_GAMES, SYSTEMS, APPEARANCE, WINDOWS_RUNTIME, ABOUT }
internal enum class ImageAction { BANNER, SYSTEM_ICON, COVER }

internal data class FolderSelection(val uri: Uri, val inPlaceRoot: File?, val folderName: String)
internal data class FolderCandidate(
    val id: String,
    val gameDir: String,
    val root: File,
    val scan: PeScanResult,
    val copied: Boolean,
    val defaultTitle: String,
)

class MainActivity : ComponentActivity() {
    private lateinit var store: LibraryStore
    private lateinit var apkPicker: ActivityResultLauncher<Array<String>>
    private lateinit var packagePicker: ActivityResultLauncher<Array<String>>
    private lateinit var imagePicker: ActivityResultLauncher<String>
    private lateinit var folderPicker: ActivityResultLauncher<Uri?>
    private lateinit var storagePermissionRequest: ActivityResultLauncher<Array<String>>
    private lateinit var unknownAppsSettings: ActivityResultLauncher<Intent>
    internal var snapshot by mutableStateOf(LibrarySnapshot())
    internal var page by mutableStateOf(Page.HOME)
    internal var tab by mutableStateOf(Tab.ALL)
    internal var selected by mutableStateOf<LibraryEntry?>(null)
    internal var selectedRail by mutableStateOf("all")
    internal var searchQuery by mutableStateOf("")
    internal var settingsSection by mutableStateOf(SettingsSection.ADD_GAMES)
    internal var targetSystemId by mutableStateOf<String?>(null)
    internal var showOptionsFor by mutableStateOf<LibraryEntry?>(null)
    internal var showAddMenu by mutableStateOf(false)
    internal var installedApps by mutableStateOf<List<InstalledApp>?>(null)
    internal var installedSearch by mutableStateOf("")
    internal var installedGamesOnly by mutableStateOf(true)
    internal var selectedInstalledPackages by mutableStateOf<Set<String>>(emptySet())
    internal var accentArgb by mutableStateOf(0xFF83F0C9L)
    internal var customBannerPath by mutableStateOf<String?>(null)
    internal var imageAction by mutableStateOf<ImageAction?>(null)
    internal var imageTargetId by mutableStateOf<String?>(null)
    internal var confirmRemove by mutableStateOf<LibraryEntry?>(null)
    internal var message by mutableStateOf<String?>(null)
    internal var importBytes by mutableStateOf<Long?>(null)
    internal var importTitle by mutableStateOf("Importing game")
    internal var folderChoice by mutableStateOf<FolderSelection?>(null)
    internal var folderCandidate by mutableStateOf<FolderCandidate?>(null)
    internal var folderTitle by mutableStateOf("")
    internal var folderExeIndex by mutableStateOf(0)
    internal var folderCopyChoice by mutableStateOf(false)
    internal var folderAddInProgress by mutableStateOf(false)
    internal var showRuntimeInstallDialog by mutableStateOf(false)
    internal var runtimeInstallProgress by mutableStateOf<Int?>(null)
    internal var runtimeInstallError by mutableStateOf<String?>(null)
    private var pendingApk by mutableStateOf<File?>(null)
    private var pendingPcEntry: LibraryEntry? = null
    private var pendingInstallSystemId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        cleanupStaleInstallApks()
        store = LibraryStore(File(filesDir, "library.json"))
        accentArgb = getSharedPreferences("hub", Context.MODE_PRIVATE)
            .getLong("accent", 0xFF83F0C9L)
        customBannerPath = getSharedPreferences("hub", Context.MODE_PRIVATE).getString("banner", null)
        val storedSnapshot = runCatching { store.read() }
        snapshot = storedSnapshot.getOrElse {
            Toast.makeText(this, "Could not read library: ${it.message}", Toast.LENGTH_LONG).show()
            LibrarySnapshot()
        }
        cleanupOrphanedLibraryFiles(storedSnapshot.isSuccess)

        apkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) prepareApk(uri)
        }
        packagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importPackage(uri)
        }
        imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) savePickedImage(uri)
        }
        folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) onGameFolderPicked(uri)
        }
        storagePermissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            folderPicker.launch(null)
        }
        unknownAppsSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            installPendingApk()
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(accentArgb),
                secondary = Color(0xFF9CAEFF),
                background = Color(0xFF090D17),
                surface = Color(0xFF121A29),
                onPrimary = Color(0xFF082117),
                onSurface = Color(0xFFF2F5FC),
            )) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF090D17)) {
                    HubScreen(this)
                }
            }
        }
        handleInstallIntent(intent)
    }

    private fun cleanupStaleInstallApks() {
        val cutoff = System.currentTimeMillis() - 60L * 60L * 1000L
        cacheDir.listFiles()?.filter {
            it.isFile && it.name.startsWith("install-") && it.name.endsWith(".apk") && it.lastModified() < cutoff
        }?.forEach { deleteCachedInstallApk(this, it.absolutePath) }
    }

    private fun cleanupOrphanedLibraryFiles(snapshotLoaded: Boolean) {
        val gameDirectory = File(filesDir, "games")
        val gameIds = snapshot.entries.filter {
            it.kind == Kind.PC && it.gameDir?.let { path -> !File(path).isAbsolute } == true
        }.map { it.id }.toSet()
        gameDirectory.listFiles()?.filter {
            it.isDirectory && (it.name.endsWith(".partial") || (snapshotLoaded && it.name !in gameIds))
        }?.forEach { it.deleteRecursively() }
        if (!snapshotLoaded) return

        val coversDirectory = File(filesDir, "covers")
        val referencedCovers = snapshot.entries.mapNotNull { entry ->
            entry.cover?.let { path -> runCatching { File(path).canonicalPath }.getOrNull() }
        }.toSet()
        val nativeCoverNames = snapshot.entries.asSequence()
            .filter { it.kind == Kind.NATIVE }
            .mapNotNull { it.packageName }
            .map { "${it.replace(Regex("[^A-Za-z0-9._-]"), "_")}.png" }
            .toSet()
        coversDirectory.listFiles()?.filter { cover ->
            val canonicalPath = runCatching { cover.canonicalPath }.getOrNull()
            cover.isFile &&
                GAME_COVER_ID_PATTERN.matches(cover.nameWithoutExtension) &&
                cover.nameWithoutExtension !in gameIds &&
                cover.name !in nativeCoverNames &&
                canonicalPath !in referencedCovers
        }?.forEach { it.delete() }
    }

    override fun onResume() {
        super.onResume()
        discoverBifrostPorts()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInstallIntent(intent)
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            val center = KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags)
            return super.dispatchKeyEvent(center)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_MENU -> {
                    selected?.let { showOptionsFor = it }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> {
                    when {
                        showOptionsFor != null -> showOptionsFor = null
                        page == Page.LINK_APPS -> { page = Page.SETTINGS; installedApps = null }
                        page == Page.SETTINGS -> page = Page.HOME
                        page == Page.DETAIL -> { page = Page.HOME; selected = null }
                        else -> super.onBackPressed()
                    }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_L1 -> { cycleTab(-1); return true }
                KeyEvent.KEYCODE_BUTTON_R1 -> { cycleTab(1); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun cycleTab(direction: Int) {
        val tabs = Tab.values()
        tab = tabs[(tabs.indexOf(tab) + direction + tabs.size) % tabs.size]
    }

    private fun persist(next: LibrarySnapshot): Boolean {
        return try {
            store.write(next)
            snapshot = next
            true
        } catch (error: Exception) {
            Toast.makeText(this, "Could not save library: ${error.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun discoverBifrostPorts() {
        val dismissed = snapshot.dismissed
        val current = snapshot.entries.mapNotNull { it.packageName }.toSet()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = packageManager.queryIntentActivities(
            launcher,
            PackageManager.GET_META_DATA,
        )
        val found = resolved.mapNotNull { it.activityInfo?.applicationInfo }.distinctBy { it.packageName }
            .filter { app -> app.packageName !in current && app.packageName !in dismissed && app.metaData?.containsKey(PORT_META) == true }
        if (found.isNotEmpty()) {
            val added = found.map { app ->
                @Suppress("DEPRECATION")
                val label = packageManager.getApplicationLabel(app).toString()
                LibraryEntry(
                    id = "native-${app.packageName}",
                    kind = Kind.NATIVE,
                    title = label,
                    packageName = app.packageName,
                    cover = saveIcon(app.packageName, packageManager.getApplicationIcon(app)).absolutePath,
                    addedAt = System.currentTimeMillis(),
                )
            }
            persist(snapshot.copy(entries = snapshot.entries + added))
        }
    }

    private fun listInstalledApps(): List<InstalledApp> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = packageManager.queryIntentActivities(
            launcher,
            PackageManager.GET_META_DATA,
        )
        return resolved
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filterNot { it.packageName == packageName }
            .map { app ->
                @Suppress("DEPRECATION")
                val label = packageManager.getApplicationLabel(app).toString()
                val isGame = (Build.VERSION.SDK_INT >= 26 && app.category == ApplicationInfo.CATEGORY_GAME) ||
                    (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                val cover = runCatching { saveApplicationCover(app).absolutePath }.getOrNull()
                InstalledApp(
                    packageName = app.packageName,
                    label = label,
                    isGame = isGame,
                    isBifrostPort = app.metaData?.containsKey(PORT_META) == true,
                    cover = cover,
                )
            }
            .sortedWith(compareByDescending<InstalledApp> { it.isGame }.thenByDescending { it.isBifrostPort }.thenBy { it.label.lowercase() })
    }

    internal fun openSettings(section: SettingsSection = SettingsSection.ADD_GAMES) {
        settingsSection = section
        targetSystemId = selectedRail.removePrefix("system:").takeIf { selectedRail.startsWith("system:") }
        page = Page.SETTINGS
    }

    fun openAddMenu() = openSettings(SettingsSection.ADD_GAMES)

    fun pickApk() { apkPicker.launch(arrayOf("application/vnd.android.package-archive")) }
    fun pickPackage() { packagePicker.launch(arrayOf("*/*")) }
    fun pickGameFolder() {
        if (Build.VERSION.SDK_INT >= 23 &&
            (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
        ) {
            storagePermissionRequest.launch(arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ))
        } else {
            folderPicker.launch(null)
        }
    }
    fun showInstalledApps() {
        val apps = listInstalledApps()
        installedApps = apps
        installedGamesOnly = defaultGamesOnly(apps)
        installedSearch = ""
        selectedInstalledPackages = emptySet()
        page = Page.LINK_APPS
    }

    internal fun pickImage(action: ImageAction, targetId: String? = null) {
        imageAction = action
        imageTargetId = targetId
        imagePicker.launch("image/*")
    }

    internal fun createSystem(name: String) {
        val normalized = name.trim()
        if (normalized.isEmpty()) return
        val system = LauncherSystem(UUID.randomUUID().toString(), normalized)
        if (persist(snapshot.copy(systems = snapshot.systems + system))) {
            selectedRail = "system:${system.id}"
        }
    }

    internal fun updateSystem(system: LauncherSystem) {
        persist(snapshot.copy(systems = snapshot.systems.map { if (it.id == system.id) system else it }))
    }

    internal fun reorderSystem(systemId: String, direction: Int) {
        val systems = snapshot.systems.toMutableList()
        val index = systems.indexOfFirst { it.id == systemId }
        val target = index + direction
        if (index !in systems.indices || target !in systems.indices) return
        val item = systems.removeAt(index)
        systems.add(target, item)
        persist(snapshot.copy(systems = systems))
    }

    internal fun deleteSystem(system: LauncherSystem) {
        if (persist(snapshot.deleteSystem(system.id))) {
            system.icon?.let { File(it).delete() }
            if (selectedRail == "system:${system.id}") selectedRail = "all"
        }
    }

    internal fun updateEntry(entryId: String, transform: (LibraryEntry) -> LibraryEntry) {
        val next = snapshot.copy(entries = snapshot.entries.map { if (it.id == entryId) transform(it) else it })
        if (persist(next)) selected = next.entries.firstOrNull { it.id == selected?.id }
    }

    internal fun setAccent(color: Long) {
        accentArgb = color
        getSharedPreferences("hub", Context.MODE_PRIVATE).edit().putLong("accent", color).apply()
    }

    internal fun setControllerMode(entry: LibraryEntry, mode: ControllerMode, applyPreset: Boolean) {
        lifecycleScope.launch {
            try {
                val current = ensureControlsProfile(snapshot.currentEntry(entry))
                if (applyPreset) {
                    val profileId = current.controlsProfileId ?: throw IllegalStateException("Controls profile is missing")
                    val file = ControlsProfile.getProfileFile(this@MainActivity, profileId)
                    val existing = ControlsDocument.parse(file.readText())
                    val preset = ControlsDocument.splitControllerPreset(profileId, "Split-Controller")
                    writeAtomically(file, File(filesDir, "controls-tmp"), existing.applyPreset(preset).encode())
                }
                updateEntry(current.id) { it.copy(controllerMode = mode) }
            } catch (error: Exception) {
                message = error.message ?: "Could not change controller mode"
            }
        }
    }

    private fun savePickedImage(uri: Uri) {
        val action = imageAction ?: return
        val targetId = imageTargetId
        imageAction = null
        imageTargetId = null
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val (directory, name, dimension) = when (action) {
                        ImageAction.BANNER -> Triple(File(filesDir, "hub"), "banner.png", 512)
                        ImageAction.SYSTEM_ICON -> Triple(File(filesDir, "systems"), "$targetId.png", 256)
                        ImageAction.COVER -> Triple(File(filesDir, "covers"), "custom-$targetId.png", 512)
                    }
                    saveUploadedImage(uri, File(directory, name), dimension)
                }
                when (action) {
                    ImageAction.BANNER -> {
                        getSharedPreferences("hub", Context.MODE_PRIVATE)
                            .edit().putString("banner", file.absolutePath).apply()
                        customBannerPath = file.absolutePath
                    }
                    ImageAction.SYSTEM_ICON -> {
                        val system = snapshot.systems.firstOrNull { it.id == targetId } ?: return@launch
                        updateSystem(system.copy(icon = file.absolutePath))
                    }
                    ImageAction.COVER -> {
                        val entry = snapshot.entries.firstOrNull { it.id == targetId } ?: return@launch
                        updateEntry(entry.id) { it.copy(cover = file.absolutePath) }
                    }
                }
            } catch (error: Exception) {
                message = error.message ?: "Could not load image"
            }
        }
    }

    private fun saveUploadedImage(uri: Uri, target: File, maxDimension: Int): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not open image")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Selected file is not an image" }
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("Could not decode image")
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height))
        val resized = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else bitmap
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { resized.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (resized !== bitmap) resized.recycle()
        bitmap.recycle()
        return target
    }

    internal fun bannerPath(): String? =
        customBannerPath

    internal fun resetBanner() {
        customBannerPath = null
        getSharedPreferences("hub", Context.MODE_PRIVATE).edit().remove("banner").apply()
    }

    internal fun installRuntimeFromSettings() {
        if (runtimeInstallProgress != null) return
        runtimeInstallError = null
        runtimeInstallProgress = 0
        lifecycleScope.launch {
            try {
                WindowsRuntime.install(this@MainActivity) { progress ->
                    runOnUiThread { if (runtimeInstallProgress != null) runtimeInstallProgress = progress }
                }
                runtimeInstallError = null
                message = "Windows runtime installed"
            } catch (error: Exception) {
                runtimeInstallError = error.message ?: "Could not install Windows runtime"
            } finally {
                runtimeInstallProgress = null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun onGameFolderPicked(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val root = runCatching {
            resolvePrimaryStoragePath(
                DocumentsContract.getTreeDocumentId(uri),
                Environment.getExternalStorageDirectory(),
            )
        }.getOrNull()?.takeIf {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                it.isDirectory && it.canRead()
        }
        folderCopyChoice = root == null
        val folderName = root?.name
            ?: runCatching { DocumentFile.fromTreeUri(this, uri)?.name }.getOrNull().orEmpty()
        folderChoice = FolderSelection(uri, root, folderName.ifBlank { "Windows game" })
    }

    internal fun playFromSelectedFolder() {
        val selection = folderChoice ?: return
        val root = selection.inPlaceRoot ?: return
        folderChoice = null
        lifecycleScope.launch {
            try {
                if (':' in root.absolutePath) throw IllegalArgumentException(
                    "This folder path contains ':', which Winlator cannot map as a game drive. Choose a folder without ':' in its path.",
                )
                val scan = withContext(Dispatchers.IO) { PeScan.scan(root) }
                val id = UUID.randomUUID().toString()
                folderTitle = selection.folderName
                folderExeIndex = scan.mainIndex
                folderCandidate = FolderCandidate(
                    id,
                    root.canonicalPath,
                    root,
                    scan,
                    copied = false,
                    defaultTitle = selection.folderName,
                )
            } catch (error: Exception) {
                message = error.message ?: "Could not scan the selected game folder"
            }
        }
    }

    internal fun copySelectedFolder() {
        val selection = folderChoice ?: return
        folderChoice = null
        lifecycleScope.launch {
            importTitle = "Copying game folder"
            importBytes = 0L
            var copiedFolder: File? = null
            val id = UUID.randomUUID().toString()
            try {
                val directory = withContext(Dispatchers.IO) {
                    copyGameFolder(this@MainActivity, selection.uri, File(filesDir, "games"), id) { bytes ->
                        runOnUiThread { importBytes = bytes }
                    }
                }
                copiedFolder = directory
                val scan = withContext(Dispatchers.IO) { PeScan.scan(directory) }
                folderTitle = selection.folderName
                folderExeIndex = scan.mainIndex
                folderCandidate = FolderCandidate(
                    id,
                    id,
                    directory,
                    scan,
                    copied = true,
                    defaultTitle = selection.folderName,
                )
            } catch (error: Exception) {
                copiedFolder?.deleteRecursively()
                message = error.message ?: "Could not copy the selected game folder"
            } finally {
                importBytes = null
            }
        }
    }

    internal fun addSelectedFolder() {
        val candidate = folderCandidate ?: return
        if (folderAddInProgress) return
        if (':' in candidate.root.absolutePath) {
            message = "This folder path contains ':', which Winlator cannot map as a game drive. Choose a folder without ':' in its path."
            return
        }
        val exe = candidate.scan.executables.getOrNull(folderExeIndex) ?: return
        val title = folderTitle.trim().ifEmpty { candidate.defaultTitle }
        folderAddInProgress = true
        lifecycleScope.launch {
            try {
                val apis = withContext(Dispatchers.IO) { PeScan.detectApis(candidate.root, exe.path) }
                val entry = LibraryEntry(
                    id = candidate.id,
                    kind = Kind.PC,
                    title = title,
                    gameDir = candidate.gameDir,
                    exe = exe.path,
                    arch = exe.arch,
                    apis = apis,
                    addedAt = System.currentTimeMillis(),
                    systemId = targetSystemId,
                )
                if (persist(snapshot.copy(entries = snapshot.entries + entry))) {
                    folderCandidate = null
                    page = Page.HOME
                    selected = null
                    message = "Added ${entry.title}"
                } else {
                    if (candidate.copied) candidate.root.deleteRecursively()
                    folderCandidate = null
                }
            } catch (error: Exception) {
                message = error.message ?: "Could not scan the selected game folder"
            } finally {
                folderAddInProgress = false
            }
        }
    }

    internal fun cancelFolderCandidate() {
        folderCandidate?.takeIf { it.copied }?.root?.deleteRecursively()
        folderCandidate = null
        folderChoice = null
    }

    internal fun addInstalledApp(app: InstalledApp) = addInstalledApps(setOf(app.packageName))

    internal fun addInstalledApps(packageNames: Set<String>) {
        val apps = installedApps.orEmpty().filter { it.packageName in packageNames }
        if (apps.isEmpty()) return
        val added = apps.map { app ->
            LibraryEntry(
                id = "native-${app.packageName}",
                kind = Kind.NATIVE,
                title = app.label,
                packageName = app.packageName,
                cover = app.cover,
                addedAt = System.currentTimeMillis(),
                systemId = targetSystemId,
            )
        }
        if (persist(snapshot.copy(
            entries = snapshot.entries.filterNot { old -> added.any { it.packageName == old.packageName } } + added,
            dismissed = snapshot.dismissed - packageNames,
        ))) {
            selectedInstalledPackages = emptySet()
            page = Page.SETTINGS
            installedApps = null
            message = "Added ${added.size} app${if (added.size == 1) "" else "s"}"
        }
    }

    private fun prepareApk(uri: Uri) {
        lifecycleScope.launch {
            var newApk: File? = null
            var keepApk = false
            try {
                val apk = withContext(Dispatchers.IO) {
                    val copy = File(cacheDir, "install-${UUID.randomUUID()}.apk")
                    try {
                        val input = contentResolver.openInputStream(uri)
                            ?: throw java.io.IOException("Could not open selected APK")
                        input.use { source ->
                            copy.outputStream().use { output -> source.copyTo(output) }
                        }
                        copy
                    } catch (error: Throwable) {
                        copy.delete()
                        throw error
                    }
                }
                newApk = apk
                @Suppress("DEPRECATION")
                val packageInfo = packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
                    ?: throw IllegalArgumentException("The selected file is not a valid APK")
                val appInfo = packageInfo.applicationInfo ?: throw IllegalArgumentException("APK has no application")
                appInfo.sourceDir = apk.absolutePath
                appInfo.publicSourceDir = apk.absolutePath
                @Suppress("DEPRECATION")
                val label = packageManager.getApplicationLabel(appInfo).toString()
                val icon = packageManager.getApplicationIcon(appInfo)
                val cover = saveIcon(packageInfo.packageName, icon)
                val previousApk = pendingApk
                val previousPackage = pendingInstallPackage
                val previousLabel = pendingInstallLabel
                val previousCover = pendingInstallCover
                val previousSystemId = pendingInstallSystemId
                pendingApk = apk
                pendingInstallPackage = packageInfo.packageName
                pendingInstallLabel = label
                pendingInstallCover = cover.absolutePath
                pendingInstallSystemId = targetSystemId
                try {
                    if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                        unknownAppsSettings.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    } else {
                        installPendingApk()
                    }
                } catch (error: Throwable) {
                    pendingApk = previousApk
                    pendingInstallPackage = previousPackage
                    pendingInstallLabel = previousLabel
                    pendingInstallCover = previousCover
                    pendingInstallSystemId = previousSystemId
                    throw error
                }
                previousApk?.let { deleteCachedInstallApk(this@MainActivity, it.absolutePath) }
                keepApk = true
            } catch (error: Exception) {
                message = error.message ?: "Could not prepare APK"
            } finally {
                if (!keepApk) newApk?.let { deleteCachedInstallApk(this@MainActivity, it.absolutePath) }
            }
        }
    }

    private var pendingInstallPackage: String? = null
    private var pendingInstallLabel: String? = null
    private var pendingInstallCover: String? = null

    private fun installPendingApk() {
        val apk = pendingApk ?: return
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            message = "Allow Bifrost to install unknown apps, then choose Install APK again."
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val installer = packageManager.packageInstaller
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    val sessionId = installer.createSession(params)
                    installer.openSession(sessionId).use { session ->
                        session.openWrite("base.apk", 0, -1).use { output ->
                            apk.inputStream().use { input -> input.copyTo(output) }
                            session.fsync(output)
                        }
                        val callback = Intent(this@MainActivity, InstallStatusReceiver::class.java).apply {
                            putExtra("packageName", pendingInstallPackage)
                            putExtra("title", pendingInstallLabel)
                            putExtra("cover", pendingInstallCover)
                            putExtra("apkPath", apk.absolutePath)
                            putExtra("systemId", pendingInstallSystemId)
                        }
                        val pending = PendingIntent.getBroadcast(
                            this@MainActivity,
                            sessionId,
                            callback,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                        )
                        session.commit(pending.intentSender)
                    }
                }
                message = "APK install started. Confirm the Android install prompt."
            } catch (error: Exception) {
                deleteCachedInstallApk(this@MainActivity, apk.absolutePath)
                if (pendingApk == apk) pendingApk = null
                message = error.message ?: "APK installation failed"
            }
        }
    }

    private fun handleInstallIntent(intent: Intent?) {
        if (intent?.action != INSTALL_ACTION) return
        val packageName = intent.getStringExtra("packageName") ?: return
        val label = intent.getStringExtra("title") ?: pendingInstallLabel ?: runCatching {
            @Suppress("DEPRECATION")
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        val cover = intent.getStringExtra("cover") ?: pendingInstallCover
        val systemId = intent.getStringExtra("systemId") ?: pendingInstallSystemId
        val apkPath = intent.getStringExtra("apkPath") ?: pendingApk?.absolutePath
        val saved = persist(snapshot.copy(
            entries = snapshot.entries.filterNot { it.packageName == packageName } + LibraryEntry(
                id = "native-$packageName",
                kind = Kind.NATIVE,
                title = label,
                packageName = packageName,
                cover = cover,
                addedAt = System.currentTimeMillis(),
                systemId = systemId,
            ),
            dismissed = snapshot.dismissed - packageName,
        ))
        if (saved) deleteCachedInstallApk(this, apkPath)
        if (pendingApk?.absolutePath == apkPath) {
            pendingApk = null
            pendingInstallPackage = null
            pendingInstallLabel = null
            pendingInstallCover = null
            pendingInstallSystemId = null
        }
        message = if (saved) "Installed $label" else null
        intent.action = null
    }

    private fun importPackage(uri: Uri) {
        lifecycleScope.launch {
            importBytes = 0L
            try {
                val displayName = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                require(displayName?.endsWith(".bifrost", ignoreCase = true) == true) { "Choose a .bifrost package" }
                val id = UUID.randomUUID().toString()
                val imported = withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("Could not open selected package")
                    input.use {
                        extractBifrostPackage(it, File(filesDir, "games"), id) { bytes ->
                            runOnUiThread { importBytes = bytes }
                        }
                    }
                }
                val entry = LibraryEntry(
                    id = id,
                    kind = Kind.PC,
                    title = imported.manifest.title,
                    gameDir = imported.gameDir,
                    exe = imported.manifest.exe,
                    arch = imported.manifest.arch,
                    apis = imported.manifest.apis,
                    cover = imported.cover?.absolutePath,
                    addedAt = System.currentTimeMillis(),
                    systemId = targetSystemId,
                )
                if (persist(snapshot.copy(entries = snapshot.entries + entry))) {
                    page = Page.HOME
                    selected = null
                    message = "Imported ${entry.title}"
                } else {
                    File(filesDir, "games/${entry.id}").deleteRecursively()
                    imported.cover?.delete()
                }
            } catch (error: Exception) {
                message = error.message ?: "Could not import package"
            } finally {
                importBytes = null
            }
        }
    }

    fun selectEntry(entry: LibraryEntry) {
        selected = entry
        page = Page.HOME
    }

    fun play(entry: LibraryEntry) {
        if (entry.kind == Kind.PC) {
            lifecycleScope.launch {
                try {
                    val profiledEntry = ensureControlsProfile(entry)
                    if (WindowsRuntime.isInstalled(this@MainActivity)) {
                        launchPcEntry(profiledEntry)
                    } else {
                        pendingPcEntry = profiledEntry
                        runtimeInstallProgress = null
                        runtimeInstallError = null
                        showRuntimeInstallDialog = true
                    }
                } catch (error: Exception) {
                    message = error.message ?: "Could not prepare controls for this game"
                }
            }
            return
        }
        val launch = entry.packageName?.let(packageManager::getLaunchIntentForPackage)
        if (launch == null) {
            message = "Not installed"
            return
        }
        persist(snapshot.recordPlay(entry.id, System.currentTimeMillis()))
        startActivity(launch)
    }

    internal fun installWindowsRuntime() {
        if (runtimeInstallProgress != null) return
        val entry = pendingPcEntry ?: return
        runtimeInstallError = null
        runtimeInstallProgress = 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            try {
                WindowsRuntime.install(this@MainActivity) { progress ->
                    runOnUiThread {
                        if (runtimeInstallProgress != null) runtimeInstallProgress = progress
                    }
                }
                runtimeInstallProgress = null
                runtimeInstallError = null
                showRuntimeInstallDialog = false
                pendingPcEntry = null
                launchPcEntry(entry)
            } catch (error: Exception) {
                runtimeInstallProgress = null
                runtimeInstallError = error.message ?: "Could not install Windows runtime"
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    internal fun dismissRuntimeInstallDialog() {
        if (runtimeInstallProgress != null) return
        showRuntimeInstallDialog = false
        runtimeInstallError = null
        pendingPcEntry = null
    }

    internal fun openControls(entry: LibraryEntry) {
        lifecycleScope.launch {
            try {
                val profiledEntry = ensureControlsProfile(entry)
                startActivity(
                    Intent(this@MainActivity, ControlsEditorActivity::class.java)
                        .putExtra("entry_id", profiledEntry.id)
                        .putExtra("controls_profile_id", profiledEntry.controlsProfileId),
                )
            } catch (error: Exception) {
                message = error.message ?: "Could not open controls"
            }
        }
    }

    private fun ensureControlsProfile(entry: LibraryEntry): LibraryEntry {
        val currentEntry = snapshot.currentEntry(entry)
        if (currentEntry.kind != Kind.PC) return currentEntry
        if (currentEntry.controlsProfileId != null) {
            if (selected?.id == currentEntry.id && selected != currentEntry) selected = currentEntry
            return currentEntry
        }
        val manager = InputControlsManager(this)
        manager.getProfiles()
        val profile = manager.createProfile(currentEntry.title)
        val file = ControlsProfile.getProfileFile(this, profile.id)
        try {
            val current = if (file.isFile) ControlsDocument.parse(file.readText()) else ControlsDocument.create(profile.id, currentEntry.title)
            val gamepad = assets.open("inputcontrols/profiles/controls-3.icp").bufferedReader().use {
                ControlsDocument.parse(it.readText())
            }
            writeAtomically(file, File(filesDir, "controls-tmp"), current.applyPreset(gamepad).encode())
            val profiledEntry = currentEntry.copy(controlsProfileId = profile.id)
            val next = snapshot.copy(entries = snapshot.entries.map { if (it.id == currentEntry.id) profiledEntry else it })
            if (!persist(next)) throw IllegalStateException("Could not save controls profile for this game")
            if (selected?.id == profiledEntry.id) selected = profiledEntry
            return profiledEntry
        } catch (error: Throwable) {
            manager.removeProfile(profile)
            file.delete()
            throw error
        }
    }

    private suspend fun launchPcEntry(entry: LibraryEntry) {
        try {
            val launchEntry = ensureControlsProfile(entry)
            val gameDirPath = launchEntry.gameDir ?: throw IllegalArgumentException("Game files are missing")
            val gameDir = resolveGameDirectory(filesDir, gameDirPath)
            if (!gameDir.isDirectory) throw IllegalArgumentException("Imported game files are missing")

            val exe = launchEntry.exe ?: throw IllegalArgumentException("Game executable is missing")
            val executable = File(gameDir, exe).canonicalFile
            if (!executable.path.startsWith(gameDir.path + File.separator) || !executable.isFile) {
                throw IllegalArgumentException("Game executable is missing or invalid")
            }

            val manager = ContainerManager(this)
            val existingContainer = launchEntry.containerId?.let(manager::getContainerById)
            val createdContainer = existingContainer == null
            val container = if (existingContainer != null) {
                existingContainer
            } else {
                val drives = try {
                    containerDrives(Container.DEFAULT_DRIVES, gameDir.absolutePath)
                } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "This game folder path contains ':', which Winlator cannot map as a drive. Choose a folder without ':' in its path.",
                    )
                }
                val wrapper = when (dxwrapperFor(launchEntry.apis)) {
                    DXWrappers.VKD3D -> DXWrappers.VKD3D
                    DXWrappers.CNC_DDRAW -> DXWrappers.CNC_DDRAW
                    else -> DXWrappers.DXVK
                }
                val data = JSONObject().apply {
                    put("name", launchEntry.title)
                    put("drives", drives)
                    put("dxwrapper", wrapper)
                }
                createContainer(manager, data)
            }

            if (snapshot.entries.none { it.id == launchEntry.id }) {
                if (createdContainer) manager.removeContainerAsync(container) {}
                return
            }
            val next = snapshot.recordPlay(launchEntry.id, System.currentTimeMillis()).copy(
                entries = snapshot.entries.map {
                    if (it.id == launchEntry.id) it.copy(containerId = container.id) else it
                },
            )
            val saved = persist(next)
            if (!saved && createdContainer) {
                manager.removeContainerAsync(container) {}
                return
            }
            if (saved) selected = next.entries.firstOrNull { it.id == launchEntry.id }

            startActivity(
                Intent(this, XServerDisplayActivity::class.java)
                    .putExtra("container_id", container.id)
                    .putExtra("exec_path", executable.absolutePath)
                    .putExtra("controls_profile_id", launchEntry.controlsProfileId ?: 0)
                    .putExtra(
                        "controller_mode",
                        if (launchEntry.controllerMode == ControllerMode.SPLIT) "split" else "overlay",
                    ),
            )
        } catch (error: Exception) {
            message = error.message ?: "Could not launch PC game"
        }
    }

    private suspend fun createContainer(manager: ContainerManager, data: JSONObject): Container =
        suspendCancellableCoroutine { continuation ->
            manager.createContainerAsync(data) { container ->
                if (!continuation.isActive) {
                    container?.let { manager.removeContainerAsync(it) {} }
                } else if (container == null) {
                    continuation.resumeWith(Result.failure(IllegalStateException("Could not create Winlator container")))
                } else {
                    continuation.resumeWith(Result.success(container))
                }
            }
        }

    fun requestRemove(entry: LibraryEntry) { confirmRemove = entry }

    fun removeEntry(entry: LibraryEntry, uninstall: Boolean = false) {
        val updated = snapshot.copy(
            entries = snapshot.entries.filterNot { it.id == entry.id },
            dismissed = if (entry.kind == Kind.NATIVE && entry.packageName != null) snapshot.dismissed + entry.packageName else snapshot.dismissed,
        )
        if (!persist(updated)) return
        if (entry.kind == Kind.PC) {
            entry.containerId?.let { containerId ->
                runCatching {
                    val manager = ContainerManager(this)
                    manager.getContainerById(containerId)?.let { manager.removeContainerAsync(it) {} }
                }
            }
            runCatching {
                val manager = InputControlsManager(this)
                manager.getProfiles()
                entry.controlsProfileId?.let(manager::getProfile)?.let(manager::removeProfile)
            }
            if (entry.gameDir == null || !File(entry.gameDir).isAbsolute) {
                File(filesDir, "games/${entry.id}").deleteRecursively()
            }
            entry.cover?.let { File(it).delete() }
        }
        confirmRemove = null
        selected = null
        page = Page.HOME
        if (uninstall && entry.packageName != null) {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${entry.packageName}")))
        }
    }

    private fun saveIcon(name: String, drawable: Drawable): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(File(filesDir, "covers").apply { mkdirs() }, "$safe.png")
        return saveDrawable(target, drawable, 256)
    }

    private fun saveApplicationCover(app: ApplicationInfo): File {
        val safe = app.packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(File(filesDir, "covers").apply { mkdirs() }, "$safe.png")
        val banner = runCatching { packageManager.getApplicationBanner(app) }.getOrNull()
        return saveDrawable(target, banner ?: packageManager.getApplicationIcon(app), 512)
    }

    private fun saveDrawable(target: File, drawable: Drawable, maxDimension: Int): File {
        val source = if (drawable is BitmapDrawable) drawable.bitmap else {
            val width = drawable.intrinsicWidth.coerceIn(1, maxDimension)
            val height = drawable.intrinsicHeight.coerceIn(1, maxDimension)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(canvas)
            }
        }
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(source.width, source.height))
        val result = if (scale < 1f) Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        ) else source
        FileOutputStream(target).use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (result !== source) result.recycle()
        if (drawable !is BitmapDrawable) source.recycle()
        return target
    }
}

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirmation)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                val result = Intent(context, MainActivity::class.java).apply {
                    action = INSTALL_ACTION
                    putExtra("packageName", intent.getStringExtra("packageName"))
                    putExtra("title", intent.getStringExtra("title"))
                    putExtra("cover", intent.getStringExtra("cover"))
                    putExtra("apkPath", intent.getStringExtra("apkPath"))
                    putExtra("systemId", intent.getStringExtra("systemId"))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(result)
            }
            else -> {
                deleteCachedInstallApk(context, intent.getStringExtra("apkPath"))
                Toast.makeText(context, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "APK installation failed", Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
private fun LauncherScreen(activity: MainActivity) {
    val entries = activity.snapshot.entries
    val firstFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val filtered = entries.filter {
        when (activity.tab) {
            Tab.ALL -> true
            Tab.NATIVE -> it.kind == Kind.NATIVE
            Tab.PC -> it.kind == Kind.PC
        }
    }.sortedBy { it.title.lowercase() }

    LaunchedEffect(activity.page, activity.tab, filtered.size, activity.selected?.id) {
        kotlinx.coroutines.delay(80)
        if (activity.page == Page.DETAIL) playFocus.requestFocus()
        else if (filtered.isEmpty()) addFocus.requestFocus()
        else firstFocus.requestFocus()
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF090D17))) {
        if (activity.page == Page.DETAIL) {
            val cover = remember(activity.selected?.cover) { activity.selected?.cover?.let(BitmapFactoryBridge::decode) }
            cover?.let {
                Image(it, null, Modifier.fillMaxSize().blur(32.dp), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color(0xDD090D17)))
            }
            DetailScreen(activity, activity.selected, playFocus)
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("BIFROST", fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp, color = Color.White)
                    Spacer(Modifier.width(30.dp))
                    Tab.values().forEach { item ->
                        val selected = activity.tab == item
                        Text(
                            if (item.name == "PC") "PC" else item.name.lowercase().replaceFirstChar { it.uppercase() },
                            Modifier.padding(horizontal = 6.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (selected) Color(0xFF203A44) else Color.Transparent)
                        .clickable { activity.tab = item }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            color = if (selected) Color(0xFF83F0C9) else Color(0xFFB5C0D3),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = activity::openAddMenu,
                        modifier = Modifier.focusRequester(addFocus),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF83F0C9)),
                    ) { Text("+  Add", fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(14.dp))
                val recent = filtered.filter { it.lastPlayed != null }.sortedByDescending { it.lastPlayed }.take(8)
                if (recent.isNotEmpty()) {
                    Text("RECENTLY PLAYED", color = Color(0xFF93A0B6), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(98.dp)) {
                        items(recent.size) { index ->
                            val entry = recent[index]
                            SmallRecentCard(entry) { activity.selectEntry(entry) }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                Text("LIBRARY", color = Color(0xFF93A0B6), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(8.dp))
                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (entries.isEmpty()) "Your library is waiting." else "No games in this collection.", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text("Add an Android app or import a .bifrost game package.", color = Color(0xFF9CA8BA))
                            Spacer(Modifier.height(20.dp))
                            Button(
                                onClick = activity::openAddMenu,
                                contentPadding = PaddingValues(horizontal = 36.dp, vertical = 18.dp),
                            ) { Text("Add a game", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 132.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(filtered, key = { it.id }) { entry ->
                            CoverCard(entry, Modifier.then(if (entry == filtered.first()) Modifier.focusRequester(firstFocus) else Modifier)) {
                                activity.selectEntry(entry)
                            }
                        }
                    }
                }
            }
        }
    }

    if (activity.showAddMenu) {
        AlertDialog(
            onDismissRequest = { activity.showAddMenu = false },
            title = { Text("Add to Bifrost") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AddAction("Install APK…", "Choose an APK and confirm Android's install prompt", activity::pickApk)
                    AddAction("Add installed app…", "Browse apps already installed on this device", activity::showInstalledApps)
                    AddAction("Import PC game (.bifrost)…", "Import a package created on Windows by Bifrost", activity::pickPackage)
                    AddAction("Add game folder", "A Windows game already on this phone", activity::pickGameFolder)
                }
            },
            confirmButton = { TextButton(onClick = { activity.showAddMenu = false }) { Text("Close") } },
        )
    }

    activity.installedApps?.let { apps ->
        AlertDialog(
            onDismissRequest = { activity.installedApps = null },
            title = { Text("Add installed app") },
            text = {
                Column(Modifier.fillMaxWidth().height(340.dp)) {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(apps.size) { index ->
                            val app = apps[index]
                            Text(
                                app.label + if (app.isBifrostPort) "  •  Bifrost port" else "",
                                Modifier.fillMaxWidth().clickable { activity.addInstalledApp(app) }.padding(12.dp),
                                color = Color.White,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { activity.installedApps = null }) { Text("Close") } },
        )
    }

    activity.confirmRemove?.let { entry ->
        AlertDialog(
            onDismissRequest = { activity.confirmRemove = null },
            title = { Text("Remove ${entry.title}?") },
            text = {
                Text(
                    when {
                        entry.kind != Kind.PC -> "Remove this app from the Bifrost library?"
                        entry.gameDir?.let { File(it).isAbsolute } == true ->
                            "Remove this game from the Bifrost library. Its folder and files on this phone will be left untouched."
                        else -> "This removes the game from the library and deletes its copied files from Bifrost."
                    },
                )
            },
            confirmButton = {
                if (entry.kind == Kind.NATIVE) {
                    TextButton(onClick = { activity.removeEntry(entry, uninstall = true) }) { Text("Uninstall app") }
                } else {
                    TextButton(onClick = { activity.removeEntry(entry) }) {
                        Text(if (entry.gameDir?.let { File(it).isAbsolute } == true) "Remove from library" else "Delete files")
                    }
                }
            },
            dismissButton = {
                Row {
                    if (entry.kind == Kind.NATIVE) TextButton(onClick = { activity.removeEntry(entry) }) { Text("Remove from library") }
                    TextButton(onClick = { activity.confirmRemove = null }) { Text("Cancel") }
                }
            },
        )
    }

    if (activity.showRuntimeInstallDialog) {
        val progress = activity.runtimeInstallProgress
        val error = activity.runtimeInstallError
        AlertDialog(
            onDismissRequest = activity::dismissRuntimeInstallDialog,
            title = { Text("Install Windows runtime") },
            text = {
                Text(
                    when {
                        progress != null -> "Installing Windows runtime: $progress%"
                        error != null -> "Could not install the Windows runtime: $error"
                        else -> "Install the bundled Windows runtime once to launch PC games."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = activity::installWindowsRuntime,
                    enabled = progress == null,
                ) {
                    Text(if (progress != null) "Installing…" else if (error != null) "Retry" else "Install")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = activity::dismissRuntimeInstallDialog,
                    enabled = progress == null,
                ) { Text("Not now") }
            },
        )
    }

    activity.importBytes?.let { bytes ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(activity.importTitle) },
            text = { Text("Copied $bytes bytes") },
            confirmButton = {},
        )
    }

    activity.folderChoice?.let { choice ->
        AlertDialog(
            onDismissRequest = activity::cancelFolderCandidate,
            title = { Text("Add Windows game folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose how to add this game.")
                    if (choice.inPlaceRoot != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = !activity.folderCopyChoice,
                                onClick = { activity.folderCopyChoice = false },
                            )
                            Column {
                                Text("Play from this folder", color = Color.White)
                                Text("Files stay where they are.", color = Color(0xFF9CA8BA), fontSize = 12.sp)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = activity.folderCopyChoice,
                            onClick = { activity.folderCopyChoice = true },
                        )
                        Column {
                            Text("Copy into Bifrost", color = Color.White)
                            Text("Uses internal storage; the original is unchanged.", color = Color(0xFF9CA8BA), fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (activity.folderCopyChoice) activity.copySelectedFolder()
                        else activity.playFromSelectedFolder()
                    },
                ) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = activity::cancelFolderCandidate) { Text("Cancel") } },
        )
    }

    activity.folderCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = {
                if (!activity.folderAddInProgress) activity.cancelFolderCandidate()
            },
            title = { Text("Confirm Windows game") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = activity.folderTitle,
                        onValueChange = { activity.folderTitle = it },
                        label = { Text("Title") },
                        singleLine = true,
                    )
                    Text("Game .exe", color = Color(0xFF9CA8BA), fontWeight = FontWeight.SemiBold)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                        items(candidate.scan.executables.size) { index ->
                            val executable = candidate.scan.executables[index]
                            Row(
                                Modifier.fillMaxWidth().clickable { activity.folderExeIndex = index }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = activity.folderExeIndex == index,
                                    onClick = { activity.folderExeIndex = index },
                                )
                                Text(executable.path, color = Color.White, maxLines = 1)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = activity::addSelectedFolder, enabled = !activity.folderAddInProgress) {
                    Text(if (activity.folderAddInProgress) "Adding…" else "Add")
                }
            },
            dismissButton = {
                TextButton(onClick = activity::cancelFolderCandidate, enabled = !activity.folderAddInProgress) { Text("Cancel") }
            },
        )
    }

    activity.message?.let { text ->
        AlertDialog(
            onDismissRequest = { activity.message = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { activity.message = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun AddAction(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp),
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Color(0xFF9CA8BA), fontSize = 12.sp)
    }
}

@Composable
private fun SmallRecentCard(entry: LibraryEntry, onClick: () -> Unit) {
    val bitmap = remember(entry.cover) { entry.cover?.let(BitmapFactoryBridge::decode) }
    Card(
        Modifier.width(180.dp).fillMaxHeight().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF162032)),
        border = BorderStroke(1.dp, Color(0x334D6577)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
            if (bitmap != null) Image(bitmap, null, Modifier.size(58.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            else PlaceholderCover(entry, Modifier.size(58.dp).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(entry.title, maxLines = 2, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(if (entry.kind == Kind.NATIVE) "NATIVE" else "PC GAME", color = Color(0xFF83F0C9), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun CoverCard(entry: LibraryEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember(entry.id) { mutableStateOf(false) }
    val bitmap = remember(entry.cover) { entry.cover?.let(BitmapFactoryBridge::decode) }
    Column(
        modifier = modifier.graphicsLayer { val scale = if (focused) 1.08f else 1f; scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color(0xFF83F0C9) else Color(0x334D6577), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp)),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f)) {
            if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else PlaceholderCover(entry, Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE8000000))))
                    .padding(12.dp),
            ) {
                Column {
                    Text(entry.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text(if (entry.kind == Kind.NATIVE) "NATIVE" else "PC GAME", color = Color(0xFF83F0C9), fontSize = 10.sp, letterSpacing = 1.sp)
                }
            }
        }
    }
}

@Composable
private fun PlaceholderCover(entry: LibraryEntry, modifier: Modifier = Modifier) {
    val colors = remember(entry.id) {
        val hue = (entry.id.hashCode().toLong().absoluteValue % 360).toFloat()
        listOf(Color.hsv(hue, 0.62f, 0.48f), Color.hsv((hue + 42) % 360, 0.7f, 0.22f))
    }
    Box(modifier.background(Brush.linearGradient(colors))) {
        Text(entry.title.take(1).uppercase(), Modifier.align(Alignment.Center), color = Color(0xAAFFFFFF), fontSize = 52.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun DetailScreen(activity: MainActivity, entry: LibraryEntry?, playFocus: FocusRequester) {
    if (entry == null) return
    val bitmap = remember(entry.cover) { entry.cover?.let(BitmapFactoryBridge::decode) }
    Row(Modifier.fillMaxSize().padding(32.dp), verticalAlignment = Alignment.CenterVertically) {
        if (bitmap != null) {
            Image(bitmap, null, Modifier.fillMaxHeight().weight(0.48f).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop)
        } else {
            PlaceholderCover(entry, Modifier.fillMaxHeight().weight(0.48f).clip(RoundedCornerShape(18.dp)))
        }
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(0.52f)) {
            Text(entry.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(if (entry.kind == Kind.NATIVE) "NATIVE APP" else "PC GAME", color = Color(0xFF83F0C9), letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
            if (entry.kind == Kind.PC) {
                Spacer(Modifier.height(8.dp))
                Text("${entry.arch ?: "Unknown architecture"}  •  ${entry.apis.joinToString().ifEmpty { "No APIs detected" }}", color = Color(0xFFACB7C8))
            }
            Spacer(Modifier.height(28.dp))
            Button(onClick = { activity.play(entry) }, modifier = Modifier.focusRequester(playFocus).width(190.dp)) { Text("Play") }
            if (entry.kind == Kind.PC) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { activity.openControls(entry) }, modifier = Modifier.width(190.dp)) { Text("Controls") }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { activity.requestRemove(entry) }, modifier = Modifier.width(190.dp)) { Text("Remove") }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { activity.page = Page.HOME; activity.selected = null }) { Text("Back") }
        }
    }
}

private object BitmapFactoryBridge {
    fun decode(path: String) = android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap()
}
