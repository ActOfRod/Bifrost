package com.actofrod.bifrost

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.DateFormat
import java.util.Date
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

private val HubBackground = Color(0xFF070A12)
private val HubMuted = Color(0xFFA6B1C2)
private val HubAccent: Color
    @Composable get() = MaterialTheme.colorScheme.primary

@Composable
internal fun HubScreen(activity: MainActivity) {
    BackHandler(
        enabled = activity.page != Page.HOME || activity.showOptionsFor != null ||
            activity.showAddWindowsGameChooser || activity.showUnresolvedExeDialog ||
            activity.packageTypeDialogMessage != null,
    ) {
        when {
            activity.showUnresolvedExeDialog -> activity.showUnresolvedExeDialog = false
            activity.showAddWindowsGameChooser -> activity.showAddWindowsGameChooser = false
            activity.packageTypeDialogMessage != null -> activity.packageTypeDialogMessage = null
            activity.folderCandidate != null && !activity.folderAddInProgress -> activity.cancelFolderCandidate()
            activity.folderChoice != null -> activity.cancelFolderCandidate()
            activity.showOptionsFor != null -> activity.showOptionsFor = null
            activity.page == Page.LINK_APPS -> {
                activity.page = activity.installedAppsReturnPage
                activity.installedApps = null
            }
            activity.page == Page.SETTINGS -> {
                if (activity.settingsDetailOpen != null) activity.settingsDetailOpen = null
                else activity.page = Page.HOME
            }
            activity.page == Page.DETAIL -> {
                activity.page = Page.HOME
                activity.selected = null
            }
            else -> activity.page = Page.HOME
        }
    }
    val page = activity.page
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 24 }) togetherWith
                (fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { -it / 24 })
        },
        label = "hub-page",
    ) { target ->
        when (target) {
            Page.SETTINGS -> SettingsScreen(activity)
            Page.LINK_APPS -> InstalledAppsScreen(activity)
            else -> GameHubScreen(activity)
        }
    }
    activity.showOptionsFor?.let { entry ->
        EntryOptionsPanel(activity, activity.snapshot.currentEntry(entry))
    }
    if (activity.showRuntimeInstallDialog) {
        AlertDialog(
            onDismissRequest = { activity.dismissRuntimeInstallDialog() },
            title = { Text("Windows runtime") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(activity.runtimeInstallError ?: "Install or repair the Windows runtime before launching this PC game?")
                    activity.runtimeInstallProgress?.let {
                        Spacer(Modifier.height(14.dp))
                        CircularProgressIndicator(progress = { it / 100f })
                        Text("Installing… $it%", color = HubMuted)
                    }
                }
            },
            confirmButton = {
                if (activity.runtimeInstallProgress == null) {
                    TextButton(onClick = activity::installWindowsRuntime) {
                        Text(if (activity.runtimeInstallError == null) "Install" else "Retry")
                    }
                }
            },
            dismissButton = {
                if (activity.runtimeInstallProgress == null) {
                    TextButton(onClick = activity::dismissRuntimeInstallDialog) { Text("Cancel") }
                }
            },
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
                        entry.gameDir?.let { java.io.File(it).isAbsolute } == true ->
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
                        Text(if (entry.gameDir?.let { java.io.File(it).isAbsolute } == true) "Remove from library" else "Delete files")
                    }
                }
            },
            dismissButton = {
                Row {
                    if (entry.kind == Kind.NATIVE) {
                        TextButton(onClick = { activity.removeEntry(entry) }) { Text("Remove from library") }
                    }
                    TextButton(onClick = { activity.confirmRemove = null }) { Text("Cancel") }
                }
            },
        )
    }
    activity.message?.let { message ->
        AlertDialog(
            onDismissRequest = { activity.message = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { activity.message = null }) { Text("OK") } },
        )
    }
    if (activity.showAddWindowsGameChooser) {
        AlertDialog(
            onDismissRequest = { activity.showAddWindowsGameChooser = false },
            title = { Text("Add Windows game") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose the game's executable or its folder.")
                    OutlinedButton(
                        onClick = activity::pickGameExe,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Choose the .exe") }
                    OutlinedButton(
                        onClick = activity::pickGameFolder,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Choose the folder") }
                }
            },
            confirmButton = {
                TextButton(onClick = { activity.showAddWindowsGameChooser = false }) { Text("Cancel") }
            },
        )
    }
    if (activity.showUnresolvedExeDialog) {
        AlertDialog(
            onDismissRequest = { activity.showUnresolvedExeDialog = false },
            title = { Text("Choose a game folder instead") },
            text = {
                Text("Bifrost can't see where this file is stored. Open the picker's menu, choose your phone's storage (e.g. 'Galaxy Z Fold7') and browse to the game, instead of Recent or Downloads.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        activity.showUnresolvedExeDialog = false
                        activity.pickGameFolder()
                    },
                ) { Text("Choose folder instead") }
            },
            dismissButton = {
                TextButton(onClick = { activity.showUnresolvedExeDialog = false }) { Text("Cancel") }
            },
        )
    }
    activity.packageTypeDialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { activity.packageTypeDialogMessage = null },
            title = { Text("Import .bifrost package") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { activity.packageTypeDialogMessage = null }) { Text("OK") }
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
                    if (choice.inPlaceRoot == null) {
                        Text(
                            "This folder's location can't be read directly by Bifrost, so it must be copied into Bifrost storage.",
                            color = HubMuted,
                            fontSize = 12.sp,
                        )
                        choice.folderSizeBytes?.let {
                            Text("Approx. folder size: ${formatStorageBytes(it)}", color = HubMuted, fontSize = 12.sp)
                        }
                        choice.availableBytes?.let {
                            Text("Available internal storage: ${formatStorageBytes(it)}", color = HubMuted, fontSize = 12.sp)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = !activity.folderCopyChoice,
                                onClick = { activity.folderCopyChoice = false },
                            )
                            Column {
                                Text("Play from this folder", color = Color.White)
                                Text("Files stay where they are.", color = HubMuted, fontSize = 12.sp)
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
                            Text("Uses internal storage; the original is unchanged.", color = HubMuted, fontSize = 12.sp)
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
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        OutlinedTextField(
                            value = activity.folderTitle,
                            onValueChange = { activity.folderTitle = it },
                            label = { Text("Title") },
                            singleLine = true,
                        )
                    }
                    if (candidate.rootCandidates.size > 1) {
                        item {
                            Text("Game folder", color = HubMuted, fontWeight = FontWeight.SemiBold)
                        }
                        items(candidate.rootCandidates.size) { index ->
                            val root = candidate.rootCandidates[index]
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = !activity.folderRootScanInProgress) { activity.selectGameRoot(root) }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = candidate.root == root,
                                    onClick = { activity.selectGameRoot(root) },
                                    enabled = !activity.folderRootScanInProgress,
                                )
                                Text(
                                    gameRootLabel(root, candidate.rootCandidates.first()),
                                    Modifier.weight(1f),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        item {
                            Text(
                                "Pick the folder that holds the whole game.",
                                color = HubMuted,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    item {
                        Text("Game .exe", color = HubMuted, fontWeight = FontWeight.SemiBold)
                    }
                    items(candidate.scan.executables.size) { index ->
                        val executable = candidate.scan.executables[index]
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = !activity.folderRootScanInProgress) { activity.folderExeIndex = index }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = activity.folderExeIndex == index,
                                onClick = { activity.folderExeIndex = index },
                                enabled = !activity.folderRootScanInProgress,
                            )
                            Text(
                                executable.path,
                                Modifier.weight(1f),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = activity::addSelectedFolder,
                    enabled = !activity.folderAddInProgress && !activity.folderRootScanInProgress,
                ) {
                    Text(if (activity.folderAddInProgress) "Adding…" else "Add")
                }
            },
            dismissButton = {
                TextButton(onClick = activity::cancelFolderCandidate, enabled = !activity.folderAddInProgress) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameHubScreen(activity: MainActivity) {
    val configuration = LocalConfiguration.current
    val compactLayout = configuration.screenHeightDp < 500
    val allEntries = activity.snapshot.entries
    val systems = activity.snapshot.systems
    val baseEntries = when {
        activity.selectedRail == "recent" -> allEntries.filter { it.lastPlayed != null }.sortedByDescending { it.lastPlayed }
        activity.selectedRail == "native" -> allEntries.filter { it.kind == Kind.NATIVE }
        activity.selectedRail == "pc" -> allEntries.filter { it.kind == Kind.PC }
        activity.selectedRail.startsWith("system:") -> allEntries.filter {
            it.systemId == activity.selectedRail.removePrefix("system:")
        }
        else -> allEntries
    }
    val query = activity.searchQuery.trim()
    val visible = baseEntries.filter { entry ->
        (activity.tab == Tab.ALL || (activity.tab == Tab.NATIVE && entry.kind == Kind.NATIVE) ||
            (activity.tab == Tab.PC && entry.kind == Kind.PC)) &&
            (query.isEmpty() || entry.title.contains(query, ignoreCase = true))
    }.sortedBy { it.title.lowercase() }
    val selected = activity.selected?.let { activity.snapshot.currentEntry(it) }
    val mostPlayed = topPlayedGames(allEntries)
    val headerItemCount = 2 + if (mostPlayed.isEmpty()) 0 else 1
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    LaunchedEffect(activity.focusEntryId, activity.message) {
        if (activity.message != null) return@LaunchedEffect
        val requestedId = activity.focusEntryId ?: return@LaunchedEffect
        val index = visible.indexOfFirst { it.id == requestedId }
        if (index < 0) return@LaunchedEffect
        gridState.scrollToItem(headerItemCount + index)
        repeat(3) { withFrameNanos { } }
        focusRequesters[requestedId]?.requestFocus()
        activity.focusEntryId = null
    }
    val heroHeight = if (compactLayout) {
        (configuration.screenHeightDp.dp * 0.28f).coerceIn(112.dp, 176.dp)
    } else {
        (configuration.screenHeightDp.dp * 0.32f).coerceIn(180.dp, 290.dp)
    }
    Row(Modifier.fillMaxSize().background(HubBackground)) {
        HubRail(activity, systems, Modifier.fillMaxHeight().width(82.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 128.dp),
            state = gridState,
            modifier = Modifier.weight(1f).fillMaxHeight().padding(
                end = 20.dp,
                top = if (compactLayout) 8.dp else 14.dp,
                bottom = if (compactLayout) 6.dp else 10.dp,
            ),
            contentPadding = PaddingValues(bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                HeroBanner(activity, selected, heroHeight)
            }
            if (mostPlayed.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text(
                            "MOST PLAYED",
                            Modifier.padding(start = 4.dp, bottom = if (compactLayout) 3.dp else 2.dp),
                            color = HubMuted,
                            fontSize = if (compactLayout) 10.sp else 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp,
                        )
                        Row(
                            Modifier.fillMaxWidth().height(if (compactLayout) 64.dp else 86.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            mostPlayed.forEachIndexed { index, entry ->
                                TopPlayedCard(
                                    entry = entry,
                                    rank = index + 1,
                                    compact = compactLayout,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    onClick = { activity.selectEntry(entry) },
                                )
                            }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                HubToolbar(
                    activity = activity,
                    narrow = configuration.screenWidthDp < 600,
                    compact = compactLayout,
                )
            }
            if (visible.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 170.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (allEntries.isEmpty()) "Your library is ready for its first game."
                                else "Nothing in this collection yet.",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { activity.openSettings(SettingsSection.ADD_GAMES) },
                                colors = ButtonDefaults.buttonColors(containerColor = HubAccent),
                            ) { Text("Add games") }
                        }
                    }
                }
            } else {
                itemsIndexed(visible, key = { _, entry -> entry.id }) { index, entry ->
                    val isSelected = selected?.id == entry.id
                    var focused by remember(entry.id) { mutableStateOf(false) }
                    val focusRequester = remember(entry.id) {
                        FocusRequester().also { focusRequesters[entry.id] = it }
                    }
                    val scale by animateFloatAsState(
                        if (focused || isSelected) 1.06f else 1f,
                        spring(dampingRatio = 0.72f, stiffness = 350f),
                        label = "tile-scale",
                    )
                    CoverTile(
                        activity,
                        entry,
                        Modifier.focusRequester(focusRequester).animateItem().graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            alpha = if (selected != null && !isSelected && !focused) 0.86f else 1f
                        }.onFocusChanged {
                            focused = it.isFocused
                            if (it.isFocused) {
                                activity.selectEntry(entry)
                                scope.launch {
                                    gridState.animateScrollToItem(headerItemCount + index)
                                }
                            }
                        },
                        focused || isSelected,
                        onClick = {
                            if (activity.selected?.id == entry.id) activity.play(entry)
                            else activity.selectEntry(entry)
                        },
                        onLongClick = { activity.showOptionsFor = entry },
                    )
                }
            }
        }
    }
}

@Composable
private fun HubToolbar(activity: MainActivity, narrow: Boolean, compact: Boolean) {
    if (narrow) {
        Column(Modifier.fillMaxWidth()) {
            HubTabButtons(activity)
            Spacer(Modifier.height(4.dp))
            HubSearchBox(activity, Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HubTabButtons(activity)
            Spacer(Modifier.weight(1f))
            HubSearchBox(activity, Modifier.width(if (compact) 160.dp else 220.dp))
        }
    }
}

@Composable
private fun HubTabButtons(activity: MainActivity) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf(Tab.ALL, Tab.NATIVE, Tab.PC).forEach { tab ->
            val label = when (tab) {
                Tab.ALL -> "All"
                Tab.NATIVE -> "Native"
                Tab.PC -> "PC"
            }
            TextButton(
                onClick = { activity.tab = tab },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (activity.tab == tab) HubAccent else HubMuted,
                ),
            ) {
                Text(label, fontWeight = if (activity.tab == tab) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun HubSearchBox(activity: MainActivity, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF111827)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⌕", color = HubMuted, fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = activity.searchQuery,
            onValueChange = { activity.searchQuery = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
            decorationBox = { inner ->
                Box {
                    if (activity.searchQuery.isEmpty()) Text("Search games", color = HubMuted, fontSize = 13.sp)
                    inner()
                }
            },
        )
    }
}

@Composable
private fun HubRail(activity: MainActivity, systems: List<LauncherSystem>, modifier: Modifier = Modifier) {
    val railItems = listOf(
        RailItem("all", "⌂", "All"),
        RailItem("recent", "◷", "Recent"),
        RailItem("native", "▣", "Native"),
        RailItem("pc", "▤", "PC"),
    ) + systems.map { RailItem("system:${it.id}", "", it.name, it) }
    Column(
        modifier.clip(RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp))
            .background(Color(0xCC101623))
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.fillMaxWidth().height(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("✦", color = HubAccent, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text("BIFROST", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(railItems, key = { it.id }) { item ->
                val selected = item.id == activity.selectedRail
                val selectRail = {
                    activity.selectedRail = item.id
                    activity.tab = Tab.ALL
                    activity.selected = if (item.id == "recent") {
                        activity.snapshot.entries.filter { it.lastPlayed != null }.maxByOrNull { it.lastPlayed ?: 0L }
                    } else {
                        activity.snapshot.entries.firstOrNull { entry ->
                            if (item.id.startsWith("system:")) entry.systemId == item.id.removePrefix("system:")
                            else when (item.id) {
                                "native" -> entry.kind == Kind.NATIVE
                                "pc" -> entry.kind == Kind.PC
                                else -> true
                            }
                        }
                    }
                }
                RailItemSlot(
                    icon = item.icon,
                    label = item.label,
                    selected = selected,
                    imagePath = item.system?.icon,
                    accent = item.system?.accent,
                    onClick = selectRail,
                )
            }
            item(key = "add-system") {
                RailItemSlot("+", "Add System", selected = false) {
                    activity.openSettings(SettingsSection.SYSTEMS)
                }
            }
        }
        RailItemSlot(
            icon = "⚙",
            label = "Settings",
            selected = activity.page == Page.SETTINGS,
            onClick = { activity.openSettings() },
        )
    }
}

private data class RailItem(
    val id: String,
    val icon: String,
    val label: String,
    val system: LauncherSystem? = null,
)

@Composable
private fun RailItemSlot(
    icon: String,
    label: String,
    selected: Boolean,
    imagePath: String? = null,
    accent: Long? = null,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(if (selected) 1.08f else 1f, spring(), label = "rail-scale")
    Box(Modifier.fillMaxWidth().height(56.dp)) {
        if (selected) {
            Box(
                Modifier.align(Alignment.CenterStart).padding(start = 4.dp)
                    .width(4.dp).height(30.dp).clip(CircleShape).background(HubAccent),
            )
        }
        Column(
            Modifier.fillMaxSize().clickable(onClick = onClick).onFocusChanged { if (it.isFocused) onClick() }.focusable(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(32.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) HubAccent.copy(alpha = 0.16f) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (imagePath != null) {
                    HubArtwork(imagePath, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)), ContentScale.Crop)
                } else {
                    Text(
                        if (icon.isEmpty()) label.take(1).uppercase() else icon,
                        color = accent?.let(::Color) ?: if (selected) HubAccent else HubMuted,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                label,
                Modifier.padding(horizontal = 1.dp),
                color = if (selected) Color.White else HubMuted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HeroBanner(activity: MainActivity, entry: LibraryEntry?, height: androidx.compose.ui.unit.Dp) {
    val compactLayout = height < 140.dp
    val configuration = LocalConfiguration.current
    val portrait = configuration.screenWidthDp < configuration.screenHeightDp
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            clock = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            kotlinx.coroutines.delay(30_000)
        }
    }
    val bannerPath = entry?.cover ?: activity.bannerPath()
    val transition = rememberInfiniteTransition(label = "hero-ken-burns")
    val zoom by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(18000), repeatMode = RepeatMode.Reverse),
        label = "hero-zoom",
    )
    Box(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF19344A), Color(0xFF111322), Color(0xFF372B44))))
    ) {
        Crossfade(bannerPath, animationSpec = tween(260), label = "hero-art") { path ->
            if (path != null) {
                HubArtwork(
                    path,
                    Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom }.blur(20.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(listOf(Color(0xFF19344A), Color(0xFF111322), Color(0xFF372B44))),
                    ),
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(Color(0xEE070A12), Color(0xA8070A12), Color(0x20070A12)),
                    startX = 0f,
                    endX = 1500f,
                ),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x14000000), Color(0xCC070A12)))))
        Text(
            clock,
            Modifier.align(Alignment.TopEnd).padding(if (compactLayout) 8.dp else 18.dp),
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 13.sp,
        )
        if (entry == null) {
            Column(Modifier.align(Alignment.CenterStart).padding(start = 28.dp, end = 28.dp)) {
                Text("BIFROST GAME HUB", color = Color.White, fontSize = if (compactLayout) 22.sp else 30.sp, fontWeight = FontWeight.Black, letterSpacing = if (compactLayout) 1.5.sp else 3.sp)
                Text("Your games, together.", color = HubMuted, fontSize = if (compactLayout) 12.sp else 14.sp)
                Spacer(Modifier.height(if (compactLayout) 6.dp else 14.dp))
                Button(onClick = { activity.openSettings(SettingsSection.ADD_GAMES) }) {
                    Text("Add games")
                }
            }
        } else if (portrait) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    HeroPoster(
                        activity,
                        entry,
                        Modifier.fillMaxHeight(0.82f).aspectRatio(2f / 3f),
                    )
                    Spacer(Modifier.width(14.dp))
                    HeroDetails(activity, entry, compactLayout, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    HeroActions(activity, entry)
                }
            }
        } else {
            Row(
                Modifier.align(Alignment.CenterStart).fillMaxSize().padding(
                    start = if (compactLayout) 20.dp else 28.dp,
                    end = if (compactLayout) 16.dp else 24.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroPoster(
                    activity,
                    entry,
                    Modifier.fillMaxHeight(if (compactLayout) 0.72f else 0.82f).aspectRatio(2f / 3f),
                )
                Spacer(Modifier.width(if (compactLayout) 10.dp else 16.dp))
                HeroDetails(activity, entry, compactLayout, Modifier.weight(1f))
                Spacer(Modifier.width(if (compactLayout) 8.dp else 14.dp))
                HeroActions(activity, entry)
            }
        }
    }
}

@Composable
private fun HeroPoster(activity: MainActivity, entry: LibraryEntry, modifier: Modifier = Modifier) {
    Box(modifier.shadow(12.dp, RoundedCornerShape(13.dp)).clip(RoundedCornerShape(13.dp))) {
        HubArtwork(
            entry.cover,
            Modifier.fillMaxSize(),
            ContentScale.Crop,
            entry.title,
        )
    }
}

@Composable
private fun HeroDetails(
    activity: MainActivity,
    entry: LibraryEntry,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val systemName = activity.snapshot.systems.firstOrNull { it.id == entry.systemId }?.name
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            HubTag(if (entry.kind == Kind.NATIVE) "NATIVE" else "PC GAME", HubAccent)
            systemName?.let { HubTag(it.uppercase(), Color(0xFFB6C8FF)) }
        }
        Spacer(Modifier.height(if (compact) 3.dp else 8.dp))
        Text(
            entry.title,
            color = Color.White,
            fontSize = if (compact) 22.sp else 30.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            playSummary(entry),
            color = Color(0xFFD0D7E1),
            fontSize = if (compact) 10.sp else 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeroActions(activity: MainActivity, entry: LibraryEntry) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = { activity.play(entry) },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HubAccent),
        ) {
            Text("▶  Play", color = Color(0xFF08151A), fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = { activity.showOptionsFor = entry },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
        ) {
            Text("Options", color = Color.White)
        }
    }
}

private fun playSummary(entry: LibraryEntry): String = if (entry.playCount == 0) {
    "Not played yet"
} else {
    "Played ${entry.playCount} times · Last played ${relativeLastPlayed(entry.lastPlayed)}"
}

private fun relativeLastPlayed(timestamp: Long?): String {
    if (timestamp == null) return "unknown"
    val seconds = ((System.currentTimeMillis() - timestamp) / 1_000L).coerceAtLeast(0L)
    return when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3_600}h ago"
        seconds < 604_800 -> "${seconds / 86_400}d ago"
        seconds < 2_592_000 -> "${seconds / 604_800}w ago"
        seconds < 31_536_000 -> "${seconds / 2_592_000}mo ago"
        else -> "${seconds / 31_536_000}y ago"
    }
}

@Composable
private fun HubTag(text: String, color: Color) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(7.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 9.dp, vertical = 4.dp),
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TopPlayedCard(entry: LibraryEntry, rank: Int, compact: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151D2A)),
        border = BorderStroke(if (rank == 1) 1.5.dp else 1.dp, if (rank == 1) HubAccent.copy(alpha = 0.6f) else Color(0x334D6577)),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val showArtwork = maxWidth >= 120.dp
            Row(Modifier.fillMaxSize().padding(if (compact) 6.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showArtwork) {
                    HubArtwork(
                        entry.cover,
                        Modifier.size(if (compact) 36.dp else 48.dp).clip(RoundedCornerShape(9.dp)),
                        title = entry.title,
                    )
                    Spacer(Modifier.width(if (compact) 6.dp else 9.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "#$rank · ${entry.playCount} plays",
                        color = if (rank == 1) HubAccent else HubMuted,
                        fontSize = if (compact) 8.sp else 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                    Text(
                        entry.title,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                        fontSize = if (compact) 10.sp else 12.sp,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverTile(
    activity: MainActivity,
    entry: LibraryEntry,
    modifier: Modifier,
    highlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp))
            .border(
                if (highlighted) 2.dp else 1.dp,
                if (highlighted) HubAccent else Color(0x334F6074),
                RoundedCornerShape(14.dp),
            )
            .background(Color(0xFF131B29))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .focusable(),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            HubArtwork(entry.cover, Modifier.fillMaxSize(), ContentScale.Crop, entry.title)
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0070A12))))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                Text(
                    entry.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HubArtwork(path: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, title: String = "") {
    val bitmap = remember(path) { path?.let(HubArtworkCache::decode) }
    if (bitmap != null) {
        Image(bitmap, null, modifier, contentScale = contentScale)
    } else {
        val hue = (title.hashCode().toLong().absoluteValue % 360).toFloat()
        BoxWithConstraints(
            modifier.background(
                Brush.linearGradient(
                    listOf(Color.hsv(hue, 0.7f, 0.48f), Color.hsv((hue + 42) % 360, 0.72f, 0.18f)),
                ),
            ),
        ) {
            if (title.isNotEmpty()) {
                Text(
                    title.take(1).uppercase(),
                    Modifier.align(Alignment.Center),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = (maxWidth * 0.3f).coerceIn(12.dp, 48.dp).value.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
    }
}

@Composable
private fun EntryOptionsPanel(activity: MainActivity, entry: LibraryEntry) {
    var renameDialog by remember(entry.id) { mutableStateOf(false) }
    var rename by remember(entry.id) { mutableStateOf(entry.title) }
    var systemMenu by remember { mutableStateOf(false) }
    var splitConfirm by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val visibility = remember(entry.id) { MutableTransitionState(false) }
    val firstActionFocusRequester = remember(entry.id) { FocusRequester() }
    LaunchedEffect(entry.id) {
        visibility.targetState = true
        withFrameNanos { }
        firstActionFocusRequester.requestFocus()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelWidth = if (configuration.screenWidthDp < configuration.screenHeightDp) maxWidth else maxWidth * 0.4f
        AnimatedVisibility(
            visibleState = visibility,
            enter = fadeIn(tween(180)),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.58f))
                    .clickable { activity.showOptionsFor = null },
            )
        }
        AnimatedVisibility(
            visibleState = visibility,
            enter = slideInHorizontally(tween(240)) { it } + fadeIn(tween(200)),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Column(
                Modifier.width(panelWidth).fillMaxHeight()
                    .background(Color(0xFF141B28))
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { activity.showOptionsFor = null }) {
                        Text("←", color = HubAccent, fontSize = 22.sp)
                    }
                    Text(
                        entry.title,
                        Modifier.weight(1f),
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("GAME OPTIONS", color = HubMuted, fontSize = 10.sp, letterSpacing = 1.3.sp)
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        OptionActionRow("▶", "Play", modifier = Modifier.focusRequester(firstActionFocusRequester)) {
                            activity.showOptionsFor = null
                            activity.play(entry)
                        }
                    }
                    if (entry.kind == Kind.PC) {
                        item {
                            OptionActionRow("▣", "Controls") {
                                activity.showOptionsFor = null
                                activity.openControls(entry)
                            }
                        }
                        item {
                            Column(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                                Text("Controller mode", color = HubMuted, fontSize = 12.sp)
                                Spacer(Modifier.height(7.dp))
                                ControllerModeSelector(
                                    selected = entry.controllerMode,
                                    onOverlay = {
                                        activity.updateEntry(entry.id) { it.copy(controllerMode = ControllerMode.OVERLAY) }
                                    },
                                    onSplit = {
                                        if (entry.controllerMode == ControllerMode.OVERLAY) splitConfirm = true
                                        else activity.updateEntry(entry.id) { it.copy(controllerMode = ControllerMode.SPLIT) }
                                    },
                                )
                            }
                        }
                        item {
                            Column(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                                Text("Controller API", color = HubMuted, fontSize = 12.sp)
                                Spacer(Modifier.height(7.dp))
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF0F1622)).padding(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    listOf(
                                        ControllerApi.AUTO to "Auto",
                                        ControllerApi.XINPUT to "XInput",
                                        ControllerApi.DIRECTINPUT to "DirectInput",
                                    ).forEach { (api, label) ->
                                        ControllerModeSegment(
                                            label,
                                            entry.controllerApi == api,
                                            Modifier.weight(1f),
                                        ) {
                                            activity.updateEntry(entry.id) { it.copy(controllerApi = api) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Box {
                            OptionActionRow("▤", "Move to System") { systemMenu = true }
                            DropdownMenu(expanded = systemMenu, onDismissRequest = { systemMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("No System") },
                                    onClick = { activity.updateEntry(entry.id) { it.copy(systemId = null) }; systemMenu = false },
                                )
                                activity.snapshot.systems.forEach { system ->
                                    DropdownMenuItem(
                                        text = { Text(system.name) },
                                        onClick = { activity.updateEntry(entry.id) { it.copy(systemId = system.id) }; systemMenu = false },
                                    )
                                }
                            }
                        }
                    }
                    item {
                        OptionActionRow("▧", "Change cover") {
                            activity.pickImage(ImageAction.COVER, entry.id)
                            activity.showOptionsFor = null
                        }
                    }
                    item {
                        OptionActionRow("✎", "Rename") {
                            rename = entry.title
                            renameDialog = true
                        }
                    }
                    item {
                        OptionActionRow("⌫", "Remove from library", destructive = true) {
                            activity.showOptionsFor = null
                            activity.requestRemove(entry)
                        }
                    }
                }
            }
        }
    }
    if (renameDialog) {
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("Rename game") },
            text = { OutlinedTextField(rename, { rename = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val value = rename.trim()
                    if (value.isNotEmpty()) activity.updateEntry(entry.id) { it.copy(title = value) }
                    renameDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Cancel") } },
        )
    }
    if (splitConfirm) {
        AlertDialog(
            onDismissRequest = { splitConfirm = false },
            title = { Text("Switch to Split-Controller?") },
            text = { Text("This game currently uses the default overlay layout. Switch its controls profile to the Split-Controller preset?") },
            confirmButton = {
                TextButton(onClick = {
                    activity.setControllerMode(entry, ControllerMode.SPLIT, applyPreset = true)
                    splitConfirm = false
                    activity.showOptionsFor = null
                }) { Text("Switch layout") }
            },
            dismissButton = {
                TextButton(onClick = {
                    activity.setControllerMode(entry, ControllerMode.SPLIT, applyPreset = false)
                    splitConfirm = false
                    activity.showOptionsFor = null
                }) { Text("Keep current layout") }
            },
        )
    }
}

@Composable
private fun OptionActionRow(
    icon: String,
    label: String,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = if (destructive) Color(0xFFFF9C9C) else HubAccent
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
            .background(if (focused) Color(0xFF2A3D50) else Color(0xFF1B2534))
            .border(
                width = if (focused) 1.dp else 0.dp,
                color = if (focused) HubAccent else Color.Transparent,
                shape = RoundedCornerShape(13.dp),
            )
            .clickable(onClick = onClick)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, color = accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(11.dp))
        Text(label, color = if (destructive) accent else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ControllerModeSelector(
    selected: ControllerMode,
    onOverlay: () -> Unit,
    onSplit: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F1622)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ControllerModeSegment(
            "Overlay",
            selected == ControllerMode.OVERLAY,
            Modifier.weight(1f),
            onOverlay,
        )
        ControllerModeSegment(
            "Split-Controller",
            selected == ControllerMode.SPLIT,
            Modifier.weight(1f),
            onSplit,
        )
    }
}

@Composable
private fun ControllerModeSegment(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.clip(RoundedCornerShape(9.dp))
            .background(if (selected) HubAccent else Color.Transparent)
            .border(
                width = if (focused) 1.dp else 0.dp,
                color = if (focused) if (selected) Color.White else HubAccent else Color.Transparent,
                shape = RoundedCornerShape(9.dp),
            )
            .clickable(onClick = onClick)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(horizontal = 4.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF08151A) else HubMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SettingsScreen(activity: MainActivity) {
    val sections = listOf(
        SettingsSection.ADD_GAMES to "Add games",
        SettingsSection.SYSTEMS to "Systems",
        SettingsSection.APPEARANCE to "Appearance",
        SettingsSection.CONTROLS to "Controls",
        SettingsSection.WINDOWS_RUNTIME to "Windows runtime",
        SettingsSection.ABOUT to "About",
    )
    BoxWithConstraints(Modifier.fillMaxSize().background(HubBackground)) {
        val compact = maxWidth < 600.dp
        LaunchedEffect(compact) {
            if (!compact) activity.settingsDetailOpen = null
        }
        if (compact) {
            val openSection = activity.settingsDetailOpen
            if (openSection == null) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                ) {
                    TextButton(
                        onClick = {
                            activity.settingsDetailOpen = null
                            activity.page = Page.HOME
                        },
                    ) { Text("←  Library", color = Color.White) }
                    Spacer(Modifier.height(6.dp))
                    Text("SETTINGS", color = HubMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(12.dp))
                    sections.forEach { (section, title) ->
                        Text(
                            title,
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (activity.settingsSection == section) HubAccent.copy(alpha = 0.15f) else Color(0xFF111824))
                                .clickable {
                                    activity.settingsSection = section
                                    activity.settingsDetailOpen = section
                                }
                                .padding(horizontal = 16.dp, vertical = 17.dp),
                            color = if (activity.settingsSection == section) HubAccent else Color.White,
                            fontWeight = if (activity.settingsSection == section) FontWeight.Bold else FontWeight.Medium,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            } else {
                SettingsDetail(
                    activity = activity,
                    section = openSection,
                    compact = true,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    sections = sections,
                )
            }
        } else {
            Row(Modifier.fillMaxSize().padding(16.dp)) {
                Column(
                    Modifier.width(210.dp).fillMaxHeight().clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF111824)).padding(12.dp),
                ) {
                    TextButton(
                        onClick = {
                            activity.settingsDetailOpen = null
                            activity.page = Page.HOME
                        },
                    ) { Text("←  Library", color = Color.White) }
                    Spacer(Modifier.height(6.dp))
                    Text("SETTINGS", color = HubMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(12.dp))
                    sections.forEach { (section, title) ->
                        Text(
                            title,
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (activity.settingsSection == section) HubAccent.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable {
                                    activity.settingsSection = section
                                    activity.settingsDetailOpen = null
                                }
                                .padding(horizontal = 12.dp, vertical = 13.dp),
                            color = if (activity.settingsSection == section) HubAccent else Color.White,
                            fontWeight = if (activity.settingsSection == section) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.width(20.dp))
                SettingsDetail(
                    activity = activity,
                    section = activity.settingsSection,
                    compact = false,
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(8.dp),
                    sections = sections,
                )
            }
        }
    }
}

@Composable
private fun SettingsDetail(
    activity: MainActivity,
    section: SettingsSection,
    compact: Boolean,
    modifier: Modifier,
    sections: List<Pair<SettingsSection, String>>,
) {
    Column(modifier) {
        if (compact) {
            TextButton(onClick = { activity.settingsDetailOpen = null }) {
                Text("← Settings", color = HubAccent)
            }
        }
        Text(sections.first { it.first == section }.second, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        when (section) {
            SettingsSection.ADD_GAMES -> AddGamesSettings(activity, compact, Modifier.weight(1f))
            SettingsSection.SYSTEMS -> SystemsSettings(activity, compact, Modifier.weight(1f))
            SettingsSection.APPEARANCE -> AppearanceSettings(activity, Modifier.weight(1f))
            SettingsSection.CONTROLS -> ControlsSettings(activity, Modifier.weight(1f))
            SettingsSection.WINDOWS_RUNTIME -> RuntimeSettings(activity, Modifier.weight(1f))
            SettingsSection.ABOUT -> AboutSettings(activity, Modifier.weight(1f))
        }
    }
}

@Composable
private fun AddGamesSettings(activity: MainActivity, compact: Boolean, modifier: Modifier) {
    var targetMenu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text("Save new games into", color = HubMuted, fontSize = 12.sp)
        Box {
            OutlinedButton(onClick = { targetMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    activity.snapshot.systems.firstOrNull { it.id == activity.targetSystemId }?.name ?: "No System",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(targetMenu, { targetMenu = false }) {
                DropdownMenuItem(text = { Text("No System") }, onClick = { activity.targetSystemId = null; targetMenu = false })
                activity.snapshot.systems.forEach { system ->
                    DropdownMenuItem(text = { Text(system.name) }, onClick = { activity.targetSystemId = system.id; targetMenu = false })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        AddGameAction(
            "Add Windows game",
            "Pick the game's .exe or its folder. It plays from where it is; nothing is copied.",
            "⊞",
            compact,
            activity::beginAddWindowsGame,
        )
        AddGameAction("Install APK", "Install an APK from this device", "⬇", compact) { activity.pickApk() }
        AddGameAction(
            "Link installed games",
            "Add launchable apps without copying or reinstalling them",
            "↗",
            compact,
        ) { activity.showInstalledApps() }
        AddGameAction(
            "Import .bifrost package",
            "A package made on a PC with Bifrost's Recomp → Windows PC game",
            "▣",
            compact,
        ) { activity.pickPackage() }
    }
}

@Composable
private fun AddGameAction(
    title: String,
    subtitle: String,
    glyph: String,
    compact: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF141D2C)).clickable(onClick = onClick)
            .padding(if (compact) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(if (compact) 44.dp else 52.dp).clip(CircleShape)
                .background(HubAccent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = HubAccent, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).padding(start = if (compact) 10.dp else 14.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = HubMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SystemsSettings(activity: MainActivity, compact: Boolean, modifier: Modifier) {
    var createDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<LauncherSystem?>(null) }
    var deleteConfirm by remember { mutableStateOf<LauncherSystem?>(null) }
    Column(modifier.fillMaxWidth()) {
        Button(onClick = { name = ""; createDialog = true }) { Text("+  Create System") }
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(activity.snapshot.systems, key = { it.id }) { system ->
                if (compact) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF141D2C)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SystemArtwork(system, Modifier.size(42.dp))
                            Text(
                                system.name,
                                Modifier.weight(1f).padding(horizontal = 12.dp),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { activity.reorderSystem(system.id, -1) }) { Text("↑") }
                            TextButton(onClick = { activity.reorderSystem(system.id, 1) }) { Text("↓") }
                            TextButton(onClick = { editing = system }) { Text("Edit") }
                            TextButton(onClick = { deleteConfirm = system }) { Text("Delete", color = Color(0xFFFF9C9C)) }
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF141D2C)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SystemArtwork(system, Modifier.size(42.dp))
                        Text(
                            system.name,
                            Modifier.weight(1f).padding(horizontal = 12.dp),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { activity.reorderSystem(system.id, -1) }) { Text("↑") }
                        TextButton(onClick = { activity.reorderSystem(system.id, 1) }) { Text("↓") }
                        TextButton(onClick = { editing = system }) { Text("Edit") }
                        TextButton(onClick = { deleteConfirm = system }) { Text("Delete", color = Color(0xFFFF9C9C)) }
                    }
                }
            }
        }
    }
    if (createDialog) {
        AlertDialog(
            onDismissRequest = { createDialog = false },
            title = { Text("Create System") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { activity.createSystem(name); createDialog = false }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { createDialog = false }) { Text("Cancel") } },
        )
    }
    editing?.let { system ->
        var editName by remember(system.id) { mutableStateOf(system.name) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit ${system.name}") },
            text = {
                Column {
                    OutlinedTextField(editName, { editName = it }, label = { Text("Name") }, singleLine = true)
                    Spacer(Modifier.height(12.dp))
                    Text("System accent", color = HubMuted, fontSize = 12.sp)
                    AccentPresets { color ->
                        activity.updateSystem(system.copy(accent = color))
                        editing = system.copy(accent = color)
                    }
                    TextButton(onClick = { activity.pickImage(ImageAction.SYSTEM_ICON, system.id); editing = null }) { Text("Upload icon") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editName.trim().isNotEmpty()) activity.updateSystem(system.copy(name = editName.trim()))
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Done") } },
        )
    }
    deleteConfirm?.let { system ->
        AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            title = { Text("Delete ${system.name}?") },
            text = { Text("The System will be removed. Its games stay in the library and move to All.") },
            confirmButton = {
                TextButton(onClick = { activity.deleteSystem(system); deleteConfirm = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteConfirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SystemArtwork(system: LauncherSystem, modifier: Modifier) {
    if (system.icon != null) {
        HubArtwork(system.icon, modifier.clip(RoundedCornerShape(11.dp)))
    } else {
        Box(
            modifier.clip(RoundedCornerShape(11.dp))
                .background(system.accent?.let(::Color) ?: HubAccent.copy(alpha = .2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(system.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AccentPresets(onSelect: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        listOf(0xFF83F0C9L, 0xFF78B8FFL, 0xFFC89AFFL, 0xFFFFA76EL, 0xFFFF7185L).forEach { color ->
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color(color))
                    .clickable { onSelect(color) },
            )
        }
    }
}

@Composable
private fun AppearanceSettings(activity: MainActivity, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Hub banner", color = Color.White, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { activity.pickImage(ImageAction.BANNER) }) { Text("Upload banner") }
            OutlinedButton(onClick = {
                activity.resetBanner()
            }) { Text("Reset") }
        }
        Divider(color = Color(0x334B5B70))
        Text("Accent colour", color = Color.White, fontWeight = FontWeight.SemiBold)
        AccentPresets(activity::setAccent)
    }
}

@Composable
private fun RuntimeSettings(activity: MainActivity, modifier: Modifier) {
    val installed = remember(activity.runtimeInstallProgress, activity.runtimeInstallError) {
        WindowsRuntime.isInstalled(activity)
    }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (installed) "Windows runtime is installed." else "Windows runtime is not installed.",
            color = if (installed) HubAccent else Color.White,
        )
        activity.runtimeInstallProgress?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(progress = { it / 100f }, modifier = Modifier.size(28.dp))
                Text("  Installing… $it%", color = HubMuted)
            }
        }
        activity.runtimeInstallError?.let { Text(it, color = Color(0xFFFF9C9C)) }
        Button(onClick = activity::installRuntimeFromSettings, enabled = activity.runtimeInstallProgress == null) {
            Text(if (installed) "Reinstall runtime" else "Install runtime")
        }
    }
}

@Composable
private fun AboutSettings(activity: MainActivity, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Bifrost ${com.winlator.BuildConfig.VERSION_NAME}", color = Color.White)
        Text("A game hub for native Android and Windows PC games.", color = HubMuted)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = activity::checkForUpdates,
                enabled = activity.updateStatus != UpdateStatus.Checking && activity.updateStatus !is UpdateStatus.Downloading,
            ) {
                Text(if (activity.updateStatus == UpdateStatus.Checking) "Checking…" else "Check for updates")
            }
            TextButton(onClick = activity::shareLogs) {
                Text("Share logs", maxLines = 1)
            }
        }
        when (val status = activity.updateStatus) {
            UpdateStatus.Idle -> Unit
            UpdateStatus.Checking -> {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text("Checking…", color = HubMuted)
            }
            is UpdateStatus.Latest -> Text("You're on the latest version (${status.version})", color = HubMuted)
            is UpdateStatus.Available -> {
                val sizeMb = ((status.release.size + 524_288L) / (1024L * 1024L)).coerceAtLeast(1L)
                Text("Bifrost ${status.release.tagName} is available ($sizeMb MB)", color = Color.White)
                Button(onClick = { activity.downloadAndInstallUpdate(status.release) }) {
                    Text("Download and install")
                }
            }
            is UpdateStatus.Downloading -> {
                Text("Downloading… ${status.progress}%", color = HubMuted)
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { status.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is UpdateStatus.Error -> Text(status.message, color = Color(0xFFFF9C9C))
        }
        TextButton(onClick = {
            activity.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/ActOfRod/Bifrost/blob/main/LICENSE"),
                ),
            )
        }) { Text("Bifrost license") }
    }
}

@Composable
private fun ControlsSettings(activity: MainActivity, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ControllerStyle.values().forEach { style ->
            val selected = activity.controllerStyle == style
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(if (selected) HubAccent.copy(alpha = 0.12f) else Color(0xFF111824))
                    .border(
                        1.dp,
                        if (selected) HubAccent.copy(alpha = 0.7f) else Color(0xFF263244),
                        RoundedCornerShape(16.dp),
                    )
                    .clickable { activity.selectControllerStyle(style) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControllerStylePreview(style)
                Spacer(Modifier.width(12.dp))
                Text(
                    when (style) {
                        ControllerStyle.XBOX -> "Xbox"
                        ControllerStyle.NINTENDO -> "Nintendo"
                        ControllerStyle.PLAYSTATION -> "PlayStation"
                    },
                    Modifier.weight(1f),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
                RadioButton(selected = selected, onClick = { activity.selectControllerStyle(style) })
            }
        }
        Text(
            "Changes button labels and colours. Buttons send the same inputs to the game, so the bottom button is always the game's A.",
            color = HubMuted,
            fontSize = 12.sp,
        )
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF111824))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Record controller trace", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    "Logs the game's joystick requests to Wine. May slow the game; turn off after testing.",
                    color = HubMuted,
                    fontSize = 11.sp,
                )
            }
            Switch(
                checked = activity.recordControllerTrace,
                onCheckedChange = activity::updateControllerTrace,
            )
        }
    }
}

@Composable
private fun ControllerStylePreview(style: ControllerStyle) {
    Canvas(Modifier.size(width = 64.dp, height = 58.dp)) {
        val bindings = listOf(
            "GAMEPAD_BUTTON_Y" to Offset(size.width * 0.72f, size.height * 0.23f),
            "GAMEPAD_BUTTON_B" to Offset(size.width * 0.88f, size.height * 0.50f),
            "GAMEPAD_BUTTON_A" to Offset(size.width * 0.72f, size.height * 0.77f),
            "GAMEPAD_BUTTON_X" to Offset(size.width * 0.56f, size.height * 0.50f),
        )
        val radius = size.minDimension * 0.12f
        bindings.forEach { (binding, center) ->
            val presentation = controllerButtonPresentation(style, binding)
            drawCircle(Color(0xFF172131), radius, center)
            drawCircle(Color(0xFFB3FFFFFF), radius, center, style = Stroke(1.2f))
            val accent = presentation?.accent?.let { Color(it) } ?: Color.White
            when (presentation?.symbol) {
                ControllerSymbol.CROSS -> {
                    val half = radius * 0.42f
                    drawLine(accent, center + Offset(-half, -half), center + Offset(half, half), 1.5f)
                    drawLine(accent, center + Offset(half, -half), center + Offset(-half, half), 1.5f)
                }
                ControllerSymbol.CIRCLE -> drawCircle(accent, radius * 0.42f, center, style = Stroke(1.5f))
                ControllerSymbol.SQUARE -> {
                    val half = radius * 0.36f
                    drawRect(accent, Offset(center.x - half, center.y - half), androidx.compose.ui.geometry.Size(half * 2, half * 2), style = Stroke(1.5f))
                }
                ControllerSymbol.TRIANGLE -> {
                    val half = radius * 0.45f
                    val path = Path().apply {
                        moveTo(center.x, center.y - half)
                        lineTo(center.x + half, center.y + half)
                        lineTo(center.x - half, center.y + half)
                        close()
                    }
                    drawPath(path, accent, style = Stroke(1.5f))
                }
                null -> {
                    val label = presentation?.label.orEmpty()
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = accent.toArgb()
                        textAlign = android.graphics.Paint.Align.CENTER
                        textSize = radius * 0.95f
                        typeface = android.graphics.Typeface.create("sans-serif-bold", android.graphics.Typeface.BOLD)
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        center.x,
                        center.y - (paint.ascent() + paint.descent()) * 0.5f,
                        paint,
                    )
                }
            }
        }
    }
}

@Composable
private fun InstalledAppsScreen(activity: MainActivity) {
    val narrow = LocalConfiguration.current.screenWidthDp < 600
    val apps = activity.installedApps.orEmpty()
    val linked = activity.snapshot.entries.mapNotNull { it.packageName }.toSet()
    val visible = filterInstalledApps(apps, activity.installedGamesOnly, activity.installedSearch, emptySet())
    Column(Modifier.fillMaxSize().background(HubBackground).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    activity.page = activity.installedAppsReturnPage
                    activity.installedApps = null
                },
            ) {
                Text("←", color = HubAccent, fontSize = 24.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Link installed games",
                    color = Color.White,
                    fontSize = if (narrow) 22.sp else 26.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Add apps to Bifrost without copying or reinstalling them.",
                    color = HubMuted,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                .background(Color(0xFF141D2C)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⌕", color = HubMuted, fontSize = 19.sp)
            Spacer(Modifier.width(8.dp))
            androidx.compose.foundation.text.BasicTextField(
                activity.installedSearch,
                { activity.installedSearch = it },
                Modifier.weight(1f),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                decorationBox = { inner ->
                    Box {
                        if (activity.installedSearch.isEmpty()) Text("Search apps", color = HubMuted, fontSize = 14.sp)
                        inner()
                    }
                },
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                .background(Color(0xFF141D2C)).padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Games only", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Show apps Android identifies as games", color = HubMuted, fontSize = 11.sp)
            }
            Switch(checked = activity.installedGamesOnly, onCheckedChange = { activity.installedGamesOnly = it })
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (visible.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (activity.installedGamesOnly) "No games match. Turn off Games only to browse all apps."
                            else "No launchable apps match your search.",
                            color = HubMuted,
                        )
                        if (activity.installedGamesOnly) {
                            TextButton(onClick = { activity.installedGamesOnly = false }) { Text("Show all apps") }
                        }
                    }
                }
            } else {
                items(visible, key = { it.packageName }) { app ->
                    val icon = remember(app.packageName) { activity.loadInstalledAppIcon(app.packageName) }
                    val isLinked = app.packageName in linked
                    val checked = app.packageName in activity.selectedInstalledPackages
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                            .background(if (isLinked) Color(0xFF10151E) else Color(0xFF141D2C))
                            .clickable(enabled = !isLinked) {
                                activity.selectedInstalledPackages =
                                    if (checked) activity.selectedInstalledPackages - app.packageName
                                    else activity.selectedInstalledPackages + app.packageName
                            }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (icon != null) {
                            Image(icon, null, Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                        } else {
                            HubArtwork(null, Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)), ContentScale.Crop, app.label)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                app.label,
                                color = if (isLinked) HubMuted else Color.White,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(app.packageName, color = HubMuted, fontSize = 10.sp, maxLines = 1)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (app.isGame) HubTag("GAME", HubAccent)
                                if (app.isBifrostPort) HubTag("BIFROST PORT", Color(0xFFB6C8FF))
                            }
                        }
                        if (isLinked) {
                            Text("LINKED", color = HubMuted, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 9.dp))
                        } else {
                            Checkbox(checked, onCheckedChange = {
                                activity.selectedInstalledPackages =
                                    if (it) activity.selectedInstalledPackages + app.packageName
                                    else activity.selectedInstalledPackages - app.packageName
                            })
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF111824)).padding(12.dp),
        ) {
            Button(
                onClick = { activity.addInstalledApps(activity.selectedInstalledPackages) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = activity.selectedInstalledPackages.isNotEmpty(),
            ) { Text("Add ${activity.selectedInstalledPackages.size}") }
        }
    }
}

internal object HubArtworkCache {
    private val cache = object : LruCache<String, ImageBitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4 / 1024
    }

    fun invalidate(path: String?) {
        if (path != null) cache.remove(path)
    }

    fun decode(path: String): ImageBitmap? {
        cache.get(path)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 512) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.asImageBitmap() ?: return null
        cache.put(path, bitmap)
        return bitmap
    }
}
