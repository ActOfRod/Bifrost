package com.actofrod.bifrost

import kotlin.math.max
import kotlin.math.floor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class NormalizedPosition(val x: Float, val y: Float)

enum class ControllerStyle(val id: String) {
    XBOX("xbox"),
    NINTENDO("nintendo"),
    PLAYSTATION("playstation");

    companion object {
        fun fromId(id: String?, default: ControllerStyle = XBOX): ControllerStyle =
            values().firstOrNull { it.id == id } ?: default
    }
}

enum class ControllerSymbol { CROSS, CIRCLE, SQUARE, TRIANGLE }

data class ControllerButtonPresentation(
    val label: String? = null,
    val accent: Long? = null,
    val symbol: ControllerSymbol? = null,
)

internal fun controllerButtonPresentation(
    style: ControllerStyle,
    binding: String,
): ControllerButtonPresentation? {
    val face = when (binding) {
        "GAMEPAD_BUTTON_A" -> 0
        "GAMEPAD_BUTTON_B" -> 1
        "GAMEPAD_BUTTON_X" -> 2
        "GAMEPAD_BUTTON_Y" -> 3
        else -> -1
    }
    if (face >= 0) {
        return when (style) {
            ControllerStyle.XBOX -> ControllerButtonPresentation(
                label = listOf("A", "B", "X", "Y")[face],
                accent = listOf(0xFF5DC21EL, 0xFFE8473FL, 0xFF2D7FF9L, 0xFFF2C12EL)[face],
            )
            ControllerStyle.NINTENDO -> ControllerButtonPresentation(
                label = listOf("B", "A", "Y", "X")[face],
            )
            ControllerStyle.PLAYSTATION -> ControllerButtonPresentation(
                accent = listOf(0xFF7FA7E8L, 0xFFE86B74L, 0xFFD58CC6L, 0xFF3FC5A0L)[face],
                symbol = listOf(
                    ControllerSymbol.CROSS,
                    ControllerSymbol.CIRCLE,
                    ControllerSymbol.SQUARE,
                    ControllerSymbol.TRIANGLE,
                )[face],
            )
        }
    }
    val label = when (binding) {
        "GAMEPAD_BUTTON_L1" -> if (style == ControllerStyle.NINTENDO) "L" else if (style == ControllerStyle.PLAYSTATION) "L1" else "LB"
        "GAMEPAD_BUTTON_R1" -> if (style == ControllerStyle.NINTENDO) "R" else if (style == ControllerStyle.PLAYSTATION) "R1" else "RB"
        "GAMEPAD_BUTTON_L2" -> if (style == ControllerStyle.NINTENDO) "ZL" else if (style == ControllerStyle.PLAYSTATION) "L2" else "LT"
        "GAMEPAD_BUTTON_R2" -> if (style == ControllerStyle.NINTENDO) "ZR" else if (style == ControllerStyle.PLAYSTATION) "R2" else "RT"
        "GAMEPAD_BUTTON_L3" -> if (style == ControllerStyle.PLAYSTATION) "L3" else "LS"
        "GAMEPAD_BUTTON_R3" -> if (style == ControllerStyle.PLAYSTATION) "R3" else "RS"
        "GAMEPAD_BUTTON_SELECT" -> when (style) {
            ControllerStyle.XBOX -> "VIEW"
            ControllerStyle.NINTENDO -> "−"
            ControllerStyle.PLAYSTATION -> "SHARE"
        }
        "GAMEPAD_BUTTON_START" -> when (style) {
            ControllerStyle.XBOX -> "MENU"
            ControllerStyle.NINTENDO -> "+"
            ControllerStyle.PLAYSTATION -> "OPTIONS"
        }
        else -> return null
    }
    return ControllerButtonPresentation(label = label)
}

data class ControlsElement(val json: JsonObject) {
    val type: String get() = json.string("type")
    val shape: String get() = json.string("shape", "CIRCLE")
    val bindings: List<String> get() = json.array("bindings").mapNotNull { it.stringOrNull() }
    val scale: Float get() = json.float("scale", 1f)
    val opacity: Float get() = json.float("opacity", 1f)
    val x: Float get() = json.float("x", 0.5f)
    val y: Float get() = json.float("y", 0.5f)
    val hidden: Boolean get() = json.boolean("hidden", false)
    val toggleSwitch: Boolean get() = json.boolean("toggleSwitch", false)
    val text: String get() = json.string("text")

    fun update(
        type: String = this.type,
        shape: String = this.shape,
        bindings: List<String> = this.bindings,
        scale: Float = this.scale,
        opacity: Float = this.opacity,
        x: Float = this.x,
        y: Float = this.y,
        hidden: Boolean = this.hidden,
        toggleSwitch: Boolean = this.toggleSwitch,
        text: String = this.text,
    ): ControlsElement = ControlsElement(json.toMutableMap().apply {
        put("type", JsonPrimitive(type))
        put("shape", JsonPrimitive(shape))
        put("bindings", JsonArray(bindings.map(::JsonPrimitive)))
        put("scale", JsonPrimitive(scale))
        put("opacity", JsonPrimitive(opacity))
        put("x", JsonPrimitive(x))
        put("y", JsonPrimitive(y))
        if (hidden) put("hidden", JsonPrimitive(true)) else remove("hidden")
        put("toggleSwitch", JsonPrimitive(toggleSwitch))
        put("text", JsonPrimitive(text))
    }.let(::JsonObject))

    companion object {
        fun create(type: String, x: Float = 0.5f, y: Float = 0.5f): ControlsElement {
            val bindings = when (type) {
                "BUTTON" -> listOf("GAMEPAD_BUTTON_A", "NONE", "NONE", "NONE")
                "D_PAD" -> listOf("GAMEPAD_DPAD_UP", "GAMEPAD_DPAD_RIGHT", "GAMEPAD_DPAD_DOWN", "GAMEPAD_DPAD_LEFT")
                "STICK" -> listOf("GAMEPAD_LEFT_THUMB_UP", "GAMEPAD_LEFT_THUMB_RIGHT", "GAMEPAD_LEFT_THUMB_DOWN", "GAMEPAD_LEFT_THUMB_LEFT")
                "TRACKPAD" -> listOf("MOUSE_MOVE_UP", "MOUSE_MOVE_RIGHT", "MOUSE_MOVE_DOWN", "MOUSE_MOVE_LEFT")
                else -> throw IllegalArgumentException("Unsupported control type: $type")
            }
            return ControlsElement(buildJsonObject {
                put("type", type)
                put("shape", if (type == "TRACKPAD") "ROUND_RECT" else "CIRCLE")
                put("bindings", JsonArray(bindings.map(::JsonPrimitive)))
                put("scale", 1f)
                put("x", x)
                put("y", y)
                put("toggleSwitch", false)
                put("text", "")
                put("iconId", 0)
            })
        }
    }
}

data class ControlsDocument(val json: JsonObject) {
    val id: Int get() = json.int("id", 0)
    val name: String get() = json.string("name")
    val elements: List<ControlsElement>
        get() = json.array("elements").mapNotNull { runCatching { ControlsElement(it.jsonObject) }.getOrNull() }
    val haptics: Boolean get() = json.boolean("haptics", true)
    val hapticsUserSet: Boolean get() = json.boolean("hapticsUserSet", false)
    val hapticStrength: Int get() = json.int("hapticStrength", 50).coerceIn(1, 100)
    val autoHideWithController: Boolean get() = json.boolean("autoHideWithController", true)

    fun encode(): String = JsonObject(json.toMap()).toString()

    fun applyPreset(template: ControlsDocument): ControlsDocument {
        val updated = withRootField("elements", template.json["elements"] ?: JsonArray(emptyList()))
        return template.json["presetVersion"]?.let { updated.withRootField("presetVersion", it) } ?: updated
    }

    fun migrateLegacyHaptics(): ControlsDocument =
        if (json["haptics"]?.jsonPrimitive?.content == "false" && "hapticsUserSet" !in json) {
            withRootField("haptics", JsonPrimitive(true))
        } else {
            this
        }

    fun migrateLegacyPreset(): ControlsDocument {
        if ("presetVersion" in json) return this
        val replacement = when {
            elementsMatch(elements, oldControls3Elements()) -> xboxOverlayPreset(id, name)
            elementsMatch(elements, oldSplitControllerElements()) -> splitControllerPreset(id, name)
            else -> return this
        }
        return ControlsDocument(json.toMutableMap().apply {
            put("elements", replacement.json["elements"] ?: JsonArray(emptyList()))
            put("presetVersion", JsonPrimitive(2))
        }.let(::JsonObject))
    }

    fun addElement(type: String, x: Float = 0.5f, y: Float = 0.5f): ControlsDocument =
        withElements(elements + ControlsElement.create(type, x, y))

    fun deleteElement(index: Int): ControlsDocument =
        if (index !in elements.indices) this else withElements(elements.filterIndexed { elementIndex, _ -> elementIndex != index })

    fun updateElement(index: Int, transform: (ControlsElement) -> ControlsElement): ControlsDocument {
        val current = elements
        if (index !in current.indices) return this
        return withElements(current.mapIndexed { elementIndex, element ->
            if (elementIndex == index) transform(element) else element
        })
    }

    fun withHaptics(enabled: Boolean): ControlsDocument =
        withRootField("haptics", JsonPrimitive(enabled))
            .withRootField("hapticsUserSet", JsonPrimitive(true))

    fun withHapticStrength(strength: Int): ControlsDocument =
        withRootField("hapticStrength", JsonPrimitive(strength.coerceIn(1, 100)))

    fun withAutoHideWithController(enabled: Boolean): ControlsDocument =
        withRootField("autoHideWithController", JsonPrimitive(enabled))

    fun controllerBindings(): List<Pair<Int, String>> {
        val wildcard = json.array("controllers").mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            .firstOrNull { it.string("id") == "*" } ?: return emptyList()
        return wildcard.array("controllerBindings").mapNotNull { entry ->
            val binding = runCatching { entry.jsonObject }.getOrNull() ?: return@mapNotNull null
            val keyCode = binding.int("keyCode", Int.MIN_VALUE)
            val name = binding.string("binding")
            if (keyCode == Int.MIN_VALUE || name.isEmpty()) null else keyCode to name
        }
    }

    fun withControllerBinding(keyCode: Int, binding: String?): ControlsDocument {
        val controllers = json.array("controllers").mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        val wildcard = controllers.firstOrNull { it.string("id") == "*" }
        val existingBindings = wildcard?.array("controllerBindings").orEmpty().mapNotNull {
            runCatching { it.jsonObject }.getOrNull()
        }
        val nextBindings = existingBindings.filterNot { it.int("keyCode", Int.MIN_VALUE) == keyCode }.toMutableList()
        if (binding != null) {
            nextBindings += buildJsonObject {
                put("keyCode", keyCode)
                put("binding", binding)
            }
        }
        val nextControllers = controllers.filterNot { it.string("id") == "*" }.toMutableList()
        if (nextBindings.isNotEmpty()) {
            val base = wildcard ?: buildJsonObject {
                put("id", "*")
                put("name", "Bifrost")
            }
            nextControllers += JsonObject(base.toMutableMap().apply {
                put("controllerBindings", JsonArray(nextBindings))
            })
        }
        return if (nextControllers.isEmpty()) {
            ControlsDocument(json.toMutableMap().apply { remove("controllers") }.let(::JsonObject))
        } else {
            withRootField("controllers", JsonArray(nextControllers))
        }
    }

    private fun withElements(elements: List<ControlsElement>): ControlsDocument =
        withRootField("elements", JsonArray(elements.map { it.json }))

    private fun withRootField(key: String, value: kotlinx.serialization.json.JsonElement): ControlsDocument =
        ControlsDocument(JsonObject(json.toMutableMap().apply { put(key, value) }))

    companion object {
        fun parse(contents: String): ControlsDocument =
            ControlsDocument(Json.parseToJsonElement(contents).jsonObject)

        fun create(id: Int, name: String): ControlsDocument = ControlsDocument(buildJsonObject {
            put("id", id)
            put("name", name)
            put("cursorSpeed", 1f)
            put("elements", JsonArray(emptyList()))
        })

        private fun presetElement(
            type: String,
            x: Float,
            y: Float,
            scale: Float,
            bindings: List<String>,
            shape: String = "CIRCLE",
        ): ControlsElement =
            ControlsElement.create(type, x, y).update(shape = shape, scale = scale, bindings = bindings)

        private fun preset(id: Int, name: String, elements: List<ControlsElement>): ControlsDocument =
            ControlsDocument(create(id, name).json.toMutableMap().apply {
                put("elements", JsonArray(elements.map { it.json }))
                put("presetVersion", JsonPrimitive(2))
            }.let(::JsonObject))

        fun xboxOverlayPreset(id: Int = 0, name: String = "Virtual Gamepad"): ControlsDocument {
            val elements = listOf(
                presetElement("STICK", 0.10f, 0.62f, 1.0f, listOf("GAMEPAD_LEFT_THUMB_UP", "GAMEPAD_LEFT_THUMB_RIGHT", "GAMEPAD_LEFT_THUMB_DOWN", "GAMEPAD_LEFT_THUMB_LEFT")),
                presetElement("D_PAD", 0.24f, 0.84f, 0.85f, listOf("GAMEPAD_DPAD_UP", "GAMEPAD_DPAD_RIGHT", "GAMEPAD_DPAD_DOWN", "GAMEPAD_DPAD_LEFT")),
                presetElement("BUTTON", 0.88f, 0.50f, 0.8f, listOf("GAMEPAD_BUTTON_Y", "NONE", "NONE", "NONE")),
                presetElement("BUTTON", 0.88f, 0.74f, 0.8f, listOf("GAMEPAD_BUTTON_A", "NONE", "NONE", "NONE")),
                presetElement("BUTTON", 0.82f, 0.62f, 0.8f, listOf("GAMEPAD_BUTTON_X", "NONE", "NONE", "NONE")),
                presetElement("BUTTON", 0.94f, 0.62f, 0.8f, listOf("GAMEPAD_BUTTON_B", "NONE", "NONE", "NONE")),
                presetElement("STICK", 0.74f, 0.84f, 0.85f, listOf("GAMEPAD_RIGHT_THUMB_UP", "GAMEPAD_RIGHT_THUMB_RIGHT", "GAMEPAD_RIGHT_THUMB_DOWN", "GAMEPAD_RIGHT_THUMB_LEFT")),
                presetElement("BUTTON", 0.08f, 0.30f, 0.7f, listOf("GAMEPAD_BUTTON_L1", "NONE", "NONE", "NONE"), "ROUND_RECT"),
                presetElement("BUTTON", 0.08f, 0.15f, 0.7f, listOf("GAMEPAD_BUTTON_L2", "NONE", "NONE", "NONE"), "ROUND_RECT"),
                presetElement("BUTTON", 0.92f, 0.30f, 0.7f, listOf("GAMEPAD_BUTTON_R1", "NONE", "NONE", "NONE"), "ROUND_RECT"),
                presetElement("BUTTON", 0.92f, 0.15f, 0.7f, listOf("GAMEPAD_BUTTON_R2", "NONE", "NONE", "NONE"), "ROUND_RECT"),
                presetElement("BUTTON", 0.45f, 0.92f, 0.55f, listOf("GAMEPAD_BUTTON_SELECT", "NONE", "NONE", "NONE"), "ROUND_RECT"),
                presetElement("BUTTON", 0.55f, 0.92f, 0.55f, listOf("GAMEPAD_BUTTON_START", "NONE", "NONE", "NONE"), "ROUND_RECT"),
                presetElement("BUTTON", 0.36f, 0.92f, 0.55f, listOf("GAMEPAD_BUTTON_L3", "NONE", "NONE", "NONE")),
                presetElement("BUTTON", 0.64f, 0.92f, 0.55f, listOf("GAMEPAD_BUTTON_R3", "NONE", "NONE", "NONE")),
            )
            return preset(id, name, elements)
        }

        fun splitControllerPreset(id: Int = 0, name: String = "Split-Controller"): ControlsDocument {
            val elements = listOf(
                presetElement("D_PAD", 0.12f, 0.55f, 0.9f, listOf("GAMEPAD_DPAD_UP", "GAMEPAD_DPAD_RIGHT", "GAMEPAD_DPAD_DOWN", "GAMEPAD_DPAD_LEFT")),
                presetElement("STICK", 0.32f, 0.56f, 0.76f, listOf("GAMEPAD_LEFT_THUMB_UP", "GAMEPAD_LEFT_THUMB_RIGHT", "GAMEPAD_LEFT_THUMB_DOWN", "GAMEPAD_LEFT_THUMB_LEFT")),
                presetElement("STICK", 0.68f, 0.56f, 0.76f, listOf("GAMEPAD_RIGHT_THUMB_UP", "GAMEPAD_RIGHT_THUMB_RIGHT", "GAMEPAD_RIGHT_THUMB_DOWN", "GAMEPAD_RIGHT_THUMB_LEFT")),
                presetElement("BUTTON", 0.83f, 0.40f, 0.62f, listOf("GAMEPAD_BUTTON_Y", "NONE", "NONE", "NONE")),
                presetElement("BUTTON", 0.90f, 0.55f, 0.62f, listOf("GAMEPAD_BUTTON_B", "NONE", "NONE", "NONE")),
                presetElement("BUTTON", 0.76f, 0.55f, 0.62f, listOf("GAMEPAD_BUTTON_X", "NONE", "NONE", "NONE")),
                presetElement("BUTTON", 0.83f, 0.70f, 0.62f, listOf("GAMEPAD_BUTTON_A", "NONE", "NONE", "NONE")),
                presetElement("BUTTON", 0.14f, 0.18f, 0.5f, listOf("GAMEPAD_BUTTON_L1", "NONE", "NONE", "NONE"), "ROUND_RECT"),
                presetElement("BUTTON", 0.29f, 0.18f, 0.5f, listOf("GAMEPAD_BUTTON_L2", "NONE", "NONE", "NONE"), "ROUND_RECT"),
                presetElement("BUTTON", 0.71f, 0.18f, 0.5f, listOf("GAMEPAD_BUTTON_R1", "NONE", "NONE", "NONE"), "ROUND_RECT"),
                presetElement("BUTTON", 0.86f, 0.18f, 0.5f, listOf("GAMEPAD_BUTTON_R2", "NONE", "NONE", "NONE"), "ROUND_RECT"),
                presetElement("BUTTON", 0.46f, 0.18f, 0.42f, listOf("GAMEPAD_BUTTON_SELECT", "NONE", "NONE", "NONE"), "ROUND_RECT"),
                presetElement("BUTTON", 0.54f, 0.18f, 0.42f, listOf("GAMEPAD_BUTTON_START", "NONE", "NONE", "NONE"), "ROUND_RECT"),
            )
            return preset(id, name, elements)
        }

        internal fun oldControls3Elements(): List<ControlsElement> = listOf(
            presetElement("D_PAD", 0.21568628f, 0.35555556f, 0.85f, listOf("GAMEPAD_DPAD_UP", "GAMEPAD_DPAD_RIGHT", "GAMEPAD_DPAD_DOWN", "GAMEPAD_DPAD_LEFT")),
            presetElement("BUTTON", 0.813317f, 0.73333335f, 1f, listOf("GAMEPAD_BUTTON_X", "NONE", "NONE", "NONE")),
            presetElement("BUTTON", 0.8721405f, 0.6f, 1f, listOf("GAMEPAD_BUTTON_Y", "NONE", "NONE", "NONE")),
            presetElement("BUTTON", 0.8721405f, 0.8666667f, 1f, listOf("GAMEPAD_BUTTON_A", "NONE", "NONE", "NONE")),
            presetElement("BUTTON", 0.93096405f, 0.73333335f, 1f, listOf("GAMEPAD_BUTTON_B", "NONE", "NONE", "NONE")),
            presetElement("BUTTON", 0.92156863f, 0.2888889f, 1f, listOf("GAMEPAD_BUTTON_R2", "NONE", "NONE", "NONE"), "RECT"),
            presetElement("BUTTON", 0.92156863f, 0.42222223f, 1f, listOf("GAMEPAD_BUTTON_R1", "NONE", "NONE", "NONE"), "RECT"),
            presetElement("BUTTON", 0.078431375f, 0.42222223f, 1f, listOf("GAMEPAD_BUTTON_L1", "NONE", "NONE", "NONE"), "RECT"),
            presetElement("BUTTON", 0.078431375f, 0.2888889f, 1f, listOf("GAMEPAD_BUTTON_L2", "NONE", "NONE", "NONE"), "RECT"),
            presetElement("BUTTON", 0.5388072f, 0.9111111f, 0.85f, listOf("GAMEPAD_BUTTON_START", "NONE", "NONE", "NONE"), "ROUND_RECT"),
            presetElement("BUTTON", 0.46078432f, 0.9111111f, 0.85f, listOf("GAMEPAD_BUTTON_SELECT", "NONE", "NONE", "NONE"), "ROUND_RECT"),
            presetElement("STICK", 0.10784314f, 0.73333335f, 1f, listOf("GAMEPAD_LEFT_THUMB_UP", "GAMEPAD_LEFT_THUMB_RIGHT", "GAMEPAD_LEFT_THUMB_DOWN", "GAMEPAD_LEFT_THUMB_LEFT")),
            presetElement("TRACKPAD", 0.78431374f, 0.35555556f, 1f, listOf("GAMEPAD_RIGHT_THUMB_UP", "GAMEPAD_RIGHT_THUMB_RIGHT", "GAMEPAD_RIGHT_THUMB_DOWN", "GAMEPAD_RIGHT_THUMB_LEFT"), "CIRCLE"),
            presetElement("BUTTON", 0.21568628f, 0.9111111f, 0.85f, listOf("GAMEPAD_BUTTON_L3", "NONE", "NONE", "NONE")),
            presetElement("BUTTON", 0.78431374f, 0.9111111f, 0.85f, listOf("GAMEPAD_BUTTON_R3", "NONE", "NONE", "NONE")),
        )

        internal fun oldSplitControllerElements(): List<ControlsElement> = listOf(
            presetElement("D_PAD", 0.12f, 0.55f, 0.9f, listOf("GAMEPAD_DPAD_UP", "GAMEPAD_DPAD_RIGHT", "GAMEPAD_DPAD_DOWN", "GAMEPAD_DPAD_LEFT")),
            presetElement("STICK", 0.32f, 0.56f, 0.76f, listOf("GAMEPAD_LEFT_THUMB_UP", "GAMEPAD_LEFT_THUMB_RIGHT", "GAMEPAD_LEFT_THUMB_DOWN", "GAMEPAD_LEFT_THUMB_LEFT")),
            presetElement("STICK", 0.68f, 0.56f, 0.76f, listOf("GAMEPAD_RIGHT_THUMB_UP", "GAMEPAD_RIGHT_THUMB_RIGHT", "GAMEPAD_RIGHT_THUMB_DOWN", "GAMEPAD_RIGHT_THUMB_LEFT")),
            presetElement("BUTTON", 0.83f, 0.43f, 0.62f, listOf("GAMEPAD_BUTTON_X", "NONE", "NONE", "NONE")),
            presetElement("BUTTON", 0.91f, 0.55f, 0.62f, listOf("GAMEPAD_BUTTON_B", "NONE", "NONE", "NONE")),
            presetElement("BUTTON", 0.75f, 0.55f, 0.62f, listOf("GAMEPAD_BUTTON_A", "NONE", "NONE", "NONE")),
            presetElement("BUTTON", 0.83f, 0.67f, 0.62f, listOf("GAMEPAD_BUTTON_Y", "NONE", "NONE", "NONE")),
            presetElement("BUTTON", 0.14f, 0.18f, 0.5f, listOf("GAMEPAD_BUTTON_L1", "NONE", "NONE", "NONE")),
            presetElement("BUTTON", 0.29f, 0.18f, 0.5f, listOf("GAMEPAD_BUTTON_L2", "NONE", "NONE", "NONE")),
            presetElement("BUTTON", 0.71f, 0.18f, 0.5f, listOf("GAMEPAD_BUTTON_R1", "NONE", "NONE", "NONE")),
            presetElement("BUTTON", 0.86f, 0.18f, 0.5f, listOf("GAMEPAD_BUTTON_R2", "NONE", "NONE", "NONE")),
            presetElement("BUTTON", 0.46f, 0.18f, 0.42f, listOf("GAMEPAD_BUTTON_SELECT", "NONE", "NONE", "NONE")),
            presetElement("BUTTON", 0.54f, 0.18f, 0.42f, listOf("GAMEPAD_BUTTON_START", "NONE", "NONE", "NONE")),
        )

        private fun elementsMatch(actual: List<ControlsElement>, expected: List<ControlsElement>): Boolean {
            return actual.size == expected.size && actual.indices.all { index ->
                val left = actual[index]
                val right = expected[index]
                left.type == right.type && left.bindings == right.bindings &&
                    left.shape == right.shape && left.text == right.text &&
                    left.hidden == right.hidden && left.toggleSwitch == right.toggleSwitch &&
                    kotlin.math.abs(left.x - right.x) <= 1e-4f &&
                    kotlin.math.abs(left.y - right.y) <= 1e-4f &&
                    kotlin.math.abs(left.scale - right.scale) <= 1e-4f &&
                    kotlin.math.abs(left.opacity - right.opacity) <= 1e-4f
            }
        }

        fun snap(x: Float, y: Float, viewWidthPx: Int, viewHeightPx: Int): NormalizedPosition {
            val step = max(1, viewWidthPx / 100)
            val width = max(1, viewWidthPx)
            val height = max(1, viewHeightPx)
            return NormalizedPosition(
                floor(x * width / step) * step / width,
                floor(y * height / step) * step / height,
            )
        }
    }
}

private fun JsonObject.string(key: String, default: String = ""): String =
    this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } ?: default

private fun JsonObject.boolean(key: String, default: Boolean): Boolean =
    this[key]?.let { runCatching { it.jsonPrimitive.content.toBooleanStrict() }.getOrNull() } ?: default

private fun JsonObject.float(key: String, default: Float): Float =
    this[key]?.let { runCatching { it.jsonPrimitive.float }.getOrNull() } ?: default

private fun JsonObject.int(key: String, default: Int): Int =
    this[key]?.let { runCatching { it.jsonPrimitive.int }.getOrNull() } ?: default

private fun JsonObject.array(key: String): List<kotlinx.serialization.json.JsonElement> =
    this[key]?.let { runCatching { it.jsonArray.toList() }.getOrNull() } ?: emptyList()

private fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
    runCatching { jsonPrimitive.contentOrNull }.getOrNull()
