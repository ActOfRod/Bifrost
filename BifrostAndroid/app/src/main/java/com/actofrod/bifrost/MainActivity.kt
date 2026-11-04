package com.actofrod.bifrost

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PORT_META = "com.actofrod.bifrost.PORT"
private const val INSTALL_ACTION = "com.actofrod.bifrost.INSTALL_RESULT"

internal enum class Page { HOME, DETAIL }
internal enum class Tab { ALL, NATIVE, PC }

internal data class InstalledApp(val packageName: String, val label: String, val isBifrostPort: Boolean)

class MainActivity : ComponentActivity() {
    private lateinit var store: LibraryStore
    private lateinit var apkPicker: ActivityResultLauncher<Array<String>>
    private lateinit var packagePicker: ActivityResultLauncher<Array<String>>
    private lateinit var unknownAppsSettings: ActivityResultLauncher<Intent>
    internal var snapshot by mutableStateOf(LibrarySnapshot())
    internal var page by mutableStateOf(Page.HOME)
    internal var tab by mutableStateOf(Tab.ALL)
    internal var selected by mutableStateOf<LibraryEntry?>(null)
    internal var showAddMenu by mutableStateOf(false)
    internal var installedApps by mutableStateOf<List<InstalledApp>?>(null)
    internal var confirmRemove by mutableStateOf<LibraryEntry?>(null)
    internal var message by mutableStateOf<String?>(null)
    internal var importBytes by mutableStateOf<Long?>(null)
    private var pendingApk by mutableStateOf<File?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        store = LibraryStore(File(filesDir, "library.json"))
        snapshot = runCatching { store.read() }.getOrElse {
            Toast.makeText(this, "Could not read library: ${it.message}", Toast.LENGTH_LONG).show()
            LibrarySnapshot()
        }

        apkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) prepareApk(uri)
        }
        packagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importPackage(uri)
        }
        unknownAppsSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            installPendingApk()
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF83F0C9),
                secondary = Color(0xFF9CAEFF),
                background = Color(0xFF090D17),
                surface = Color(0xFF121A29),
                onPrimary = Color(0xFF082117),
                onSurface = Color(0xFFF2F5FC),
            )) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF090D17)) {
                    LauncherScreen(this)
                }
            }
        }
        handleInstallIntent(intent)
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            val center = KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags)
            return super.dispatchKeyEvent(center)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_Y -> {
                    showAddMenu = true
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> {
                    if (showAddMenu) showAddMenu = false
                    else if (page == Page.DETAIL) { page = Page.HOME; selected = null }
                    else if (installedApps != null) installedApps = null
                    else super.onBackPressed()
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

    private fun persist(next: LibrarySnapshot) {
        snapshot = next
        runCatching { store.write(next) }.onFailure {
            Toast.makeText(this, "Could not save library: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun discoverBifrostPorts() {
        val dismissed = snapshot.dismissed
        val current = snapshot.entries.mapNotNull { it.packageName }.toSet()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = packageManager.queryIntentActivities(
            launcher,
            PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_META_DATA,
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
        return packageManager.queryIntentActivities(
            launcher,
            PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_META_DATA,
        )
            .mapNotNull { result ->
                val info = result.activityInfo ?: return@mapNotNull null
                if (info.packageName == packageName) return@mapNotNull null
                val app = info.applicationInfo
                InstalledApp(info.packageName, result.loadLabel(packageManager).toString(), app.metaData?.containsKey(PORT_META) == true)
            }
            .distinctBy { it.packageName }
            .sortedWith(compareByDescending<InstalledApp> { it.isBifrostPort }.thenBy { it.label.lowercase() })
    }

    fun openAddMenu() { showAddMenu = true }
    fun pickApk() { showAddMenu = false; apkPicker.launch(arrayOf("application/vnd.android.package-archive")) }
    fun pickPackage() { showAddMenu = false; packagePicker.launch(arrayOf("*/*")) }
    fun showInstalledApps() { showAddMenu = false; installedApps = listInstalledApps() }

    fun addInstalledApp(app: InstalledApp) {
        val icon = runCatching { packageManager.getApplicationIcon(app.packageName) }.getOrNull()
        val entry = LibraryEntry(
            id = "native-${app.packageName}",
            kind = Kind.NATIVE,
            title = app.label,
            packageName = app.packageName,
            cover = icon?.let { saveIcon(app.packageName, it).absolutePath },
            addedAt = System.currentTimeMillis(),
        )
        persist(snapshot.copy(
            entries = (snapshot.entries.filterNot { it.packageName == app.packageName } + entry),
            dismissed = snapshot.dismissed - app.packageName,
        ))
        installedApps = null
    }

    private fun prepareApk(uri: Uri) {
        lifecycleScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    val copy = File(cacheDir, "install-${UUID.randomUUID()}.apk")
                    contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Could not open selected APK" }
                        copy.outputStream().use { output -> input.copyTo(output) }
                    }
                    copy
                }
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
                pendingApk = apk
                pendingInstallPackage = packageInfo.packageName
                pendingInstallLabel = label
                pendingInstallCover = cover.absolutePath
                if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                    unknownAppsSettings.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                } else {
                    installPendingApk()
                }
            } catch (error: Exception) {
                message = error.message ?: "Could not prepare APK"
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
        persist(snapshot.copy(
            entries = snapshot.entries.filterNot { it.packageName == packageName } + LibraryEntry(
                id = "native-$packageName",
                kind = Kind.NATIVE,
                title = label,
                packageName = packageName,
                cover = cover,
                addedAt = System.currentTimeMillis(),
            ),
            dismissed = snapshot.dismissed - packageName,
        ))
        pendingApk?.delete()
        pendingApk = null
        pendingInstallPackage = null
        pendingInstallLabel = null
        pendingInstallCover = null
        message = "Installed $label"
        intent.action = null
    }

    private fun importPackage(uri: Uri) {
        lifecycleScope.launch {
            importBytes = 0L
            var source: File? = null
            try {
                val displayName = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                require(displayName?.endsWith(".bifrost", ignoreCase = true) == true) { "Choose a .bifrost package" }
                val packageFile = withContext(Dispatchers.IO) {
                    val file = File(cacheDir, "import-${UUID.randomUUID()}.bifrost")
                    contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Could not open selected package" }
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    file
                }
                source = packageFile
                val id = UUID.randomUUID().toString()
                val imported = withContext(Dispatchers.IO) {
                    extractBifrostPackage(packageFile, File(filesDir, "games"), id) { bytes ->
                        runOnUiThread { importBytes = bytes }
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
                )
                persist(snapshot.copy(entries = snapshot.entries + entry))
                page = Page.HOME
                selected = null
                message = "Imported ${entry.title}"
                packageFile.delete()
            } catch (error: Exception) {
                message = error.message ?: "Could not import package"
            } finally {
                source?.delete()
                importBytes = null
            }
        }
    }

    fun selectEntry(entry: LibraryEntry) {
        selected = entry
        page = Page.DETAIL
    }

    fun play(entry: LibraryEntry) {
        if (entry.kind == Kind.PC) {
            message = "Running PC games arrives in the next Bifrost update (Windows runtime). The game is imported and ready."
            return
        }
        val launch = entry.packageName?.let(packageManager::getLaunchIntentForPackage)
        if (launch == null) {
            message = "Not installed"
            return
        }
        persist(snapshot.copy(entries = snapshot.entries.map {
            if (it.id == entry.id) it.copy(lastPlayed = System.currentTimeMillis()) else it
        }))
        startActivity(launch)
    }

    fun requestRemove(entry: LibraryEntry) { confirmRemove = entry }

    fun removeEntry(entry: LibraryEntry, uninstall: Boolean = false) {
        val updated = snapshot.copy(
            entries = snapshot.entries.filterNot { it.id == entry.id },
            dismissed = if (entry.kind == Kind.NATIVE && entry.packageName != null) snapshot.dismissed + entry.packageName else snapshot.dismissed,
        )
        persist(updated)
        if (entry.kind == Kind.PC) {
            File(filesDir, "games/${entry.id}").deleteRecursively()
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
        val bitmap = if (drawable is BitmapDrawable) drawable.bitmap else {
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        }
        FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(result)
            }
            else -> Toast.makeText(context, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "APK installation failed", Toast.LENGTH_LONG).show()
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
                            item.name.lowercase().replaceFirstChar { it.uppercase() },
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddAction("Install APK…", "Choose an APK and confirm Android's install prompt", activity::pickApk)
                    AddAction("Add installed app…", "Browse apps already installed on this device", activity::showInstalledApps)
                    AddAction("Import PC game (.bifrost)…", "Import a package created on Windows by Bifrost", activity::pickPackage)
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
            text = { Text(if (entry.kind == Kind.PC) "This deletes the imported game files from Bifrost." else "Remove this app from the Bifrost library?") },
            confirmButton = {
                if (entry.kind == Kind.NATIVE) {
                    TextButton(onClick = { activity.removeEntry(entry, uninstall = true) }) { Text("Uninstall app") }
                } else {
                    TextButton(onClick = { activity.removeEntry(entry) }) { Text("Delete files") }
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

    activity.importBytes?.let { bytes ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Importing game") },
            text = { Text("Copied $bytes bytes") },
            confirmButton = {},
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
