package com.actofrod.bifrost

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingUpdateTest {
    @Test
    fun validPendingUpdateLaunchesWhenInstallPermissionIsGranted() {
        assertEquals(
            PendingUpdateDecision.LAUNCH,
            pendingUpdateDecision("/cache/update.apk", 1234L, 1234L, true),
        )
    }

    @Test
    fun validPendingUpdateWaitsForInstallPermission() {
        assertEquals(
            PendingUpdateDecision.WAIT,
            pendingUpdateDecision("/cache/update.apk", 1234L, 1234L, false),
        )
    }

    @Test
    fun missingOrInvalidPendingUpdateIsCleared() {
        assertEquals(
            PendingUpdateDecision.CLEAR,
            pendingUpdateDecision(null, 1234L, 1234L, true),
        )
        assertEquals(
            PendingUpdateDecision.CLEAR,
            pendingUpdateDecision("/cache/update.apk", 1234L, null, true),
        )
        assertEquals(
            PendingUpdateDecision.CLEAR,
            pendingUpdateDecision("/cache/update.apk", 1234L, 1200L, true),
        )
    }
}
