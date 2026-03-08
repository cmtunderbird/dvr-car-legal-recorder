package com.dashcam.dvr.evidence

/**
 * ManifestEntry — one file record inside manifest.json
 *
 * Blueprint §11 Session Structure / §12 Evidence Integrity
 * ─────────────────────────────────────────────────────────
 * Each session file that exists at seal time gets one entry.
 * Optional files (audio.aac, calibration.json) use present=false when absent.
 */
data class ManifestEntry(
    /** File name (basename only, relative to session directory) */
    val name:       String,
    /** Lowercase hex SHA-256 of the file content */
    val sha256:     String?,
    /** File size in bytes */
    val sizeBytes:  Long,
    /** True if the file was found and hashed; false = absent optional file */
    val present:    Boolean
)

/**
 * SealResult — returned by EvidencePackager.seal()
 *
 * Carried back to RecordingService so it can be written into session.json
 * and logged to logcat.
 */
data class SealResult(
    /** Lowercase hex SHA-256 of manifest.json (the canonical integrity anchor) */
    val manifestHash:  String,
    /** Base64 DER signature of manifest.json bytes */
    val signatureB64:  String,
    /** "TIMESTAMPED" | "TSA_UNAVAILABLE" | "TSA_ERROR" */
    val tsaStatus:     String,
    /** ISO-8601 UTC timestamp of when sealing completed */
    val sealedTs:      String,
    /** Number of files hashed and included in the manifest */
    val fileCount:     Int
)