package com.dashcam.dvr.session

import android.os.StatFs
import android.util.Log
import com.dashcam.dvr.session.model.StorageHealthResult
import com.dashcam.dvr.util.AppConstants
import java.io.File
import java.io.FileOutputStream

/**
 * StorageHealthChecker
 *
 * Blueprint §14 — Storage Health Monitoring
 * ─────────────────────────────────────────────────────────────────────────────
 * Performed once at each session open. Results written to session.json.
 *
 * Two checks:
 *   1. Write throughput — 10 MB probe file written to the session directory.
 *      Threshold: ≥ 14 MB/s required to sustain dual-stream @ 12 Mbps with headroom.
 *      (Rear 8 Mbps + Front 4 Mbps = 12 Mbps = 1.5 MB/s; 14 MB/s gives 9× headroom
 *       for metadata, filesystem overhead, and burst spikes.)
 *
 *   2. Free space — StatFs on the session directory's mount point.
 *      Threshold: ≥ 10 GB required (Blueprint §14 / AppConstants.MIN_FREE_STORAGE_BYTES).
 *
 * health result:
 *   "OK"         — both checks pass
 *   "SLOW"       — write speed below 14 MB/s (storage card too slow)
 *   "LOW_SPACE"  — free space below 10 GB
 *   "CRITICAL"   — both conditions true simultaneously
 */
object StorageHealthChecker {

    private const val TAG            = "StorageHealthChecker"
    private const val PROBE_BYTES    = 10 * 1024 * 1024   // 10 MB probe file
    private const val PROBE_FILENAME = ".dvr_probe"
    private const val MIN_MBPS       = 14.0f
    private const val MIN_FREE_GB    = 10.0f

    /**
     * Run both checks and return a [StorageHealthResult].
     * Call from an IO thread — this method blocks up to ~1 second for the write probe.
     */
    fun check(sessionDir: File): StorageHealthResult {
        val writeMbps = benchmarkWrite(sessionDir)
        val freeGb    = measureFreeSpace(sessionDir)

        val slow      = writeMbps < MIN_MBPS
        val lowSpace  = freeGb    < MIN_FREE_GB

        val health = when {
            slow && lowSpace -> "CRITICAL"
            slow             -> "SLOW"
            lowSpace         -> "LOW_SPACE"
            else             -> "OK"
        }

        Log.i(TAG, "Storage health: $health  write=${writeMbps}MB/s  free=${freeGb}GB")
        if (slow)     Log.w(TAG, "Write speed below 14 MB/s threshold — dual stream may drop frames")
        if (lowSpace) Log.w(TAG, "Free space below 10 GB — loop recording may fill quickly")

        return StorageHealthResult(writeMbps, freeGb, health)
    }

    // ── Write throughput probe ────────────────────────────────────────────────

    private fun benchmarkWrite(sessionDir: File): Float {
        val probe = File(sessionDir, PROBE_FILENAME)
        return try {
            val buf   = ByteArray(64 * 1024) { 0xAB.toByte() }   // 64 KB chunks
            val start = System.nanoTime()
            var written = 0

            FileOutputStream(probe).use { fos ->
                while (written < PROBE_BYTES) {
                    val chunk = minOf(buf.size, PROBE_BYTES - written)
                    fos.write(buf, 0, chunk)
                    written += chunk
                }
                fos.fd.sync()   // flush OS buffer to storage — this is the critical timing point
            }

            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
            val mbps      = (PROBE_BYTES / 1_048_576.0 / (elapsedMs / 1000.0)).toFloat()
            Log.d(TAG, "Write probe: ${String.format("%.1f", mbps)} MB/s  (${String.format("%.0f", elapsedMs)}ms)")
            mbps

        } catch (e: Exception) {
            Log.e(TAG, "Write benchmark failed: ${e.message}")
            0f
        } finally {
            probe.delete()
        }
    }

    // ── Free space ────────────────────────────────────────────────────────────

    private fun measureFreeSpace(sessionDir: File): Float {
        return try {
            val stat  = StatFs(sessionDir.absolutePath)
            val freeB = stat.availableBlocksLong * stat.blockSizeLong
            (freeB / (1024.0 * 1024.0 * 1024.0)).toFloat()
        } catch (e: Exception) {
            Log.e(TAG, "StatFs failed: ${e.message}")
            0f
        }
    }
}