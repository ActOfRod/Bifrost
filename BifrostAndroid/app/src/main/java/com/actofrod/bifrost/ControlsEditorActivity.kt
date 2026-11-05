package com.actofrod.bifrost

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalControllerBinding
import java.io.File
import kotlin.math.abs
import kotlin.math.max

private val EditorBackground = Color(0xFF090D17)
private val EditorAccent = Color(0xFF83F0C9)
private val EditorPanel = Color(0xFF121A29)
private val EditorMuted = Color(0xFF9CA8BA)

class ControlsEditorActivity : ComponentActivity() {
    internal var document by mutableStateOf<ControlsDocument?>(null)
    internal var savedContents by mutableStateOf("")
    internal var selectedIndex by mutableStateOf(-1)
    internal var canvasWidthPx by mutableStateOf(1)
    internal var snapEnabled by mutableStateOf(true)
    internal var showSettings by mutableStateOf(false)
    internal var showControllerMap by mutableStateOf(false)
    internal var presetConfirmation by mutableStateOf<String?>(null)
    internal var discardConfirmation by mutableStateOf(false)
    internal var error by mutableStateOf<String?>(null)
    private var profileFile: File? = null
    private var templates = emptyMap<String, ControlsDocument>()

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        try {
            val entryId = intent.getStringExtra("entry_id") ?: throw IllegalArgumentException("Game entry is missing")
            val profileId = intent.getIntExtra("controls_profile_id", 0)
            require(profileId > 0) { "Controls profile is missing" }
            val entry = LibraryStore(File(filesDir, "library.json")).read().entries.firstOrNull { it.id == entryId }
                ?: throw IllegalArgumentException("Game entry is missing")
            require(entry.controlsProfileId == profileId) { "Controls profile does not match this game" }
            profileFile = ControlsProfile.getProfileFile(this, profileId)
            document = ControlsDocument.parse(profileFile!!.readText())
            savedContents = document!!.encode()
            templates = mapOf(
                "Gamepad" to readTemplate("controls-3.icp"),
                "Keyboard + mouse" to readTemplate("controls-4.icp"),
                "RTS" to readTemplate("controls-1.icp"),
            )
        } catch (exception: Exception) {
            error = exception.message ?: "Could not load controls"
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = EditorAccent,
                secondary = Color(0xFF9CAEFF),
                background = EditorBackground,
                surface = EditorPanel,
                onPrimary = Color(0xFF082117),
                onSurface = Color.White,
            )) {
                Surface(Modifier.fillMaxSize(), color = EditorBackground) {
                    if (document == null) ErrorScreen(error ?: "Could not load controls", ::finish)
                    else document?.let { ControlsEditorScreen(this, it) }
                }
            }
        }
    }

    private fun readTemplate(name: String): ControlsDocument =
        assets.open("inputcontrols/profiles/$name").bufferedReader().use { ControlsDocument.parse(it.readText()) }

    internal fun updateDocument(transform: (ControlsDocument) -> ControlsDocument) {
        document = document?.let(transform)
    }

    internal fun saveDocument() {
        val current = document ?: return
        try {
            profileFile?.parentFile?.mkdirs()
            profileFile?.writeText(current.encode())
            savedContents = current.encode()
            error = null
        } catch (exception: Exception) {
            error = exception.message ?: "Could not save controls"
        }
    }

    internal fun requestClose() {
        if (document?.encode() == savedContents) finish() else discardConfirmation = true
    }

    internal fun applyPreset(name: String) {
        val template = templates[name] ?: return
        updateDocument { it.applyPreset(template) }
        selectedIndex = -1
        presetConfirmation = null
    }

    internal fun testVibration(strength: Int) {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        val normalized = strength.coerceIn(1, 100)
        val duration = 10L + (30L * (normalized - 1) / 99)
        val amplitude = max(1, normalized * 255 / 100)
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
    }

    internal fun applyControllerMapping(keyCode: Int, binding: String?) {
        updateDocument { it.withControllerBinding(keyCode, binding) }
    }
}

@Composable
private fun ErrorScreen(message: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = Color.White)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun ControlsEditorScreen(activity: ControlsEditorActivity, document: ControlsDocument) {
    BackHandler { activity.requestClose() }
    Box(Modifier.fillMaxSize().background(EditorBackground)) {
        ControlsCanvas(activity, document, Modifier.fillMaxSize())
        document.elements.getOrNull(activity.selectedIndex)?.let { selected ->
            val canvasWidth = activity.canvasWidthPx.coerceAtLeast(1)
            val halfWidth = elementBounds(selected, max(1, canvasWidth / 100).toFloat()).first
            val centerX = selected.x * canvasWidth
            val leftSpace = (centerX - halfWidth).coerceAtLeast(0f)
            val rightSpace = (canvasWidth - centerX - halfWidth).coerceAtLeast(0f)
            val panelOnLeft = leftSpace >= rightSpace
            val availableSpace = if (panelOnLeft) leftSpace else rightSpace
            val availableWidth = with(LocalDensity.current) { availableSpace.toDp() }
            val panelWidth = (availableWidth - 16.dp).coerceAtLeast(1.dp).coerceAtMost(380.dp)
            ElementPanel(
                activity,
                document,
                Modifier
                    .align(if (panelOnLeft) Alignment.CenterStart else Alignment.CenterEnd)
                    .width(panelWidth)
                    .fillMaxHeight()
                    .padding(start = 8.dp, end = 8.dp, top = 64.dp, bottom = 8.dp),
            )
        }
        EditorToolbar(activity, Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp))
    }
    activity.presetConfirmation?.let { name ->
        AlertDialog(
            onDismissRequest = { activity.presetConfirmation = null },
            title = { Text("Apply $name preset?") },
            text = { Text("This replaces the current on-screen controls. Your profile settings and controller mapping are kept.") },
            confirmButton = { TextButton(onClick = { activity.applyPreset(name) }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { activity.presetConfirmation = null }) { Text("Cancel") } },
        )
    }
    if (activity.showSettings) SettingsDialog(activity, document)
    if (activity.showControllerMap) ControllerMappingDialog(activity, document)
    if (activity.discardConfirmation) {
        AlertDialog(
            onDismissRequest = { activity.discardConfirmation = false },
            title = { Text("Discard unsaved changes?") },
            text = { Text("Your controls will return to the last saved version.") },
            confirmButton = {
                TextButton(onClick = {
                    activity.discardConfirmation = false
                    activity.finish()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { activity.discardConfirmation = false }) { Text("Keep editing") } },
        )
    }
    activity.error?.let { message ->
        AlertDialog(
            onDismissRequest = { activity.error = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { activity.error = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun EditorToolbar(activity: ControlsEditorActivity, modifier: Modifier) {
    var addMenu by remember { mutableStateOf(false) }
    var presetMenu by remember { mutableStateOf(false) }
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(EditorPanel.copy(alpha = 0.82f))
            .clickable { }
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorActionButton("Back", "←", Modifier.weight(1f), onClick = activity::requestClose)
        Box(Modifier.weight(1f)) {
            EditorActionButton("Add", "+", Modifier.fillMaxWidth()) { addMenu = true }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                listOf("BUTTON" to "Button", "D_PAD" to "D-pad", "STICK" to "Stick", "TRACKPAD" to "Trackpad")
                    .forEach { (type, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                val elements = activity.document?.elements.orEmpty()
                                activity.updateDocument { it.addElement(type) }
                                activity.selectedIndex = elements.size
                                addMenu = false
                            },
                        )
                }
            }
        }
        Box(Modifier.weight(1f)) {
            EditorActionButton("Preset", "▤", Modifier.fillMaxWidth()) { presetMenu = true }
            DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                listOf("Gamepad", "Keyboard + mouse", "RTS").forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            activity.presetConfirmation = name
                            presetMenu = false
                        },
                    )
                }
            }
        }
        EditorActionButton("Snap", if (activity.snapEnabled) "✓" else "□", Modifier.weight(1f)) {
            activity.snapEnabled = !activity.snapEnabled
        }
        EditorActionButton("Settings", "⚙", Modifier.weight(1f)) { activity.showSettings = true }
        EditorActionButton("Controller", "🎮", Modifier.weight(1f)) { activity.showControllerMap = true }
        EditorActionButton("Save", "✓", Modifier.weight(1f), primary = true, onClick = activity::saveDocument)
    }
}

@Composable
private fun EditorActionButton(
    label: String,
    icon: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) EditorAccent else Color(0x667C8BA0))
            .clickable(onClick = onClick)
        .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, color = if (primary) Color(0xFF082117) else EditorAccent, fontSize = 12.sp, maxLines = 1)
        Spacer(Modifier.width(2.dp))
        Text(
            label,
            color = if (primary) Color(0xFF082117) else Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun ControlsCanvas(activity: ControlsEditorActivity, document: ControlsDocument, modifier: Modifier) {
    Canvas(
        modifier.onSizeChanged { canvasSize ->
            if (activity.canvasWidthPx != canvasSize.width) activity.canvasWidthPx = canvasSize.width
        }
            .pointerInput(activity.snapEnabled) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var previous = down.position
                    val canvasWidth = size.width.toInt().coerceAtLeast(1)
                    val canvasHeight = size.height.toInt().coerceAtLeast(1)
                    val hit = hitTest(document.elements, down.position.x, down.position.y, canvasWidth, canvasHeight)
                    activity.selectedIndex = hit
                    var pointer = down
                    while (pointer.pressed) {
                        val event = awaitPointerEvent()
                        pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (pointer.pressed && activity.selectedIndex >= 0) {
                            val current = activity.document?.elements?.getOrNull(activity.selectedIndex)
                            if (current != null) {
                                var x = (current.x + (pointer.position.x - previous.x) / size.width).coerceIn(0.02f, 0.98f)
                                var y = (current.y + (pointer.position.y - previous.y) / size.height).coerceIn(0.02f, 0.98f)
                                val bounds = elementBounds(current, max(1, canvasWidth / 100).toFloat())
                                val minX = (bounds.first / size.width).coerceAtMost(0.49f)
                                val minY = (bounds.second / size.height).coerceAtMost(0.49f)
                                if (activity.snapEnabled) {
                                    val snapped = ControlsDocument.snap(x, y, canvasWidth, canvasHeight)
                                    x = snapped.x
                                    y = snapped.y
                                }
                                x = x.coerceIn(minX, 1f - minX)
                                y = y.coerceIn(minY, 1f - minY)
                                val selected = activity.selectedIndex
                                activity.updateDocument { profile ->
                                    profile.updateElement(selected) { element -> element.update(x = x, y = y) }
                                }
                            }
                            pointer.consume()
                        }
                        previous = pointer.position
                    }
                }
            },
    ) {
        val widthPx = size.width
        val heightPx = size.height
        val snappingSize = max(1, widthPx.toInt() / 100).toFloat()
        if (activity.snapEnabled) {
            val gridColor = Color(0x204C5B70)
            var x = 0f
            while (x <= widthPx) {
                drawLine(gridColor, Offset(x, 0f), Offset(x, heightPx), 0.7f)
                x += snappingSize
            }
            var y = 0f
            while (y <= heightPx) {
                drawLine(gridColor, Offset(0f, y), Offset(widthPx, y), 0.7f)
                y += snappingSize
            }
        }
        document.elements.forEachIndexed { index, element ->
            val centerX = element.x * widthPx
            val centerY = element.y * heightPx
            val base = elementBounds(element, snappingSize)
            val halfWidth = base.first
            val halfHeight = base.second
            val color = if (element.hidden) EditorMuted.copy(alpha = 0.25f) else EditorAccent.copy(alpha = element.opacity)
            val topLeft = Offset(centerX - halfWidth, centerY - halfHeight)
            val boxSize = Size(halfWidth * 2, halfHeight * 2)
            when (element.type) {
                "BUTTON" -> when (element.shape) {
                    "RECT" -> drawRect(color, topLeft, boxSize)
                    "ROUND_RECT" -> drawRoundRect(color, topLeft, boxSize, androidx.compose.ui.geometry.CornerRadius(snappingSize))
                    "SQUARE" -> drawRect(color, topLeft, boxSize)
                    else -> drawCircle(color, radius = halfWidth, center = Offset(centerX, centerY))
                }
                "D_PAD" -> {
                    drawRect(color, Offset(centerX - halfWidth * 0.28f, centerY - halfHeight), Size(halfWidth * 0.56f, halfHeight * 2))
                    drawRect(color, Offset(centerX - halfWidth, centerY - halfHeight * 0.28f), Size(halfWidth * 2, halfHeight * 0.56f))
                }
                "STICK" -> {
                    drawCircle(color, radius = halfWidth, center = Offset(centerX, centerY), style = Stroke(snappingSize * 0.7f))
                    drawCircle(color.copy(alpha = color.alpha * 0.6f), radius = halfWidth * 0.38f, center = Offset(centerX, centerY))
                }
                "TRACKPAD" -> drawRoundRect(color, topLeft, boxSize, androidx.compose.ui.geometry.CornerRadius(snappingSize * 1.2f))
                else -> drawRoundRect(color, topLeft, boxSize, androidx.compose.ui.geometry.CornerRadius(snappingSize))
            }
            if (activity.selectedIndex == index) {
                drawRoundRect(EditorAccent, topLeft, boxSize, androidx.compose.ui.geometry.CornerRadius(snappingSize), style = Stroke(2f))
            }
            val label = canvasLabel(element)
            if (label.isNotEmpty()) {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.White.copy(alpha = if (element.hidden) 0.25f else 0.95f).toArgb()
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = (snappingSize * 1.1f).coerceIn(8f, 18f)
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                }
                val maxTextWidth = (boxSize.width - snappingSize * 0.8f).coerceAtLeast(1f)
                while (paint.textSize > 7f && paint.measureText(label) > maxTextWidth) {
                    paint.textSize -= 1f
                }
                val fittedLabel = ellipsizeLabel(label, paint, maxTextWidth)
                if (fittedLabel.isNotEmpty()) {
                    drawContext.canvas.nativeCanvas.drawText(
                        fittedLabel,
                        centerX,
                        centerY + paint.textSize * 0.35f,
                        paint,
                    )
                }
            }
            if (element.hidden) {
                drawLine(Color(0xFFE57373).copy(alpha = 0.8f), Offset(centerX - halfWidth, centerY + halfHeight), Offset(centerX + halfWidth, centerY - halfHeight), 2f)
            }
        }
    }
}

private fun canvasLabel(element: ControlsElement): String = when (element.type) {
    "D_PAD" -> ""
    "STICK" -> element.text.ifBlank {
        if (element.bindings.any { it.startsWith("GAMEPAD_RIGHT_THUMB_") }) "R-stick" else "L-stick"
    }
    "TRACKPAD" -> element.text.ifBlank { "Mouse" }
    else -> element.text.ifBlank {
        element.bindings.firstOrNull { it != "NONE" }?.let(::bindingLabel).orEmpty()
    }
}

private fun ellipsizeLabel(label: String, paint: android.graphics.Paint, maxWidth: Float): String {
    if (paint.measureText(label) <= maxWidth) return label
    val ellipsis = "…"
    var end = label.length
    while (end > 0 && paint.measureText(label.substring(0, end) + ellipsis) > maxWidth) end--
    return if (end == 0) "" else label.substring(0, end) + ellipsis
}

private fun elementBounds(element: ControlsElement, snappingSize: Float): Pair<Float, Float> = when (element.type) {
    "BUTTON" -> when (element.shape) {
        "RECT", "ROUND_RECT" -> (snappingSize.toInt() * 4) to (snappingSize.toInt() * 2)
        "SQUARE" -> (snappingSize.toInt() * 2.5f).toInt() to (snappingSize.toInt() * 2.5f).toInt()
        else -> (snappingSize.toInt() * 3) to (snappingSize.toInt() * 3)
    }
    "D_PAD" -> (snappingSize.toInt() * 7) to (snappingSize.toInt() * 7)
    "STICK", "TRACKPAD" -> (snappingSize.toInt() * 6) to (snappingSize.toInt() * 6)
    else -> (snappingSize.toInt() * 3) to (snappingSize.toInt() * 3)
}.let { (halfWidth, halfHeight) ->
    (halfWidth * element.scale).toInt().toFloat() to (halfHeight * element.scale).toInt().toFloat()
}

private fun hitTest(elements: List<ControlsElement>, x: Float, y: Float, width: Int, height: Int): Int {
    val snap = max(1, width / 100).toFloat()
    return elements.indices.reversed().firstOrNull { index ->
        val element = elements[index]
        val bounds = elementBounds(element, snap)
        val centerX = element.x * width
        val centerY = element.y * height
        abs(x - centerX) <= bounds.first && abs(y - centerY) <= bounds.second
    } ?: -1
}

private fun bindingLabel(name: String): String {
    return when (name) {
        "", "NONE" -> "None"
        "MOUSE_LEFT_BUTTON" -> "Left click"
        "MOUSE_RIGHT_BUTTON" -> "Right click"
        "MOUSE_MIDDLE_BUTTON" -> "Middle click"
        "MOUSE_SCROLL_UP" -> "Scroll up"
        "MOUSE_SCROLL_DOWN" -> "Scroll down"
        "MOUSE_MOVE_UP", "MOUSE_MOVE_RIGHT", "MOUSE_MOVE_DOWN", "MOUSE_MOVE_LEFT" -> "Move mouse"
        "GAMEPAD_BUTTON_A" -> "A"
        "GAMEPAD_BUTTON_B" -> "B"
        "GAMEPAD_BUTTON_X" -> "X"
        "GAMEPAD_BUTTON_Y" -> "Y"
        "GAMEPAD_BUTTON_L1" -> "L1"
        "GAMEPAD_BUTTON_R1" -> "R1"
        "GAMEPAD_BUTTON_L2" -> "L2"
        "GAMEPAD_BUTTON_R2" -> "R2"
        "GAMEPAD_BUTTON_L3" -> "L3"
        "GAMEPAD_BUTTON_R3" -> "R3"
        "GAMEPAD_BUTTON_SELECT" -> "Select"
        "GAMEPAD_BUTTON_START" -> "Start"
        "GAMEPAD_DPAD_UP" -> "D-pad Up"
        "GAMEPAD_DPAD_RIGHT" -> "D-pad Right"
        "GAMEPAD_DPAD_DOWN" -> "D-pad Down"
        "GAMEPAD_DPAD_LEFT" -> "D-pad Left"
        "GAMEPAD_LEFT_THUMB_UP" -> "Left stick Up"
        "GAMEPAD_LEFT_THUMB_RIGHT" -> "Left stick Right"
        "GAMEPAD_LEFT_THUMB_DOWN" -> "Left stick Down"
        "GAMEPAD_LEFT_THUMB_LEFT" -> "Left stick Left"
        "GAMEPAD_RIGHT_THUMB_UP" -> "Right stick Up"
        "GAMEPAD_RIGHT_THUMB_RIGHT" -> "Right stick Right"
        "GAMEPAD_RIGHT_THUMB_DOWN" -> "Right stick Down"
        "GAMEPAD_RIGHT_THUMB_LEFT" -> "Right stick Left"
        "KEY_UP" -> "Up arrow"
        "KEY_RIGHT" -> "Right arrow"
        "KEY_DOWN" -> "Down arrow"
        "KEY_LEFT" -> "Left arrow"
        "KEY_ENTER" -> "Enter"
        "KEY_ESC" -> "Esc"
        "KEY_BKSP" -> "Backspace"
        "KEY_DEL" -> "Delete"
        "KEY_INSERT" -> "Insert"
        "KEY_TAB" -> "Tab"
        "KEY_SPACE" -> "Space"
        "KEY_CTRL_L", "KEY_CTRL_R" -> "Ctrl"
        "KEY_SHIFT_L", "KEY_SHIFT_R" -> "Shift"
        "KEY_ALT_L", "KEY_ALT_R" -> "Alt"
        "KEY_HOME" -> "Home"
        "KEY_PRTSCN" -> "Print Screen"
        "KEY_PG_UP" -> "Page Up"
        "KEY_PG_DOWN" -> "Page Down"
        "KEY_END" -> "End"
        "KEY_CAPS_LOCK" -> "Caps Lock"
        "KEY_NUM_LOCK" -> "Num Lock"
        "KEY_VOL_UP" -> "Volume Up"
        "KEY_VOL_DOWN" -> "Volume Down"
        "KEY_BRACKET_LEFT" -> "["
        "KEY_BRACKET_RIGHT" -> "]"
        "KEY_BACKSLASH" -> "\\"
        "KEY_SLASH" -> "/"
        "KEY_SEMICOLON" -> ";"
        "KEY_COMMA" -> ","
        "KEY_PERIOD" -> "."
        "KEY_APOSTROPHE" -> "'"
        "KEY_KP_ADD" -> "Numpad +"
        else -> when {
            name.startsWith("KEY_KP_") -> "Numpad ${name.removePrefix("KEY_KP_")}"
            name.startsWith("KEY_") && name.length == 5 -> name.last().toString()
            name.startsWith("KEY_") -> name.removePrefix("KEY_").split('_')
                .joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
            else -> name
        }
    }
}

@Composable
private fun ElementPanel(activity: ControlsEditorActivity, document: ControlsDocument, modifier: Modifier) {
    val selected = document.elements.getOrNull(activity.selectedIndex)
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(EditorPanel.copy(alpha = 0.96f)).clickable { }) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Selected control", Modifier.weight(1f), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { activity.selectedIndex = -1 }) { Text("Close") }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selected == null) {
                Text("Select a control on the canvas to edit it.", color = EditorMuted)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(elementTypeLabel(selected.type), color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Show on screen", color = EditorMuted, fontSize = 12.sp)
                }
                Switch(
                    checked = !selected.hidden,
                    onCheckedChange = { checked ->
                        val index = activity.selectedIndex
                        activity.updateDocument { it.updateElement(index) { item -> item.update(hidden = !checked) } }
                    },
                )
            }
            if (selected.type == "BUTTON") {
                Text("Shape", color = EditorMuted)
                EnumPicker(
                    selected = selected.shape,
                    options = listOf("CIRCLE", "RECT", "ROUND_RECT", "SQUARE"),
                    onSelected = { shape ->
                        val index = activity.selectedIndex
                        activity.updateDocument { it.updateElement(index) { item -> item.update(shape = shape) } }
                    },
                )
            }
            if (selected.type in listOf("BUTTON", "D_PAD", "STICK", "TRACKPAD")) {
                val labels = if (selected.type == "BUTTON") listOf("Slot 1", "Slot 2", "Slot 3", "Slot 4")
                else listOf("Up", "Right", "Down", "Left")
                repeat(4) { slot ->
                    BindingPicker(
                        label = labels[slot],
                        value = selected.bindings.getOrElse(slot) { "NONE" },
                        groups = setOf("Gamepad", "Keyboard", "Mouse"),
                        onSelected = { binding ->
                            val index = activity.selectedIndex
                            activity.updateDocument { profile ->
                                profile.updateElement(index) { item ->
                                    val next = item.bindings.toMutableList().also {
                                        while (it.size < 4) it += "NONE"
                                        it[slot] = binding
                                    }
                                    item.update(bindings = next)
                                }
                            }
                        },
                    )
                }
            }
            Text("Size · ${"%.1f".format(selected.scale)}×", color = EditorMuted)
            Slider(value = selected.scale.coerceIn(0.5f, 3f), onValueChange = { value ->
                val index = activity.selectedIndex
                activity.updateDocument { it.updateElement(index) { item -> item.update(scale = value) } }
            }, valueRange = 0.5f..3f)
            Text("Opacity · ${(selected.opacity * 100).toInt()}%", color = EditorMuted)
            Slider(value = selected.opacity.coerceIn(0.1f, 1f), onValueChange = { value ->
                val index = activity.selectedIndex
                activity.updateDocument { it.updateElement(index) { item -> item.update(opacity = value) } }
            }, valueRange = 0.1f..1f)
            if (selected.type == "BUTTON") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Toggle mode", Modifier.weight(1f), color = Color.White)
                    Switch(
                        checked = selected.toggleSwitch,
                        onCheckedChange = { enabled ->
                            val index = activity.selectedIndex
                            activity.updateDocument { it.updateElement(index) { item -> item.update(toggleSwitch = enabled) } }
                        },
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    activity.updateDocument { it.deleteElement(activity.selectedIndex) }
                    activity.selectedIndex = -1
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Delete control") }
        }
    }
}

@Composable
private fun EnumPicker(selected: String, options: List<String>, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(selected.replace('_', ' ')) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option.replace('_', ' ')) }, onClick = {
                    onSelected(option)
                    expanded = false
                })
            }
        }
    }
}

@Composable
private fun BindingPicker(
    label: String,
    value: String,
    groups: Set<String>,
    passThrough: Boolean = false,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        if (label.isNotEmpty()) Text(label, color = EditorMuted, fontSize = 12.sp)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (passThrough && value == "NONE") "Pass through" else bindingLabel(value), maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                val values = Binding.values().filter { binding ->
                    (binding.name == "NONE" && !passThrough) ||
                        (binding.isGamepad && "Gamepad" in groups) ||
                        (binding.isKeyboard && "Keyboard" in groups) ||
                        (binding.isMouse && "Mouse" in groups)
                }
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (passThrough) {
                        DropdownMenuItem(text = { Text("Pass through") }, onClick = {
                            onSelected("NONE")
                            expanded = false
                        })
                    }
                    listOf("Gamepad", "Keyboard", "Mouse").filter { it in groups }.forEach { group ->
                        Text(group, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = EditorAccent, fontWeight = FontWeight.Bold)
                        values.filter { bindingGroup(it) == group }.forEach { binding ->
                            DropdownMenuItem(
                                text = { Text(bindingLabel(binding.name)) },
                                onClick = {
                                    onSelected(binding.name)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun bindingGroup(binding: Binding): String = when {
    binding.isGamepad -> "Gamepad"
    binding.isMouse -> "Mouse"
    else -> "Keyboard"
}

private fun elementTypeLabel(type: String): String = when (type) {
    "BUTTON" -> "Button"
    "D_PAD" -> "D-pad"
    "STICK" -> "Stick"
    "TRACKPAD" -> "Trackpad"
    else -> type
}

@Composable
private fun SettingsDialog(activity: ControlsEditorActivity, document: ControlsDocument) {
    AlertDialog(
        onDismissRequest = { activity.showSettings = false },
        title = { Text("Control settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Haptic feedback", Modifier.weight(1f), color = Color.White)
                    Switch(
                        checked = document.haptics,
                        onCheckedChange = { enabled -> activity.updateDocument { profile -> profile.withHaptics(enabled) } },
                    )
                }
                Text("Strength · ${document.hapticStrength}", color = EditorMuted)
                Slider(
                    value = document.hapticStrength.toFloat(),
                    onValueChange = { strength -> activity.updateDocument { profile -> profile.withHapticStrength(strength.toInt()) } },
                    valueRange = 1f..100f,
                )
                OutlinedButton(onClick = { activity.testVibration(document.hapticStrength) }) { Text("Test vibration") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hide when a controller is connected", Modifier.weight(1f), color = Color.White)
                    Switch(
                        checked = document.autoHideWithController,
                        onCheckedChange = { enabled -> activity.updateDocument { it.withAutoHideWithController(enabled) } },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { activity.showSettings = false }) { Text("Done") } },
    )
}

private data class ControllerRow(val label: String, val keyCode: Int)

private val controllerRows = listOf(
    ControllerRow("A", KeyEvent.KEYCODE_BUTTON_A),
    ControllerRow("B", KeyEvent.KEYCODE_BUTTON_B),
    ControllerRow("X", KeyEvent.KEYCODE_BUTTON_X),
    ControllerRow("Y", KeyEvent.KEYCODE_BUTTON_Y),
    ControllerRow("L1", KeyEvent.KEYCODE_BUTTON_L1),
    ControllerRow("R1", KeyEvent.KEYCODE_BUTTON_R1),
    ControllerRow("L2", KeyEvent.KEYCODE_BUTTON_L2),
    ControllerRow("R2", KeyEvent.KEYCODE_BUTTON_R2),
    ControllerRow("Start", KeyEvent.KEYCODE_BUTTON_START),
    ControllerRow("Select", KeyEvent.KEYCODE_BUTTON_SELECT),
    ControllerRow("L3", KeyEvent.KEYCODE_BUTTON_THUMBL),
    ControllerRow("R3", KeyEvent.KEYCODE_BUTTON_THUMBR),
    ControllerRow("D-pad Up", KeyEvent.KEYCODE_DPAD_UP),
    ControllerRow("D-pad Right", KeyEvent.KEYCODE_DPAD_RIGHT),
    ControllerRow("D-pad Down", KeyEvent.KEYCODE_DPAD_DOWN),
    ControllerRow("D-pad Left", KeyEvent.KEYCODE_DPAD_LEFT),
    ControllerRow("Left stick Up", ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Y, (-1).toByte())),
    ControllerRow("Left stick Right", ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, 1.toByte())),
    ControllerRow("Left stick Down", ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Y, 1.toByte())),
    ControllerRow("Left stick Left", ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, (-1).toByte())),
    ControllerRow("Right stick Up", ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_RZ, (-1).toByte())),
    ControllerRow("Right stick Right", ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Z, 1.toByte())),
    ControllerRow("Right stick Down", ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_RZ, 1.toByte())),
    ControllerRow("Right stick Left", ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Z, (-1).toByte())),
)

@Composable
private fun ControllerMappingDialog(activity: ControlsEditorActivity, document: ControlsDocument) {
    val mapping = document.controllerBindings().toMap()
    Dialog(
        onDismissRequest = { activity.showControllerMap = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.9f).clip(RoundedCornerShape(16.dp)).background(EditorPanel).padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Controller mapping", Modifier.weight(1f), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { activity.showControllerMap = false }) { Text("Done") }
            }
            Text("Pass through keeps the original gamepad input unchanged.", color = EditorMuted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                controllerRows.forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(row.label, Modifier.weight(1f), color = Color.White)
                        BindingPicker(
                            label = "",
                            value = mapping[row.keyCode] ?: "NONE",
                            groups = setOf("Keyboard", "Mouse"),
                            passThrough = true,
                            onSelected = { binding ->
                                activity.applyControllerMapping(row.keyCode, if (binding == "NONE") null else binding)
                            },
                            modifier = Modifier.width(220.dp),
                        )
                    }
                }
            }
        }
    }
}
