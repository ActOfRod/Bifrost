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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
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
    Row(Modifier.fillMaxSize().background(EditorBackground)) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp)) {
            EditorToolbar(activity)
            Spacer(Modifier.height(10.dp))
            ControlsCanvas(activity, document, Modifier.fillMaxSize().weight(1f))
        }
        ElementPanel(activity, document, Modifier.width(300.dp).fillMaxHeight())
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
private fun EditorToolbar(activity: ControlsEditorActivity) {
    var addMenu by remember { mutableStateOf(false) }
    var presetMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorMenuButton("Back", onClick = activity::requestClose)
        Box {
            EditorMenuButton("Add", onClick = { addMenu = true })
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
        Box {
            EditorMenuButton("Preset", onClick = { presetMenu = true })
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
        EditorMenuButton(if (activity.snapEnabled) "Snap: On" else "Snap: Off") {
            activity.snapEnabled = !activity.snapEnabled
        }
        EditorMenuButton("Settings", onClick = { activity.showSettings = true })
        EditorMenuButton("Controller", onClick = { activity.showControllerMap = true })
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = activity::saveDocument,
            colors = ButtonDefaults.buttonColors(containerColor = EditorAccent, contentColor = Color(0xFF082117)),
        ) { Text("Save", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun EditorMenuButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 11.dp, vertical = 8.dp)) {
        Text(label, maxLines = 1)
    }
}

@Composable
private fun ControlsCanvas(activity: ControlsEditorActivity, document: ControlsDocument, modifier: Modifier) {
    Canvas(
        modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF070A12))
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
            val label = element.text.ifBlank { bindingLabel(element.bindings.firstOrNull().orEmpty()) }
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.White.copy(alpha = if (element.hidden) 0.25f else 0.95f).toArgb()
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = max(11f, snappingSize * 1.25f)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
            drawContext.canvas.nativeCanvas.drawText(label, centerX, centerY + paint.textSize * 0.35f, paint)
            if (element.hidden) {
                drawLine(Color(0xFFE57373).copy(alpha = 0.8f), Offset(centerX - halfWidth, centerY + halfHeight), Offset(centerX + halfWidth, centerY - halfHeight), 2f)
            }
        }
    }
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
    if (name.isEmpty() || name == "NONE") return "—"
    return when (name) {
        "MOUSE_LEFT_BUTTON" -> "LMB"
        "MOUSE_RIGHT_BUTTON" -> "RMB"
        "MOUSE_MIDDLE_BUTTON" -> "MMB"
        "GAMEPAD_BUTTON_A", "GAMEPAD_BUTTON_B", "GAMEPAD_BUTTON_X", "GAMEPAD_BUTTON_Y" -> name.substringAfterLast('_')
        else -> runCatching { Binding.valueOf(name).toString() }.getOrDefault(name)
    }
}

@Composable
private fun ElementPanel(activity: ControlsEditorActivity, document: ControlsDocument, modifier: Modifier) {
    val selected = document.elements.getOrNull(activity.selectedIndex)
    Column(
        modifier.background(EditorPanel).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Selected control", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (selected == null) {
            Text("Select a control on the canvas to edit it.", color = EditorMuted)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(selected.type.replace('_', ' '), color = Color.White, fontWeight = FontWeight.SemiBold)
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
                                text = { Text(if (binding.name == "NONE") "None" else binding.toString()) },
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
    ControllerRow("D-pad up", KeyEvent.KEYCODE_DPAD_UP),
    ControllerRow("D-pad right", KeyEvent.KEYCODE_DPAD_RIGHT),
    ControllerRow("D-pad down", KeyEvent.KEYCODE_DPAD_DOWN),
    ControllerRow("D-pad left", KeyEvent.KEYCODE_DPAD_LEFT),
    ControllerRow("Left stick up", ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Y, (-1).toByte())),
    ControllerRow("Left stick right", ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, 1.toByte())),
    ControllerRow("Left stick down", ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Y, 1.toByte())),
    ControllerRow("Left stick left", ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, (-1).toByte())),
    ControllerRow("Right stick up", ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_RZ, (-1).toByte())),
    ControllerRow("Right stick right", ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Z, 1.toByte())),
    ControllerRow("Right stick down", ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_RZ, 1.toByte())),
    ControllerRow("Right stick left", ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Z, (-1).toByte())),
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
