package com.actofrod.bifrost

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdatePackageValidationTest {
    @Test
    fun matchingSignerAndNewerVersionCanInstall() {
        assertEquals(
            UpdatePackageDecision.INSTALL,
            updatePackageDecision(setOf("installed-cert"), setOf("installed-cert"), 130L, 131L),
        )
    }

    @Test
    fun mismatchedSignerIsRejected() {
        assertEquals(
            UpdatePackageDecision.SIGNER_MISMATCH,
            updatePackageDecision(setOf("installed-cert"), setOf("other-cert"), 130L, 131L),
        )
    }

    @Test
    fun olderOrSameVersionIsRejected() {
        assertEquals(
            UpdatePackageDecision.NOT_NEWER,
            updatePackageDecision(setOf("installed-cert"), setOf("installed-cert"), 130L, 129L),
        )
        assertEquals(
            UpdatePackageDecision.NOT_NEWER,
            updatePackageDecision(setOf("installed-cert"), setOf("installed-cert"), 130L, 130L),
        )
    }

    @Test
    fun unreadableSignersFallBackToVersionCheck() {
        assertEquals(
            UpdatePackageDecision.INSTALL,
            updatePackageDecision(setOf("installed-cert"), emptySet(), 130L, 131L),
        )
        assertEquals(
            UpdatePackageDecision.NOT_NEWER,
            updatePackageDecision(emptySet(), setOf("installed-cert"), 130L, 130L),
        )
    }
}
