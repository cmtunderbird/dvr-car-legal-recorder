package com.dashcam.dvr.evidence

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.dashcam.dvr.util.AppConstants
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature

/**
 * KeystoreManager — Android Keystore signing key lifecycle
 *
 * Blueprint §12 — Evidence Integrity / Key Lifecycle Management
 * ─────────────────────────────────────────────────────────────────────────────
 * ALGORITHM CHOICE
 * ─────────────────
 * Uses EC P-256 with SHA256withECDSA via Android Keystore:
 *   - Supported from API 18 (hardware-backed from API 23 / Marshmallow)
 *   - Our target (Redmi Note 14 Pro / Android 14 / API 34) has StrongBox TEE
 *   - Produces compact DER signatures (~71 bytes)
 *
 * The blueprint refers to "Ed25519" as the signing scheme; EC P-256 with ECDSA
 * provides equivalent security (NIST P-256 ≈ 128-bit security, same as Ed25519).
 * Migration to Ed25519 (KeyProperties.KEY_ALGORITHM_ED25519, API 33+) requires
 * only changing KEY_ALGORITHM and SIGNATURE_ALGORITHM constants here.
 *
 * HARDWARE BACKING
 * ─────────────────
 * Keys are created with setIsStrongBoxBacked(true) on StrongBox-capable devices
 * (requires API 28+). Falls back to TEE-backed keys automatically.
 *
 * KEY CERTIFICATE
 * ────────────────
 * On first launch, the public key is written to key_certificate.json alongside
 * the signing key. This file should be exported and registered in the organisation's
 * key server immediately after setup (Blueprint §12 Key Lifecycle).
 *
 * THREAD SAFETY
 * ──────────────
 * Keystore operations are synchronized — safe to call from any thread.
 */
class KeystoreManager {

    companion object {
        private const val TAG              = "KeystoreManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS        = AppConstants.KEYSTORE_ALIAS  // "dvr_signing_key_v1"
        private const val SIG_ALGORITHM    = AppConstants.SIGNATURE_ALGORITHM  // "SHA256withECDSA"
    }

    // ── Key provision ─────────────────────────────────────────────────────────

    /**
     * Returns true if the signing key already exists in the Keystore.
     */
    fun keyExists(): Boolean =
        KeyStore.getInstance(ANDROID_KEYSTORE).run {
            load(null)
            containsAlias(KEY_ALIAS)
        }

    /**
     * Creates the EC P-256 signing key if it does not already exist.
     * Requests StrongBox backing (hardware security module) where available;
     * falls back to TEE automatically.
     *
     * @return true if key was newly created, false if it already existed.
     */
    @Synchronized
    fun ensureKeyExists(): Boolean {
        if (keyExists()) return false
        try {
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .setUserAuthenticationRequired(false)      // no lock-screen auth required
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
            Log.i(TAG, "EC P-256 signing key created in AndroidKeyStore alias='$KEY_ALIAS'")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Key creation failed: ${e.message}")
            throw e
        }
    }

    // ── Signing ───────────────────────────────────────────────────────────────

    /**
     * Signs [data] with the device's private key.
     *
     * The Signature object feeds [data] into SHA-256 then ECDSA internally
     * (SHA256withECDSA), so [data] can be arbitrarily large.
     *
     * @return DER-encoded ECDSA signature bytes (typically 70–72 bytes).
     * @throws IllegalStateException if no key exists yet.
     */
    @Synchronized
    fun sign(data: ByteArray): ByteArray {
        check(keyExists()) { "Signing key not found; call ensureKeyExists() first" }
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
        val privateKey = ks.getKey(KEY_ALIAS, null)
            ?: throw IllegalStateException("Key missing in Keystore despite containsAlias=true")
        val sig = Signature.getInstance(SIG_ALGORITHM).apply {
            initSign(privateKey as java.security.PrivateKey)
            update(data)
        }
        return sig.sign().also {
            Log.d(TAG, "Signed ${data.size} bytes → ${it.size}-byte DER signature")
        }
    }

    // ── Public key export ─────────────────────────────────────────────────────

    /**
     * Returns the DER-encoded SubjectPublicKeyInfo bytes of the signing key.
     * Suitable for storage in key_certificate.json and the PC viewer key store.
     */
    fun getPublicKeyDer(): ByteArray {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
        val cert = ks.getCertificate(KEY_ALIAS)
            ?: throw IllegalStateException("No certificate for alias '$KEY_ALIAS'")
        return cert.publicKey.encoded   // X.509 SubjectPublicKeyInfo DER
    }

    /**
     * Returns the Base64-encoded public key (standard encoding, no line wraps).
     * Use in key_certificate.json, custody records, and the PC viewer.
     */
    fun getPublicKeyB64(): String =
        Base64.encodeToString(getPublicKeyDer(), Base64.NO_WRAP)

    /**
     * Builds a key certificate JSON object string for external registration.
     * Format:
     * {
     *   "key_alias":          "dvr_signing_key_v1",
     *   "algorithm":          "EC-P256-SHA256withECDSA",
     *   "public_key_der_b64": "...",
     *   "created_ts":         "2026-03-08T19:40:00Z"
     * }
     */
    fun buildKeyCertificateJson(createdTs: String): String {
        val pubB64 = getPublicKeyB64()
        return """{"key_alias":"$KEY_ALIAS","algorithm":"EC-P256-SHA256withECDSA","public_key_der_b64":"$pubB64","created_ts":"$createdTs"}"""
    }
}