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
    val haptics: Boolean get() = json.boolean("haptics", false)
    val hapticStrength: Int get() = json.int("hapticStrength", 50).coerceIn(1, 100)
    val autoHideWithController: Boolean get() = json.boolean("autoHideWithController", true)

    fun encode(): String = JsonObject(json.toMap()).toString()

    fun applyPreset(template: ControlsDocument): ControlsDocument = withRootField(
        "elements",
        template.json["elements"] ?: JsonArray(emptyList()),
    )

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

    fun withHaptics(enabled: Boolean): ControlsDocument = withRootField("haptics", JsonPrimitive(enabled))

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
