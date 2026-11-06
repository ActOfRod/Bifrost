package com.actofrod.bifrost

internal enum class UpdatePackageDecision {
    INSTALL,
    SIGNER_MISMATCH,
    NOT_NEWER,
}

internal fun updatePackageDecision(
    installedSigningCertificates: Set<String>,
    updateSigningCertificates: Set<String>,
    installedVersionCode: Long,
    updateVersionCode: Long,
): UpdatePackageDecision {
    val signersKnown = installedSigningCertificates.isNotEmpty() && updateSigningCertificates.isNotEmpty()
    if (signersKnown && installedSigningCertificates != updateSigningCertificates) {
        return UpdatePackageDecision.SIGNER_MISMATCH
    }
    return if (updateVersionCode > installedVersionCode) {
        UpdatePackageDecision.INSTALL
    } else {
        UpdatePackageDecision.NOT_NEWER
    }
}
