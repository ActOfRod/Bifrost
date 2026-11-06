package com.actofrod.bifrost

internal enum class PendingUpdateDecision {
    LAUNCH,
    CLEAR,
    WAIT,
}

internal fun pendingUpdateDecision(
    storedPath: String?,
    storedSize: Long,
    fileSize: Long?,
    canRequestPackageInstalls: Boolean,
): PendingUpdateDecision {
    if (storedPath.isNullOrBlank() || storedSize <= 0 || fileSize == null || fileSize != storedSize) {
        return PendingUpdateDecision.CLEAR
    }
    return if (canRequestPackageInstalls) PendingUpdateDecision.LAUNCH else PendingUpdateDecision.WAIT
}
