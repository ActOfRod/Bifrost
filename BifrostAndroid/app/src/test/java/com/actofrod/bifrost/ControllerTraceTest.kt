package com.actofrod.bifrost

import com.winlator.core.ControllerTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerTraceTest {
    @Test
    fun composeWineDebugReplacesAllWithControllerChannels() {
        assertEquals("+dinput,+hid,+xinput", ControllerTrace.composeWineDebug("-all"))
    }

    @Test
    fun composeWineDebugAppendsChannelsToExistingSettings() {
        assertEquals("+err,+dinput,+hid,+xinput", ControllerTrace.composeWineDebug("+err"))
    }

    @Test
    fun traceFilterKeepsControllerAndErrorLinesOnly() {
        assertTrue(ControllerTrace.shouldLogLine("trace:dinput:..."))
        assertTrue(ControllerTrace.shouldLogLine("err: some failure"))
        assertTrue(ControllerTrace.shouldLogLine("FIXME: hid issue"))
        assertFalse(ControllerTrace.shouldLogLine("trace:d3d:..."))
    }
}
