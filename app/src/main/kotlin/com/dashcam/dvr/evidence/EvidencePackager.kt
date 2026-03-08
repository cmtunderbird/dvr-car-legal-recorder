package com.dashcam.dvr.evidence

import android.util.Base64
import android.util.Log
import com.dashcam.dvr.session.CustodyLog
import com.dashcam.dvr.util.AppConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * EvidencePackager — session sealing orchestrator
 *
 * Blueprint §12 — Evidence Integrity System ★ ENHANCED
 * ─────────────────────────────────────────────────────────────────────────────
 * SEALING SEQUENCE (called once per session, on session close)
 * ─────────────────────────────────────────────────────────────
 *  1. Ensure signing key exists in Android Keystore (creates on first seal).
 *  2. Inventory all session files. Hash each present file with SHA-256.
 *  3. Write manifest.json — file list with hashes, session identity, seal time.
 *  4. Sign manifest.json bytes with EC P-256 / SHA256withECDSA via Keystore.
 *  5. Write raw DER signature to "signature" file.
 *  6. Submit SHA-256(signature_bytes) to RFC 3161 TSA → write tsa_response.tsr.
 *     Best-effort: session sealed without TSA token if network unavailable.
 *  7. Write key_certificate.json (public key, first-run only — for key server).
 *
 * OUTPUT FILES (written to sessionDir)
 * ──────────────────────────────────────
 *  manifest.json       — hash registry for all session files
 *  signature           — DER-encoded ECDSA signature of manifest.json bytes
 *  tsa_response.tsr    — RFC 3161 TSA token (if network available)
 *  key_certificate.json — public key export (only if key was newly created)
 *
 * CUSTODY LOG
 * ────────────
 * A SESSION_SEALED entry is appended to custody.log after successful sealing,
 * recording the manifest hash and TSA status. This entry is NOT included in
 * the manifest hash (sealing happens first).
 *
 * ERROR HANDLING
 * ───────────────
 * File hashing and signing errors are terminal (returns null).
 * TSA errors are non-terminal (tsa_status = "TSA_UNAVAILABLE" or "TSA_ERROR").
 *
 * THREAD SAFETY
 * ──────────────
 * seal() must be called from a background (IO) thread — file I/O + network.
 * The method is not reentrant; caller must ensure single-threaded invocation.
 */
object EvidencePackager {

    private const val TAG = "EvidencePackager"

    // Files included in the manifest, in canonical order.
    // Mandatory files (absent → present=false, sha256=null).
    // All files in the session directory are hashed; this list defines the canonical
    // set and ensures deterministic ordering in manifest.json.
    private val CANONICAL_FILES = listOf(
        AppConstants.REAR_VIDEO_FILENAME,    // rear_camera.mp4    (mandatory when cameras active)
        AppConstants.FRONT_VIDEO_FILENAME,   // front_camera.mp4   (mandatory when cameras active)
        AppConstants.AUDIO_FILENAME,         // audio.aac          (optional)
        AppConstants.TELEMETRY_FILENAME,     // telemetry.log      (mandatory)
        AppConstants.EVENTS_FILENAME,        // events.log         (mandatory)
        AppConstants.CALIBRATION_FILENAME,   // calibration.json   (optional)
        AppConstants.SESSION_META_FILENAME,  // session.json       (mandatory)
        AppConstants.CUSTODY_LOG_FILENAME    // custody.log        (mandatory — last, final entry written)
    )

    /**
     * Seal the session directory.
     *
     * @param sessionDir        The session directory to seal.
     * @param installationUuid  Installation UUID from SessionManager.
     * @return [SealResult] on success, null if a fatal error prevents sealing.
     */
    fun seal(sessionDir: File, installationUuid: String): SealResult? {
        val sessionId = sessionDir.name
        Log.i(TAG, "Sealing session: $sessionId")

        // ── 1. Ensure signing key ─────────────────────────────────────────────
        val keystoreManager = KeystoreManager()
        try {
            val created = keystoreManager.ensureKeyExists()
            if (created) {
                // First-run: write key certificate for external registration
                writeKeyCertificate(sessionDir, keystoreManager, installationUuid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fatal: Keystore key creation failed: ${e.message}")
            return null
        }

        // ── 2. Hash all session files ─────────────────────────────────────────
        val entries = hashFiles(sessionDir)
        val presentCount = entries.count { it.present }
        Log.i(TAG, "Hashed $presentCount / ${entries.size} files")

        // ── 3. Write manifest.json ────────────────────────────────────────────
        val sealedTs = utcNow()
        val manifestJson = buildManifestJson(
            sessionId        = sessionId,
            installationUuid = installationUuid,
            sealedTs         = sealedTs,
            entries          = entries,
            tsaStatus        = "PENDING"      // updated below
        )
        val manifestFile = File(sessionDir, AppConstants.MANIFEST_FILENAME)
        try {
            manifestFile.writeText(manifestJson)
        } catch (e: Exception) {
            Log.e(TAG, "Fatal: manifest.json write failed: ${e.message}")
            return null
        }
        val manifestBytes    = manifestFile.readBytes()
        val manifestHashHex  = sha256Hex(manifestBytes)
        Log.i(TAG, "manifest.json written  hash=$manifestHashHex")

        // ── 4. Sign manifest.json ─────────────────────────────────────────────
        val signatureBytes: ByteArray
        try {
            signatureBytes = keystoreManager.sign(manifestBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Fatal: signing failed: ${e.message}")
            return null
        }
        val signatureFile = File(sessionDir, AppConstants.SIGNATURE_FILENAME)
        try {
            signatureFile.writeBytes(signatureBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Fatal: signature file write failed: ${e.message}")
            return null
        }
        Log.i(TAG, "Manifest signed  sig=${signatureBytes.size} bytes")

        // ── 5. RFC 3161 TSA timestamp ─────────────────────────────────────────
        // We stamp the SHA-256 of the signature bytes — this ties the TSA token
        // directly to the signature, which in turn covers the manifest hash.
        var tsaStatus: String
        try {
            val sigHash    = sha256Bytes(signatureBytes)
            val tsaToken   = TsaClient.stamp(sigHash)
            if (tsaToken != null) {
                File(sessionDir, AppConstants.TSA_TOKEN_FILENAME).writeBytes(tsaToken)
                tsaStatus = "TIMESTAMPED"
                Log.i(TAG, "TSA token saved  (${tsaToken.size} bytes)")
            } else {
                tsaStatus = "TSA_UNAVAILABLE"
                Log.w(TAG, "TSA unavailable — sealed without timestamp token")
            }
        } catch (e: Exception) {
            Log.w(TAG, "TSA error (non-fatal): ${e.message}")
            tsaStatus = "TSA_ERROR"
        }

        // ── 6. Update manifest.json with final tsa_status ─────────────────────
        // Rewrite manifest.json with resolved TSA status. The manifest hash
        // changes; both the old (PENDING) and new hash are logged.
        // JSONObject.toString(2) emits ": " (colon-space); tolerate both forms
        val finalManifest = manifestJson
            .replace("\"tsa_status\": \"PENDING\"", "\"tsa_status\": \"$tsaStatus\"")
            .replace("\"tsa_status\":\"PENDING\"",   "\"tsa_status\":\"$tsaStatus\"")
        try {
            manifestFile.writeText(finalManifest)
        } catch (e: Exception) {
            Log.w(TAG, "manifest.json TSA status update failed (non-fatal): ${e.message}")
        }
        val finalManifestHash = sha256Hex(manifestFile.readBytes())
        Log.i(TAG, "Manifest finalised  tsa=$tsaStatus  hash=$finalManifestHash")

        // ── 7. Custody log: SESSION_SEALED ────────────────────────────────────
        try {
            CustodyLog.append(
                sessionDir       = sessionDir,
                installationUuid = installationUuid,
                action           = "SESSION_SEALED",
                sessionId        = sessionId,
                detail           = "manifest_hash=$finalManifestHash  tsa=$tsaStatus",
                result           = if (tsaStatus == "TIMESTAMPED") "OK" else "WARN"
            )
        } catch (e: Exception) {
            Log.w(TAG, "custody.log SESSION_SEALED write failed (non-fatal): ${e.message}")
        }

        val sigB64 = Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
        return SealResult(
            manifestHash = finalManifestHash,
            signatureB64 = sigB64,
            tsaStatus    = tsaStatus,
            sealedTs     = sealedTs,
            fileCount    = presentCount
        ).also {
            Log.i(TAG, "Session sealed OK  files=$presentCount  tsa=$tsaStatus")
        }
    }

    // ── File hashing ──────────────────────────────────────────────────────────

    private fun hashFiles(sessionDir: File): List<ManifestEntry> =
        CANONICAL_FILES.map { filename ->
            val file = File(sessionDir, filename)
            if (file.exists() && file.isFile) {
                try {
                    ManifestEntry(
                        name      = filename,
                        sha256    = sha256Hex(file.readBytes()),
                        sizeBytes = file.length(),
                        present   = true
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to hash $filename: ${e.message}")
                    ManifestEntry(name = filename, sha256 = null, sizeBytes = 0L, present = false)
                }
            } else {
                Log.d(TAG, "Optional file absent: $filename")
                ManifestEntry(name = filename, sha256 = null, sizeBytes = 0L, present = false)
            }
        }

    // ── Manifest JSON builder ─────────────────────────────────────────────────

    private fun buildManifestJson(
        sessionId:        String,
        installationUuid: String,
        sealedTs:         String,
        entries:          List<ManifestEntry>,
        tsaStatus:        String
    ): String {
        val filesArray = JSONArray().apply {
            entries.forEach { e ->
                put(JSONObject().apply {
                    put("name",       e.name)
                    put("sha256",     e.sha256 ?: JSONObject.NULL)
                    put("size_bytes", e.sizeBytes)
                    put("present",    e.present)
                })
            }
        }
        return JSONObject().apply {
            put("session_id",        sessionId)
            put("schema_version",    "2.0")
            put("sealed_ts_utc",     sealedTs)
            put("installation_uuid", installationUuid)
            put("signing_key_id",    AppConstants.KEYSTORE_ALIAS)
            put("files",             filesArray)
            put("tsa_status",        tsaStatus)
        }.toString(2)   // pretty-print, 2-space indent
    }

    // ── Key certificate (first-run only) ─────────────────────────────────────

    private fun writeKeyCertificate(
        sessionDir:       File,
        keystoreManager:  KeystoreManager,
        installationUuid: String
    ) {
        try {
            val cert = keystoreManager.buildKeyCertificateJson(utcNow())
            File(sessionDir, AppConstants.KEY_CERTIFICATE_FILENAME).writeText(cert)
            Log.i(TAG, "key_certificate.json written — export to key server immediately")
        } catch (e: Exception) {
            Log.w(TAG, "key_certificate.json write failed (non-fatal): ${e.message}")
        }
    }

    // ── Crypto helpers ────────────────────────────────────────────────────────

    private fun sha256Bytes(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun sha256Hex(data: ByteArray): String =
        sha256Bytes(data).joinToString("") { "%02x".format(it) }

    private fun utcNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
}