package com.dashcam.dvr.telemetry

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.atomic.AtomicLong

/**
 * TelemetryWriter
 *
 * Blueprint §11 — telemetry.log (JSONL format)
 * —————————————————————————————————————————————
 * Writes one JSON object per line. Append-only — parseable line-by-line even
 * after unexpected termination.
 *
 * Architecture — Channel-based async IO:
 *   Sensor threads call write() (non-blocking trySend).
 *   A single drain coroutine on IO dispatcher serialises and writes to disk.
 *
 * Throughput at 100 Hz IMU + 5 Hz GPS:
 *   ~205 JSONL lines/s → ~18 MB/hour. Negligible vs video streams.
 *
 * Drop policy:
 *   If the channel is full (e.g. storage stall), records are dropped and
 *   counted. Telemetry is best-effort; video evidence integrity is primary.
 *
 * Flush schedule:
 *   Flushed every FLUSH_INTERVAL_RECORDS to balance durability vs fsync overhead.
 */
class TelemetryWriter(private val telemetryFile: File) {

    companion object {
        private const val TAG                    = "TelemetryWriter"
        private const val WRITE_BUFFER_BYTES     = 64 * 1024
        private const val CHANNEL_CAPACITY       = 8_192        // ~40 s of IMU headroom
        private const val FLUSH_INTERVAL_RECORDS = 500L
    }

    private val gson    = Gson()
    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<Any>(CHANNEL_CAPACITY)
    private val written = AtomicLong(0)
    private val dropped = AtomicLong(0)

    @Volatile private var writer: BufferedWriter? = null
    // FIX B: track the drain coroutine so close() can await it
    private var drainJob: kotlinx.coroutines.Job? = null

    fun open() {
        writer = BufferedWriter(FileWriter(telemetryFile, true), WRITE_BUFFER_BYTES)
        drainJob = scope.launch { drainLoop() }
        Log.i(TAG, "TelemetryWriter open: ${telemetryFile.absolutePath}")
    }

    /**
     * Enqueue a record for async write. Non-blocking. Safe at 100 Hz from any thread.
     */
    fun write(record: Any) {
        if (channel.trySend(record).isFailure) dropped.incrementAndGet()
    }

    /**
     * Drain remaining records, flush and close. Call once at session end.
     *
     * FIX B: Previously close() called channel.close() then immediately
     * writer.flush()/close(), racing with drainLoop() which was still
     * iterating the channel on a separate coroutine.  This caused either
     * an IOException ("Stream closed") or silently dropped the final batch.
     *
     * Fix: close the channel (signals no more sends), then JOIN the drain
     * coroutine so all buffered records are written, THEN flush/close the
     * writer.  No more race — drainLoop() finishes first, guaranteed.
     */
    suspend fun close() {
        channel.close()                     // signal: no more sends
        drainJob?.join()                    // wait for drainLoop() to finish writing
        drainJob = null
        writer?.flush()
        writer?.close()
        writer = null
        val w = written.get()
        val d = dropped.get()
        Log.i(TAG, "TelemetryWriter closed — written=$w dropped=$d" +
                if (d > 0) "  ⚠ DROP RATE: ${d * 100 / (w + d)}%" else "")
    }

    private suspend fun drainLoop() {
        try {
            for (record in channel) {
                val w = writer ?: break
                w.write(gson.toJson(record))
                w.newLine()
                val count = written.incrementAndGet()
                if (count % FLUSH_INTERVAL_RECORDS == 0L) w.flush()
            }
        } catch (e: ClosedReceiveChannelException) {
            // Normal shutdown
        } catch (e: Exception) {
            Log.e(TAG, "Write error in drain loop: ${e.message}")
        } finally {
            writer?.flush()
        }
    }
}
