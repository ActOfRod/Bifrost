package com.actofrod.bifrost

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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

private val HubBackground = Color(0xFF070A12)
private val HubMuted = Color(0xFFA6B1C2)
private val HubAccent: Color
    @Composable get() = MaterialTheme.colorScheme.primary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HubScreen(activity: MainActivity) {
    BackHandler(enabled = activity.page != Page.HOME || activity.showOptionsFor != null) {
        when {
            activity.showOptionsFor != null -> activity.showOptionsFor = null
            activity.page == Page.LINK_APPS -> { activity.page = Page.SETTINGS; activity.installedApps = null }
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
        EntryOptionsSheet(activity, activity.snapshot.currentEntry(entry))
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameHubScreen(activity: MainActivity) {
    val compactLayout = LocalConfiguration.current.screenHeightDp < 500
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
    Row(Modifier.fillMaxSize().background(HubBackground)) {
        HubRail(activity, systems, Modifier.fillMaxHeight().width(82.dp))
        Column(
            Modifier.fillMaxSize().padding(
                end = 20.dp,
                top = if (compactLayout) 8.dp else 14.dp,
                bottom = if (compactLayout) 6.dp else 10.dp,
            ),
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val heroHeight = if (compactLayout) {
                    (maxHeight * 0.28f).coerceIn(96.dp, 200.dp)
                } else {
                    (maxHeight * 0.34f).coerceIn(170.dp, 300.dp)
                }
                HeroBanner(activity, selected, heroHeight)
            }
            val mostPlayed = topPlayedGames(allEntries)
            if (mostPlayed.isNotEmpty()) {
                Spacer(Modifier.height(if (compactLayout) 4.dp else 10.dp))
                Text(
                    "MOST PLAYED",
                    Modifier.padding(start = 4.dp, bottom = if (compactLayout) 3.dp else 6.dp),
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
                            modifier = Modifier.weight(1f).fillMaxHeight(if (index == 0) 1f else 0.88f),
                            onClick = { activity.selectEntry(entry) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(if (compactLayout) 4.dp else 9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf(Tab.ALL, Tab.NATIVE, Tab.PC).forEach { item ->
                    val label = when (item) {
                        Tab.ALL -> "All"
                        Tab.NATIVE -> "Native"
                        Tab.PC -> "PC"
                    }
                    TextButton(
                        onClick = { activity.tab = item },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (activity.tab == item) HubAccent else HubMuted,
                        ),
                    ) {
                        Text(label, fontWeight = if (activity.tab == item) FontWeight.Bold else FontWeight.Medium)
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.width(if (compactLayout) 180.dp else 220.dp).clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF111827)).padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("⌕", color = HubMuted, fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = activity.searchQuery,
                        onValueChange = { activity.searchQuery = it },
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
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = if (compactLayout) 94.dp else 122.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visible, key = { it.id }) { entry ->
                        val isSelected = selected?.id == entry.id
                        var focused by remember(entry.id) { mutableStateOf(false) }
                        val scale by animateFloatAsState(
                            if (focused || isSelected) 1.06f else 1f,
                            spring(dampingRatio = 0.72f, stiffness = 350f),
                            label = "tile-scale",
                        )
                        CoverTile(
                            activity,
                            entry,
                            Modifier.animateItem().graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                alpha = if (selected != null && !isSelected && !focused) 0.86f else 1f
                            }.onFocusChanged {
                                focused = it.isFocused
                                if (it.isFocused) activity.selectEntry(entry)
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
}

@Composable
private fun HubRail(activity: MainActivity, systems: List<LauncherSystem>, modifier: Modifier = Modifier) {
    val compactLayout = LocalConfiguration.current.screenHeightDp < 500
    val itemHeight = if (compactLayout) 38.dp else 52.dp
    val indicatorHeight = if (compactLayout) 26.dp else 34.dp
    val items = listOf(
        "all" to ("⌂" to "All"),
        "recent" to ("◷" to "Recent"),
        "native" to ("▣" to "Native"),
        "pc" to ("▤" to "PC"),
    ) + systems.map { "system:${it.id}" to ("•" to it.name) }
    val selectedIndex = items.indexOfFirst { it.first == activity.selectedRail }.coerceAtLeast(0)
    val indicatorY by animateDpAsState(
        targetValue = (if (compactLayout) 6.dp else 9.dp) + itemHeight * selectedIndex,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 360f),
        label = "rail-indicator",
    )
    Column(
        modifier.clip(RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp))
            .background(Color(0xCC101623))
            .padding(top = if (compactLayout) 4.dp else 10.dp, bottom = if (compactLayout) 4.dp else 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("✦", color = HubAccent, fontSize = if (compactLayout) 22.sp else 28.sp, fontWeight = FontWeight.Black)
        Text("BIFROST", color = Color.White, fontSize = if (compactLayout) 7.sp else 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Spacer(Modifier.height(if (compactLayout) 8.dp else 16.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Box(
                Modifier.offset(y = indicatorY).align(Alignment.TopStart).padding(start = 4.dp)
                    .width(4.dp).height(indicatorHeight).clip(CircleShape).background(HubAccent),
            )
            Column(Modifier.fillMaxWidth()) {
                items.forEach { (id, info) ->
                    val system = systems.firstOrNull { "system:${it.id}" == id }
                    val selected = id == activity.selectedRail
                    val scale by animateFloatAsState(if (selected) 1.1f else 1f, spring(), label = "rail-scale")
                    val selectRail = {
                        activity.selectedRail = id
                        activity.tab = Tab.ALL
                        activity.selected = if (id == "recent") {
                            activity.snapshot.entries.filter { it.lastPlayed != null }.maxByOrNull { it.lastPlayed ?: 0L }
                        } else {
                            activity.snapshot.entries.firstOrNull { entry ->
                                if (id.startsWith("system:")) entry.systemId == id.removePrefix("system:")
                                else when (id) {
                                    "native" -> entry.kind == Kind.NATIVE
                                    "pc" -> entry.kind == Kind.PC
                                    else -> true
                                }
                            }
                        }
                    }
                    Column(
                        Modifier.fillMaxWidth().height(itemHeight).clickable(onClick = selectRail)
                            .onFocusChanged { if (it.isFocused) selectRail() }
                            .padding(horizontal = 4.dp).focusable(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(Modifier.size(if (compactLayout) 24.dp else 34.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (selected) HubAccent.copy(alpha = 0.16f) else Color.Transparent),
                            contentAlignment = Alignment.Center) {
                            if (system?.icon != null) {
                                HubArtwork(system.icon, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
                            } else if (system != null) {
                                Text(
                                    system.name.take(1).uppercase(),
                                    color = system.accent?.let(::Color) ?: HubAccent,
                                    fontWeight = FontWeight.Bold,
                                )
                            } else {
                                Text(info.first, color = if (selected) HubAccent else HubMuted, fontSize = 21.sp)
                            }
                        }
                        Text(
                            info.second.take(8),
                            color = if (selected) Color.White else HubMuted,
                            fontSize = if (compactLayout) 7.sp else 8.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(
                    Modifier.fillMaxWidth().height(itemHeight).clickable {
                        activity.settingsSection = SettingsSection.SYSTEMS
                        activity.page = Page.SETTINGS
                    }.focusable(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("+", color = HubAccent, fontSize = 24.sp, fontWeight = FontWeight.Light)
                    Text("System", color = HubMuted, fontSize = 8.sp)
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().height(if (compactLayout) 44.dp else 56.dp).clickable { activity.openSettings() },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("⚙", color = HubMuted, fontSize = 20.sp)
            Text("Settings", color = HubMuted, fontSize = 8.sp)
        }
    }
}

@Composable
private fun HeroBanner(activity: MainActivity, entry: LibraryEntry?, height: androidx.compose.ui.unit.Dp) {
    val compactLayout = height < 140.dp
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
            .background(Brush.linearGradient(listOf(Color(0xFF19344A), Color(0xFF111322), Color(0xFF372B44))),
            )
    ) {
        Crossfade(bannerPath, animationSpec = tween(260), label = "hero-art") { path ->
            if (path != null) HubArtwork(
                path,
                Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom },
                contentScale = ContentScale.Crop,
            )
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
        } else {
            val systemName = activity.snapshot.systems.firstOrNull { it.id == entry.systemId }?.name
            if (compactLayout) {
                Row(
                    Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(start = 28.dp, end = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            HubTag(if (entry.kind == Kind.NATIVE) "NATIVE" else "PC GAME", HubAccent)
                            systemName?.let { HubTag(it.uppercase(), Color(0xFFB6C8FF)) }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(entry.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Played ${entry.playCount} ${if (entry.playCount == 1) "time" else "times"}  ·  ${entry.lastPlayed?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "Never played"}",
                            color = Color(0xFFD0D7E1),
                            fontSize = 10.sp,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { activity.play(entry) }, colors = ButtonDefaults.buttonColors(containerColor = HubAccent)) {
                            Text("▶  Play", color = Color(0xFF08151A), fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = { activity.showOptionsFor = entry }, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f))) {
                            Text("Options", color = Color.White)
                        }
                    }
                }
            } else {
                Column(Modifier.align(Alignment.CenterStart).padding(start = 28.dp, end = 24.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        HubTag(if (entry.kind == Kind.NATIVE) "NATIVE" else "PC GAME", HubAccent)
                        systemName?.let { HubTag(it.uppercase(), Color(0xFFB6C8FF)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(entry.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Played ${entry.playCount} ${if (entry.playCount == 1) "time" else "times"}  ·  ${entry.lastPlayed?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "Never played"}",
                        color = Color(0xFFD0D7E1),
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { activity.play(entry) }, colors = ButtonDefaults.buttonColors(containerColor = HubAccent)) {
                            Text("▶  Play", color = Color(0xFF08151A), fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = { activity.showOptionsFor = entry }, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f))) {
                            Text("Options", color = Color.White)
                        }
                    }
                }
            }
        }
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
        Row(Modifier.fillMaxSize().padding(if (compact) 6.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
            HubArtwork(entry.cover, Modifier.size(if (compact) 36.dp else 48.dp).clip(RoundedCornerShape(9.dp)))
            Spacer(Modifier.width(if (compact) 6.dp else 9.dp))
            Column(Modifier.weight(1f)) {
                Text("#$rank  MOST PLAYED", color = if (rank == 1) HubAccent else HubMuted, fontSize = if (compact) 8.sp else 9.sp, fontWeight = FontWeight.Bold)
                Text(entry.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = if (compact) 10.sp else 12.sp)
                Text("${entry.playCount} plays", color = HubMuted, fontSize = if (compact) 8.sp else 10.sp)
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
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f)) {
            HubArtwork(entry.cover, Modifier.fillMaxSize(), ContentScale.Crop, entry.title)
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0070A12))))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                Column {
                    Text(entry.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (entry.kind == Kind.NATIVE) "NATIVE" else "PC GAME", color = HubAccent, fontSize = 9.sp, letterSpacing = 0.8.sp)
                }
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
        Box(modifier.background(Brush.linearGradient(listOf(Color.hsv(hue, 0.7f, 0.48f), Color.hsv((hue + 42) % 360, 0.72f, 0.18f))))) {
            if (title.isNotEmpty()) {
                Text(
                    title.take(1).uppercase(),
                    Modifier.align(Alignment.Center),
                    color = Color(0xBFFFFFFF),
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryOptionsSheet(activity: MainActivity, entry: LibraryEntry) {
    var renameDialog by remember(entry.id) { mutableStateOf(false) }
    var rename by remember(entry.id) { mutableStateOf(entry.title) }
    var systemMenu by remember { mutableStateOf(false) }
    var splitConfirm by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = { activity.showOptionsFor = null },
        containerColor = Color(0xFF141B28),
        contentColor = Color.White,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text(entry.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { activity.showOptionsFor = null; activity.play(entry) }) { Text("Play") }
                if (entry.kind == Kind.PC) OutlinedButton(onClick = {
                    activity.showOptionsFor = null
                    activity.openControls(entry)
                }) { Text("Controls") }
            }
            if (entry.kind == Kind.PC) {
                Text("Controller mode", color = HubMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        activity.updateEntry(entry.id) { it.copy(controllerMode = ControllerMode.OVERLAY) }
                    }) { Text(if (entry.controllerMode == ControllerMode.OVERLAY) "✓ Overlay" else "Overlay") }
                    TextButton(onClick = {
                        if (entry.controllerMode == ControllerMode.OVERLAY) splitConfirm = true
                        else activity.updateEntry(entry.id) { it.copy(controllerMode = ControllerMode.SPLIT) }
                    }) { Text(if (entry.controllerMode == ControllerMode.SPLIT) "✓ Split-Controller" else "Split-Controller") }
                }
            }
            Box {
                TextButton(onClick = { systemMenu = true }) { Text("Move to System") }
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
            TextButton(onClick = { activity.pickImage(ImageAction.COVER, entry.id); activity.showOptionsFor = null }) { Text("Change cover") }
            TextButton(onClick = { rename = entry.title; renameDialog = true }) { Text("Rename") }
            TextButton(onClick = { activity.showOptionsFor = null; activity.requestRemove(entry) }) { Text("Remove from library", color = Color(0xFFFF9C9C)) }
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
private fun SettingsScreen(activity: MainActivity) {
    val sections = listOf(
        SettingsSection.ADD_GAMES to "Add games",
        SettingsSection.SYSTEMS to "Systems",
        SettingsSection.APPEARANCE to "Appearance",
        SettingsSection.WINDOWS_RUNTIME to "Windows runtime",
        SettingsSection.ABOUT to "About",
    )
    Row(Modifier.fillMaxSize().background(HubBackground).padding(16.dp)) {
        Column(
            Modifier.width(190.dp).fillMaxHeight().clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF111824)).padding(12.dp),
        ) {
            Text("SETTINGS", color = HubMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(12.dp))
            sections.forEach { (section, title) ->
                Text(
                    title,
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (activity.settingsSection == section) HubAccent.copy(alpha = 0.15f) else Color.Transparent)
                        .clickable { activity.settingsSection = section }
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                    color = if (activity.settingsSection == section) HubAccent else Color.White,
                    fontWeight = if (activity.settingsSection == section) FontWeight.Bold else FontWeight.Medium,
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { activity.page = Page.HOME }) { Text("←  Back to hub") }
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f).fillMaxHeight().padding(8.dp)) {
            Text(sections.first { it.first == activity.settingsSection }.second, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            when (activity.settingsSection) {
                SettingsSection.ADD_GAMES -> AddGamesSettings(activity)
                SettingsSection.SYSTEMS -> SystemsSettings(activity)
                SettingsSection.APPEARANCE -> AppearanceSettings(activity)
                SettingsSection.WINDOWS_RUNTIME -> RuntimeSettings(activity)
                SettingsSection.ABOUT -> AboutSettings(activity)
            }
        }
    }
}

@Composable
private fun AddGamesSettings(activity: MainActivity) {
    var targetMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text("Save new games into", color = HubMuted, fontSize = 12.sp)
        Box {
            OutlinedButton(onClick = { targetMenu = true }) {
                Text(activity.snapshot.systems.firstOrNull { it.id == activity.targetSystemId }?.name ?: "No System")
            }
            DropdownMenu(targetMenu, { targetMenu = false }) {
                DropdownMenuItem(text = { Text("No System") }, onClick = { activity.targetSystemId = null; targetMenu = false })
                activity.snapshot.systems.forEach { system ->
                    DropdownMenuItem(text = { Text(system.name) }, onClick = { activity.targetSystemId = system.id; targetMenu = false })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        AddGameAction("Install APK", "Install an APK from this device", "⬇") { activity.pickApk() }
        AddGameAction("Link installed games", "Add launchable apps without copying or reinstalling them", "↗") { activity.showInstalledApps() }
        AddGameAction("Import PC game", "Add a .bifrost game package", "▣") { activity.pickPackage() }
        AddGameAction("Add game folder", "Choose an installed Windows game folder on this phone", "▤") { activity.pickGameFolder() }
    }
}

@Composable
private fun AddGameAction(title: String, subtitle: String, glyph: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF141D2C)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, color = HubAccent, fontSize = 24.sp, modifier = Modifier.width(46.dp))
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = HubMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SystemsSettings(activity: MainActivity) {
    var createDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<LauncherSystem?>(null) }
    var deleteConfirm by remember { mutableStateOf<LauncherSystem?>(null) }
    Column {
        Button(onClick = { name = ""; createDialog = true }) { Text("+  Create System") }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(activity.snapshot.systems, key = { it.id }) { system ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF141D2C)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (system.icon != null) HubArtwork(system.icon, Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)))
                    else Box(Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(system.accent?.let(::Color) ?: HubAccent.copy(alpha = .2f)), contentAlignment = Alignment.Center) {
                        Text(system.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                    }
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
private fun AppearanceSettings(activity: MainActivity) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
private fun RuntimeSettings(activity: MainActivity) {
    val installed = remember(activity.runtimeInstallProgress, activity.runtimeInstallError) {
        WindowsRuntime.isInstalled(activity)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
private fun AboutSettings(activity: MainActivity) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Bifrost ${com.winlator.BuildConfig.VERSION_NAME}", color = Color.White)
        Text("A game hub for native Android and Windows PC games.", color = HubMuted)
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
private fun InstalledAppsScreen(activity: MainActivity) {
    val apps = activity.installedApps.orEmpty()
    val linked = activity.snapshot.entries.mapNotNull { it.packageName }.toSet()
    val visible = filterInstalledApps(apps, activity.installedGamesOnly, activity.installedSearch, emptySet())
    Row(Modifier.fillMaxSize().background(HubBackground).padding(20.dp)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Link installed games", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("Add apps to Bifrost without copying or reinstalling them.", color = HubMuted, fontSize = 12.sp)
                }
                TextButton(onClick = { activity.page = Page.SETTINGS; activity.installedApps = null }) { Text("Close") }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(13.dp)).background(Color(0xFF141D2C)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("⌕", color = HubMuted, fontSize = 19.sp)
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        activity.installedSearch,
                        { activity.installedSearch = it },
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
                Spacer(Modifier.width(18.dp))
                Text("Games only", color = Color.White)
                Switch(checked = activity.installedGamesOnly, onCheckedChange = { activity.installedGamesOnly = it })
            }
            Spacer(Modifier.height(10.dp))
            if (visible.isEmpty()) {
                Text(
                    if (activity.installedGamesOnly) "No games match. Turn off Games only to browse all apps."
                    else "No launchable apps match your search.",
                    color = HubMuted,
                    modifier = Modifier.padding(16.dp),
                )
                if (activity.installedGamesOnly) TextButton(onClick = { activity.installedGamesOnly = false }) { Text("Show all apps") }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(visible, key = { it.packageName }) { app ->
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
                        HubArtwork(app.cover, Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)), ContentScale.Crop, app.label)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(app.label, color = if (isLinked) HubMuted else Color.White, fontWeight = FontWeight.SemiBold)
                            Text(app.packageName, color = HubMuted, fontSize = 10.sp, maxLines = 1)
                        }
                        if (app.isGame) HubTag("GAME", HubAccent)
                        if (app.isBifrostPort) HubTag("BIFROST PORT", Color(0xFFB6C8FF))
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
        Column(Modifier.width(130.dp).fillMaxHeight().padding(start = 12.dp), verticalArrangement = Arrangement.Bottom) {
            Button(
                onClick = { activity.addInstalledApps(activity.selectedInstalledPackages) },
                enabled = activity.selectedInstalledPackages.isNotEmpty(),
            ) { Text("Add ${activity.selectedInstalledPackages.size}") }
        }
    }
}

private object HubArtworkCache {
    private val cache = object : LruCache<String, ImageBitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4 / 1024
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
