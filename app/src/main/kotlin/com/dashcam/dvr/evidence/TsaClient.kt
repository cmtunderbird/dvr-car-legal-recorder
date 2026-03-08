package com.dashcam.dvr.evidence

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * TsaClient — RFC 3161 Trusted Timestamp Authority client
 *
 * Blueprint §12 — Trusted Timestamp Authority (Enhanced)
 * ─────────────────────────────────────────────────────────────────────────────
 * PURPOSE
 * ────────
 * Submits the SHA-256 hash of the signed manifest to an RFC 3161 compliant
 * Timestamp Authority (TSA). The returned token (.tsr file) cryptographically
 * binds the signing time to the manifest hash, independent of device clock
 * integrity — critical for legal admissibility.
 *
 * PROTOCOL (RFC 3161 §2.4)
 * ─────────────────────────
 * 1. Build a TimeStampReq in DER encoding:
 *      TimeStampReq ::= SEQUENCE {
 *        version        INTEGER { v1(1) },
 *        messageImprint MessageImprint,   -- SHA-256 of manifest signature bytes
 *        nonce          INTEGER,          -- 8 random bytes
 *        certReq        BOOLEAN TRUE      -- request TSA cert chain in response
 *      }
 *      MessageImprint ::= SEQUENCE {
 *        hashAlgorithm  AlgorithmIdentifier,  -- SHA-256
 *        hashedMessage  OCTET STRING           -- 32 bytes
 *      }
 *
 * 2. HTTP POST to TSA URL with:
 *      Content-Type: application/timestamp-query
 *
 * 3. Response body (application/timestamp-reply) is stored verbatim as
 *    tsa_response.tsr — the PC viewer verifies it independently.
 *
 * BEST-EFFORT
 * ────────────
 * TSA submission is best-effort. If the network is unavailable, returns null
 * and the session is sealed without a TSA token. The manifest signature still
 * proves device-clock integrity; the TSA adds third-party time authority.
 * This is logged as tsa_status="TSA_UNAVAILABLE" in manifest.json.
 *
 * TSA PROVIDERS (tried in order, all free public TSAs)
 * ─────────────────────────────────────────────────────
 * 1. DigiCert       http://timestamp.digicert.com
 * 2. Sectigo        http://timestamp.sectigo.com
 * 3. GlobalSign     http://timestamp.globalsign.com/tsa/r6advanced1
 */
object TsaClient {

    private const val TAG         = "TsaClient"
    private const val TIMEOUT_MS  = 10_000

    // Free public RFC 3161 TSA endpoints — tried in priority order
    private val TSA_URLS = listOf(
        "http://timestamp.digicert.com",
        "http://timestamp.sectigo.com",
        "http://timestamp.globalsign.com/tsa/r6advanced1"
    )

    // ── SHA-256 OID bytes (2.16.840.1.101.3.4.2.1) ─────────────────────────
    // Encoded as DER OID (tag 06, length 09, then the 9-byte OID value)
    private val SHA256_OID_DER = byteArrayOf(
        0x06, 0x09,
        0x60.toByte(), 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01
    )

    /**
     * Stamps the SHA-256 hash [sha256Hash] (32 bytes) of the manifest signature
     * with the first responsive TSA.
     *
     * @param sha256Hash 32-byte SHA-256 digest of the manifest signature bytes
     * @return Raw TSA response bytes (.tsr) or null if all TSAs unreachable
     */
    fun stamp(sha256Hash: ByteArray): ByteArray? {
        require(sha256Hash.size == 32) { "sha256Hash must be 32 bytes, got ${sha256Hash.size}" }
        val request = buildRequest(sha256Hash)
        Log.d(TAG, "TSA request built: ${request.size} bytes")

        for (url in TSA_URLS) {
            try {
                val response = post(url, request)
                if (response != null && response.size > 10) {
                    Log.i(TAG, "TSA token received from $url  (${response.size} bytes)")
                    return response
                }
            } catch (e: Exception) {
                Log.w(TAG, "TSA $url failed: ${e.javaClass.simpleName} — ${e.message}")
            }
        }
        Log.w(TAG, "All TSA endpoints unreachable — session sealed without TSA token")
        return null
    }

    // ── DER request construction ──────────────────────────────────────────────

    /**
     * Builds a minimal RFC 3161 TimeStampReq in DER encoding.
     * Fixed layout — all lengths are known before writing (no dynamic realloc).
     */
    private fun buildRequest(sha256Hash: ByteArray): ByteArray {
        // 8-byte nonce with high bit cleared → guaranteed positive INTEGER, no leading zero
        val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }
        nonce[0] = (nonce[0].toInt() and 0x7F).toByte()

        // AlgorithmIdentifier ::= SEQUENCE { OID NULL }
        // Content: SHA256_OID_DER (11 bytes) + 05 00 (2 bytes) = 13 bytes
        val nullDer  = byteArrayOf(0x05, 0x00)
        val algIdContent = SHA256_OID_DER + nullDer               // 13 bytes
        val algId = derSequence(algIdContent)                      // 30 0d [13] = 15 bytes

        // MessageImprint ::= SEQUENCE { AlgorithmIdentifier OCTET_STRING }
        val hashOctet = byteArrayOf(0x04, 0x20.toByte()) + sha256Hash  // tag + len + 32 bytes = 34
        val msgImprintContent = algId + hashOctet                  // 15 + 34 = 49 bytes
        val msgImprint = derSequence(msgImprintContent)            // 30 31 [49] = 51 bytes

        // version INTEGER 1 = 02 01 01 (3 bytes)
        val version = byteArrayOf(0x02, 0x01, 0x01)

        // nonce INTEGER (8 bytes, positive, no leading zero) = 02 08 [8] = 10 bytes
        val nonceField = byteArrayOf(0x02, 0x08) + nonce

        // certReq BOOLEAN TRUE = 01 01 ff (3 bytes)
        val certReq = byteArrayOf(0x01, 0x01, 0xFF.toByte())

        // TimeStampReq SEQUENCE body = 3 + 51 + 10 + 3 = 67 bytes
        val body = version + msgImprint + nonceField + certReq
        return derSequence(body)   // 30 43 [67] = 69 bytes
    }

    /** DER SEQUENCE wrapper: handles lengths up to 65535 bytes. */
    private fun derSequence(content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x30)  // SEQUENCE tag
        val len = content.size
        when {
            len < 0x80   -> out.write(len)
            len < 0x100  -> { out.write(0x81); out.write(len) }
            else         -> { out.write(0x82); out.write(len shr 8); out.write(len and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    // ── HTTP POST ─────────────────────────────────────────────────────────────

    private fun post(tsaUrl: String, requestBytes: ByteArray): ByteArray? {
        val conn = URL(tsaUrl).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod      = "POST"
            conn.doOutput           = true
            conn.doInput            = true
            conn.connectTimeout     = TIMEOUT_MS
            conn.readTimeout        = TIMEOUT_MS
            conn.setRequestProperty("Content-Type",   "application/timestamp-query")
            conn.setRequestProperty("Content-Length", requestBytes.size.toString())
            conn.setRequestProperty("Accept",         "application/timestamp-reply")

            conn.outputStream.use { it.write(requestBytes) }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "$tsaUrl returned HTTP $code")
                return null
            }
            val contentType = conn.contentType ?: ""
            if (!contentType.contains("timestamp-reply", ignoreCase = true)) {
                Log.w(TAG, "$tsaUrl returned unexpected Content-Type: $contentType")
                // Accept anyway — some TSAs mis-set Content-Type but body is correct
            }
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}