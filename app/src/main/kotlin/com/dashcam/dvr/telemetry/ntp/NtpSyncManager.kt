package com.dashcam.dvr.telemetry.ntp

import android.util.Log
import com.dashcam.dvr.telemetry.model.NtpRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * NtpSyncManager
 *
 * Blueprint §6 — Clock Synchronisation (Enhanced)
 * ─────────────────────────────────────────────────
 * • Performs one NTP sync at session start (blocking, on IO dispatcher).
 * • Stores [offsetMs] for UTC correction throughout the entire session.
 * • Tries multiple fallback NTP servers before giving up.
 * • Returns an [NtpRecord] as the first JSONL entry in telemetry.log.
 * • If all servers fail → offset stays 0, status = "FAILED".
 *   SessionManager (Module 4) must flag the session as CLOCK_UNVERIFIED
 *   in session.json (Blueprint §6 requirement).
 *
 * Thread-safety: [offsetMs] and [syncStatus] are @Volatile — safe to read
 * from any thread after [syncAtSessionStart] has returned.
 */
class NtpSyncManager {

    companion object {
        private const val TAG = "NtpSyncManager"

        private val SERVERS = listOf(
            "pool.ntp.org",
            "time.google.com",
            "time.cloudflare.com",
            "time.windows.com"
        )

        val UTC_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneOffset.UTC)
    }

    @Volatile var offsetMs:    Long   = 0L;       private set
    @Volatile var syncStatus:  String = "SKIPPED"; private set
    @Volatile var serverUsed:  String = "none";   private set
    @Volatile var roundTripMs: Long   = -1L;      private set

    val isSynced: Boolean get() = syncStatus == "SYNCED"

    suspend fun syncAtSessionStart(): NtpRecord = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting NTP sync — trying ${SERVERS.size} servers...")
        val client = SntpClient()
        var result: SntpClient.SntpResult? = null

        for (server in SERVERS) {
            result = client.sync(server)
            if (result != null) break
            Log.w(TAG, "Server $server failed — trying next")
        }

        if (result != null) {
            offsetMs    = result.offsetMs
            roundTripMs = result.roundTripMs
            syncStatus  = "SYNCED"
            serverUsed  = result.serverAddress
            Log.i(TAG, "NTP sync OK — offset=${offsetMs}ms rtt=${roundTripMs}ms via $serverUsed")
        } else {
            offsetMs    = 0L
            roundTripMs = -1L
            syncStatus  = "FAILED"
            serverUsed  = "none"
            Log.w(TAG, "All NTP servers failed — session will be flagged CLOCK_UNVERIFIED")
        }

        NtpRecord(
            tsUtc      = correctedUtcNow(),
            syncStatus = syncStatus,
            offsetMs   = offsetMs,
            server     = serverUsed
        )
    }

    /** NTP-corrected UTC now as ISO 8601 string. Use for every ts_utc field. */
    fun correctedUtcNow(): String =
        UTC_FORMATTER.format(Instant.ofEpochMilli(System.currentTimeMillis() + offsetMs))

    /** NTP-corrected epoch milliseconds. */
    fun correctedEpochMs(): Long = System.currentTimeMillis() + offsetMs
}
