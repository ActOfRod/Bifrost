package com.actofrod.bifrost

import com.winlator.core.GuestLaunchCommand
import org.junit.Assert.assertEquals
import org.junit.Test

class GuestLaunchCommandTest {
    @Test
    fun wrapsExecutableWithoutArguments() {
        assertEquals(
            "/dir C:\\\\Games \"C:\\windows\\bifrost-launch.exe\" \"Meridian.exe\"",
            GuestLaunchCommand.build("C:\\Games", "Meridian.exe", "", true)
        )
    }

    @Test
    fun wrapsExecutableAndPreservesArguments() {
        assertEquals(
            "/dir C:\\\\Games \"C:\\windows\\bifrost-launch.exe\" \"Meridian.exe\" -windowed \"two words\"",
            GuestLaunchCommand.build("C:\\Games", "Meridian.exe", " -windowed \"two words\"", true)
        )
    }

    @Test
    fun wrapsExecutableAndAppendsGodotLaunchOptions() {
        assertEquals(
            "/dir C:\\\\Games \"C:\\windows\\bifrost-launch.exe\" \"GodotGame.exe\" --rendering-driver vulkan",
            GuestLaunchCommand.build("C:\\Games", "GodotGame.exe", " --rendering-driver vulkan", true)
        )
    }

    @Test
    fun escapesGameDirectoryWithSpaces() {
        assertEquals(
            "/dir C:\\\\Games\\\\The\\ Hobbit \"C:\\windows\\bifrost-launch.exe\" \"Meridian.exe\"",
            GuestLaunchCommand.build("C:\\Games\\The Hobbit", "Meridian.exe", "", true)
        )
    }

    @Test
    fun leavesCommandInOriginalFormatWhenHelperIsUnavailable() {
        assertEquals(
            "/dir C:\\\\Games\\ My\\ Game \"Meridian.exe\" -safe",
            GuestLaunchCommand.build("C:\\Games My Game", "Meridian.exe", " -safe", false)
        )
    }

    @Test
    fun wrapsUppercaseExeExtension() {
        assertEquals(
            "/dir C:\\\\Games \"C:\\windows\\bifrost-launch.exe\" \"MERIDIAN.EXE\"",
            GuestLaunchCommand.build("C:\\Games", "MERIDIAN.EXE", "", true)
        )
    }
}
