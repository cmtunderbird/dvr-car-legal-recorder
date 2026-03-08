package com.dashcam.dvr.collision

import android.util.Log
import com.dashcam.dvr.collision.model.ImpactEvent
import com.dashcam.dvr.util.AppConstants
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.io.FileWriter

/**
 * EventsLog — append-only JSONL writer for events.log
 *
 * Blueprint §8 / §11 — Evidence session structure
 * ─────────────────────────────────────────────────────────────────────────────
 * Each confirmed collision event produces exactly one JSONL line in events.log.
 * Suppressed ROAD_IMPACT events are NOT written here — they go to telemetry.log
 * only (via CollisionDetector.writeCallback).
 *
 * events.log is part of the evidence package signed in Module 6.
 *
 * Thread safety: append() is @Synchronized — called from the IMU HandlerThread
 * but could theoretically also be called from the main/IO thread in recovery.
 */
class EventsLog(sessionDir: File) {

    companion object {
        private const val TAG = "EventsLog"
    }

    private val file   = File(sessionDir, AppConstants.EVENTS_FILENAME)
    private val gson   = Gson()
    private var count  = 0

    /** Write a confirmed ImpactEvent as a JSONL line. */
    @Synchronized
    fun append(event: ImpactEvent) {
        if (!event.confirmed) return   // guard: only custody-grade events
        try {
            val entry = EventEntry(
                tsUtc      = event.tsUtc,
                tsMonoNs   = event.tsMonoNs,
                eventType  = "COLLISION",
                direction  = event.direction,
                peakG      = event.peakG,
                durationMs = event.durationMs,
                axG        = event.axG,
                ayG        = event.ayG,
                azNetG     = event.azNetG,
                roadState  = event.roadState,
                seqNo      = ++count
            )
            FileWriter(file, /* append = */ true).use { it.write(gson.toJson(entry) + "\n") }
            Log.i(TAG, "Event #$count logged: ${event.direction}  peak=${event.peakG}g  " +
                "road=${event.roadState}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write event: ${e.message}")
        }
    }

    fun eventCount(): Int = count

    // ── JSONL schema ─────────────────────────────────────────────────────────

    private data class EventEntry(
        val type: String                        = "EVENT",
        @SerializedName("ts_utc")      val tsUtc:      String,
        @SerializedName("ts_mono_ns")  val tsMonoNs:   Long,
        @SerializedName("event_type")  val eventType:  String,
        val direction:                                  String,
        @SerializedName("peak_g")      val peakG:      Float,
        @SerializedName("duration_ms") val durationMs: Long,
        @SerializedName("ax_g")        val axG:        Float,
        @SerializedName("ay_g")        val ayG:        Float,
        @SerializedName("az_net_g")    val azNetG:     Float,
        @SerializedName("road_state")  val roadState:  String,
        @SerializedName("seq_no")      val seqNo:      Int
    )
}