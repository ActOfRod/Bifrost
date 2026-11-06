package com.actofrod.bifrost

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsTest {
    @Test
    fun updatesRoundTripUnknownProfileAndElementKeys() {
        val original = ControlsDocument.parse(
            """{"id":7,"name":"Game","futureProfile":{"v":1},"elements":[{"type":"BUTTON","shape":"CIRCLE","bindings":["GAMEPAD_BUTTON_A","NONE","NONE","NONE"],"scale":1,"x":0.5,"y":0.5,"toggleSwitch":false,"text":"","futureElement":[1,2]}]}""",
        )
        val updated = original.updateElement(0) { it.update(x = 0.6f) }
        val result = Json.parseToJsonElement(updated.encode()).jsonObject
        assertEquals(Json.parseToJsonElement("""{"v":1}"""), result["futureProfile"])
        assertEquals(
            Json.parseToJsonElement("[1,2]"),
            result["elements"]!!.jsonArray[0].jsonObject["futureElement"],
        )
        assertEquals(0.6f, result["elements"]!!.jsonArray[0].jsonObject["x"]!!.jsonPrimitive.content.toFloat(), 0.0001f)
    }

    @Test
    fun hiddenFlagIsWrittenOnlyWhenEnabled() {
        val document = ControlsDocument.create(1, "Game").addElement("BUTTON")
        val hidden = document.updateElement(0) { it.update(hidden = true) }
        assertTrue(hidden.elements.single().hidden)
        assertEquals(true, Json.parseToJsonElement(hidden.encode()).jsonObject["elements"]!!.jsonArray[0].jsonObject["hidden"]!!.jsonPrimitive.content.toBoolean())
        val visible = hidden.updateElement(0) { it.update(hidden = false) }
        assertFalse(visible.elements.single().hidden)
        assertFalse("hidden" in Json.parseToJsonElement(visible.encode()).jsonObject["elements"]!!.jsonArray[0].jsonObject)
    }

    @Test
    fun applyingPresetRetainsProfileSettingsAndControllerMappings() {
        val current = ControlsDocument.parse(
            """{"id":8,"name":"My game","haptics":true,"hapticStrength":73,"autoHideWithController":false,"controllers":[{"id":"*","name":"Bifrost","controllerBindings":[{"keyCode":96,"binding":"KEY_SPACE"}]}],"elements":[]}""",
        )
        val preset = ControlsDocument.parse(
            """{"id":3,"name":"Virtual Gamepad","elements":[{"type":"BUTTON","shape":"CIRCLE","bindings":["GAMEPAD_BUTTON_A","NONE","NONE","NONE"],"scale":1,"x":0.5,"y":0.5,"toggleSwitch":false,"text":""}]}""",
        )
        val applied = current.applyPreset(preset)
        assertEquals(8, applied.id)
        assertEquals("My game", applied.name)
        assertFalse(applied.disableMouseInput)
        assertTrue(applied.haptics)
        assertEquals(73, applied.hapticStrength)
        assertFalse(applied.autoHideWithController)
        assertEquals(listOf(96 to "KEY_SPACE"), applied.controllerBindings())
        assertEquals(1, applied.elements.size)
    }

    @Test
    fun snapsNormalizedCoordinatesToWinlatorGrid() {
        val snapped = ControlsDocument.snap(0.552f, 0.251f, viewWidthPx = 1000, viewHeightPx = 500)
        assertEquals(0.55f, snapped.x, 0.0001f)
        assertEquals(0.24f, snapped.y, 0.0001f)
    }

    @Test
    fun addsAndDeletesSupportedElementTypes() {
        val document = ControlsDocument.create(1, "Game")
            .addElement("BUTTON")
            .addElement("D_PAD")
            .addElement("STICK")
            .addElement("TRACKPAD")
        assertEquals(listOf("BUTTON", "D_PAD", "STICK", "TRACKPAD"), document.elements.map { it.type })
        assertEquals("GAMEPAD_DPAD_UP", document.elements[1].bindings.first())
        assertEquals("MOUSE_MOVE_UP", document.elements[3].bindings.first())
        assertEquals(3, document.deleteElement(1).elements.size)
        assertEquals(4, document.elements.size)
    }

    @Test
    fun splitControllerPresetFitsAWideLowerDeck() {
        val preset = ControlsDocument.splitControllerPreset(12)
        assertEquals(13, preset.elements.size)
        assertTrue(preset.elements.all { it.x in 0f..1f && it.y in 0.18f..0.70f })
        assertTrue(preset.elements.any { "GAMEPAD_RIGHT_THUMB_UP" in it.bindings })
        assertTrue(preset.elements.any { "GAMEPAD_BUTTON_START" in it.bindings })
    }

    @Test
    fun hapticsDefaultOnAndLegacyOptOutMigratesUnlessUserSet() {
        assertTrue(ControlsDocument.parse("""{"id":1,"name":"Game","elements":[]}""").haptics)
        val legacyOptOut = ControlsDocument.parse("""{"id":1,"name":"Game","haptics":false,"elements":[]}""")
        assertTrue(legacyOptOut.migrateLegacyHaptics().haptics)

        val userOptOut = legacyOptOut.withHaptics(false)
        assertFalse(userOptOut.migrateLegacyHaptics().haptics)
        assertTrue(userOptOut.hapticsUserSet)
    }

    @Test
    fun disableMouseInputRoundTripsAndMissingKeyKeepsCurrentBehavior() {
        val original = ControlsDocument.parse("""{"id":1,"name":"Game","elements":[]}""")
        assertFalse(original.disableMouseInput)
        assertTrue(ControlsDocument.parse(original.withDisableMouseInput(true).encode()).disableMouseInput)
        assertFalse(ControlsDocument.parse(original.withDisableMouseInput(false).encode()).disableMouseInput)
    }

    @Test
    fun migratesExactOldPresetsAndKeepsOtherProfileFields() {
        fun legacyDocument(elements: List<ControlsElement>) = ControlsDocument(
            JsonObject(
                ControlsDocument.create(9, "Game").json.toMutableMap().apply {
                    put("elements", JsonArray(elements.map { it.json }))
                    put("haptics", JsonPrimitive(false))
                    put("hapticStrength", JsonPrimitive(73))
                    put("controllers", Json.parseToJsonElement("""[{"id":"*","name":"Bifrost"}]"""))
                    put("customField", JsonPrimitive("keep"))
                },
            ),
        )

        val migratedXbox = legacyDocument(ControlsDocument.oldControls3Elements()).migrateLegacyPreset()
        assertEquals(ControlsDocument.xboxOverlayPreset(9, "Game").elements, migratedXbox.elements)
        assertEquals(2, migratedXbox.json["presetVersion"]!!.jsonPrimitive.content.toInt())
        assertFalse(migratedXbox.haptics)
        assertEquals(73, migratedXbox.hapticStrength)
        assertEquals("keep", migratedXbox.json["customField"]!!.jsonPrimitive.content)
        assertTrue("controllers" in migratedXbox.json)
        assertFalse(migratedXbox.disableMouseInput)

        val migratedSplit = legacyDocument(ControlsDocument.oldSplitControllerElements()).migrateLegacyPreset()
        assertEquals(ControlsDocument.splitControllerPreset(9, "Game").elements, migratedSplit.elements)
        assertFalse(migratedSplit.disableMouseInput)
    }

    @Test
    fun changedLegacyPresetElementDoesNotMigrate() {
        val originalElements = ControlsDocument.oldControls3Elements()
        val changedPresets = listOf(
            originalElements.mapIndexed { index, element ->
                if (index == 0) element.update(x = element.x + 0.01f) else element
            },
            originalElements.mapIndexed { index, element ->
                if (index == 1) element.update(text = "Custom") else element
            },
            originalElements.mapIndexed { index, element ->
                if (index == 1) element.update(hidden = true) else element
            },
        )

        changedPresets.forEach { elements ->
            val document = ControlsDocument(
                JsonObject(
                    ControlsDocument.create(1, "Game").json.toMutableMap().apply {
                        put("elements", JsonArray(elements.map { it.json }))
                    },
                ),
            )
            val migrated = document.migrateLegacyPreset()

            assertEquals(document.elements, migrated.elements)
            assertFalse("presetVersion" in migrated.json)
        }
    }

    @Test
    fun newPresetsHaveCorrectFaceButtonDiamondAndVersion() {
        listOf(
            ControlsDocument.splitControllerPreset(),
            ControlsDocument.xboxOverlayPreset(),
        ).forEach { preset ->
            assertTrue(preset.disableMouseInput)
            fun button(binding: String) = preset.elements.single { binding in it.bindings }
            assertTrue(button("GAMEPAD_BUTTON_Y").y < button("GAMEPAD_BUTTON_A").y)
            assertTrue(button("GAMEPAD_BUTTON_X").x < button("GAMEPAD_BUTTON_B").x)
            assertEquals(2, preset.json["presetVersion"]!!.jsonPrimitive.content.toInt())
        }
        val xbox = ControlsDocument.xboxOverlayPreset()
        assertEquals(
            listOf(0.10f, 0.62f, 1.0f),
            listOf(xbox.elements[0].x, xbox.elements[0].y, xbox.elements[0].scale),
        )
    }

    @Test
    fun controllerStylesMapLabelsColorsAndPlaystationShapes() {
        assertEquals("B", controllerButtonPresentation(ControllerStyle.NINTENDO, "GAMEPAD_BUTTON_A")!!.label)
        assertEquals("A", controllerButtonPresentation(ControllerStyle.NINTENDO, "GAMEPAD_BUTTON_B")!!.label)
        assertEquals(0xFF5DC21EL, controllerButtonPresentation(ControllerStyle.XBOX, "GAMEPAD_BUTTON_A")!!.accent)
        assertEquals(
            ControllerSymbol.CROSS,
            controllerButtonPresentation(ControllerStyle.PLAYSTATION, "GAMEPAD_BUTTON_A")!!.symbol,
        )
        assertEquals("OPTIONS", controllerButtonPresentation(ControllerStyle.PLAYSTATION, "GAMEPAD_BUTTON_START")!!.label)
        assertEquals(ControllerStyle.XBOX, ControllerStyle.fromId("invalid"))
    }

    @Test
    fun wildcardControllerBindingUsesWinlatorJsonShape() {
        val document = ControlsDocument.create(1, "Game").withControllerBinding(96, "KEY_SPACE")
        val controller = Json.parseToJsonElement(document.encode()).jsonObject["controllers"]!!.jsonArray.single().jsonObject
        assertEquals("*", controller["id"]!!.jsonPrimitive.content)
        val binding = controller["controllerBindings"]!!.jsonArray.single().jsonObject
        assertEquals(96, binding["keyCode"]!!.jsonPrimitive.content.toInt())
        assertEquals("KEY_SPACE", binding["binding"]!!.jsonPrimitive.content)
        assertEquals(
            Json.parseToJsonElement("""{"keyCode":96,"binding":"KEY_SPACE"}"""),
            binding,
        )
        assertTrue(document.withControllerBinding(96, null).controllerBindings().isEmpty())
    }
}
