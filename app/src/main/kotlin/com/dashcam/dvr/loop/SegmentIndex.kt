package com.dashcam.dvr.loop

import android.util.Log
import com.dashcam.dvr.loop.model.Segment
import com.dashcam.dvr.util.AppConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * SegmentIndex — persistent JSON registry of all loop-recording segments.
 *
 * Blueprint §4 Recording Modes — Continuous Loop Recording
 * ─────────────────────────────────────────────────────────────────────────────
 * Stored as sessionDir/segments/index.json.
 *
 * The file is rewritten atomically on every mutation (write to .tmp, then rename).
 * This means the index is always complete and readable even after a crash mid-write.
 *
 * FORMAT
 * ───────
 * {
 *   "session_id":   "session_20260308_232640",
 *   "schema":       "1.0",
 *   "segments":     [ { ...Segment fields... }, ... ]
 * }
 *
 * THREAD SAFETY
 * ──────────────
 * All methods are synchronized on `this`.  LoopRecorder calls them from its
 * dedicated IO coroutine — the lock ensures the in-memory list and on-disk file
 * stay consistent if the UI thread triggers a protectCurrentSegment() call
 * concurrently.
 */
class SegmentIndex(
    private val sessionDir: File,
    private val sessionId:  String
) {

    companion object {
        private const val TAG = "SegmentIndex"
        const val INDEX_FILENAME = "index.json"
        const val SEGMENTS_SUBDIR = "segments"
    }

    private val segmentsDir = File(sessionDir, SEGMENTS_SUBDIR).also { it.mkdirs() }
    private val indexFile   = File(segmentsDir, INDEX_FILENAME)

    /** In-memory list — canonical source of truth, mirrored to disk on every write. */
    private val segments = mutableListOf<Segment>()

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Load existing index from disk (for session recovery) or create empty index.
     * Call once before any other method.
     */
    @Synchronized
    fun load(): List<Segment> {
        segments.clear()
        if (!indexFile.exists()) {
            Log.i(TAG, "No existing index — starting fresh")
            persist()
            return emptyList()
        }
        return try {
            val root = JSONObject(indexFile.readText())
            val arr  = root.optJSONArray("segments") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                segments.add(Segment(
                    id            = o.getInt("id"),
                    rearFile      = o.getString("rear_file"),
                    frontFile     = o.getString("front_file"),
                    startTsUtc    = o.getString("start_ts_utc"),
                    endTsUtc      = o.optString("end_ts_utc").takeIf { it.isNotEmpty() },
                    durationMs    = if (o.has("duration_ms")) o.getLong("duration_ms") else null,
                    protected     = o.optBoolean("protected", false),
                    protectReason = o.optString("protect_reason").takeIf { it.isNotEmpty() },
                    status        = o.optString("status", Segment.STATUS_COMPLETE),
                    rearSizeBytes = o.optLong("rear_size_bytes", 0L),
                    frontSizeBytes= o.optLong("front_size_bytes", 0L)
                ))
            }
            Log.i(TAG, "Loaded ${segments.size} segments from index")
            segments.toList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load index: ${e.message} — starting fresh")
            segments.clear()
            persist()
            emptyList()
        }
    }

    // ── Segment lifecycle ─────────────────────────────────────────────────────

    /**
     * Open a new ACTIVE segment entry.
     * @return The newly created Segment.
     */
    @Synchronized
    fun openSegment(id: Int, rearFile: String, frontFile: String,
                    startTsUtc: String): Segment {
        val seg = Segment(
            id         = id,
            rearFile   = rearFile,
            frontFile  = frontFile,
            startTsUtc = startTsUtc,
            status     = Segment.STATUS_ACTIVE
        )
        segments.add(seg)
        persist()
        Log.i(TAG, "Segment #$id opened  rear=${rearFile.substringAfterLast('/')}")
        return seg
    }

    /**
     * Close the ACTIVE segment — mark COMPLETE, record end time and file sizes.
     * @return Updated segment, or null if no ACTIVE segment found.
     */
    @Synchronized
    fun closeSegment(id: Int, endTsUtc: String, rearFile: File, frontFile: File): Segment? {
        val idx = segments.indexOfFirst { it.id == id && it.status == Segment.STATUS_ACTIVE }
        if (idx < 0) { Log.w(TAG, "closeSegment: no ACTIVE segment with id=$id"); return null }
        val old = segments[idx]
        val start = parseUtcMs(old.startTsUtc)
        val end   = parseUtcMs(endTsUtc)
        val updated = old.copy(
            endTsUtc     = endTsUtc,
            durationMs   = if (start != null && end != null) end - start else null,
            status       = Segment.STATUS_COMPLETE,
            rearSizeBytes = if (rearFile.exists()) rearFile.length() else 0L,
            frontSizeBytes= if (frontFile.exists()) frontFile.length() else 0L
        )
        segments[idx] = updated
        persist()
        Log.i(TAG, "Segment #$id closed  dur=${updated.durationMs?.div(1000)}s  " +
                   "rear=${updated.rearSizeBytes / 1024}KB  front=${updated.frontSizeBytes / 1024}KB")
        return updated
    }

    /**
     * Flag a segment as PARTIAL (power loss / crash detection at session open).
     */
    @Synchronized
    fun markPartial(id: Int): Boolean {
        val idx = segments.indexOfFirst { it.id == id }
        if (idx < 0) return false
        segments[idx] = segments[idx].copy(status = Segment.STATUS_PARTIAL)
        persist()
        Log.w(TAG, "Segment #$id marked PARTIAL")
        return true
    }

    // ── Protection ────────────────────────────────────────────────────────────

    /**
     * Protect a segment from circular-buffer eviction.
     * If the protected-event cap is reached, returns false WITHOUT protecting.
     * Caller must notify the user and queue oldest for manual review.
     */
    @Synchronized
    fun protect(id: Int, reason: String): Boolean {
        val count = segments.count { it.protected && it.status != Segment.STATUS_DELETED }
        if (count >= AppConstants.MAX_PROTECTED_EVENTS) {
            Log.w(TAG, "Protected-event cap (${AppConstants.MAX_PROTECTED_EVENTS}) reached — " +
                       "cannot protect segment #$id reason=$reason")
            return false
        }
        val idx = segments.indexOfFirst { it.id == id }
        if (idx < 0) { Log.w(TAG, "protect: segment #$id not found"); return false }
        segments[idx] = segments[idx].copy(protected = true, protectReason = reason)
        persist()
        Log.i(TAG, "Segment #$id protected  reason=$reason  total=${count+1}/${AppConstants.MAX_PROTECTED_EVENTS}")
        return true
    }

    // ── Circular buffer eviction ──────────────────────────────────────────────

    /**
     * Evict the oldest unprotected COMPLETE segment.
     * Deletes both video files and marks the entry DELETED.
     * @return Freed bytes (rear + front size), or 0 if nothing to evict.
     */
    @Synchronized
    fun evictOldestUnprotected(sessionDir: File): Long {
        val candidate = segments
            .filter { !it.protected && it.status == Segment.STATUS_COMPLETE }
            .minByOrNull { it.id }
        if (candidate == null) {
            Log.w(TAG, "evictOldest: no unprotected complete segments to evict")
            return 0L
        }
        var freed = 0L
        listOf(candidate.rearFile, candidate.frontFile).forEach { rel ->
            val f = File(sessionDir, rel)
            if (f.exists()) { freed += f.length(); f.delete() }
        }
        val idx = segments.indexOfFirst { it.id == candidate.id }
        if (idx >= 0) segments[idx] = candidate.copy(
            status        = Segment.STATUS_DELETED,
            rearSizeBytes = 0L, frontSizeBytes = 0L
        )
        persist()
        Log.i(TAG, "Evicted segment #${candidate.id}  freed=${freed / (1024*1024)}MB")
        return freed
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    @Synchronized fun getAll(): List<Segment> = segments.toList()
    @Synchronized fun getActive(): Segment?   = segments.firstOrNull { it.status == Segment.STATUS_ACTIVE }
    @Synchronized fun nextId(): Int           = (segments.maxOfOrNull { it.id } ?: 0) + 1
    @Synchronized fun protectedCount(): Int   = segments.count { it.protected && it.status != Segment.STATUS_DELETED }
    @Synchronized fun segmentsDir(): File     = segmentsDir

    /** Total bytes of all non-deleted segments on disk (rear + front). */
    @Synchronized
    fun totalDiskBytes(): Long =
        segments.filter { it.status != Segment.STATUS_DELETED }
                .sumOf { it.rearSizeBytes + it.frontSizeBytes }

    // ── Persistence ───────────────────────────────────────────────────────────

    /** Atomic write: .tmp file then rename. */
    private fun persist() {
        try {
            val arr = JSONArray().apply {
                segments.forEach { s ->
                    put(JSONObject().apply {
                        put("id",               s.id)
                        put("rear_file",        s.rearFile)
                        put("front_file",       s.frontFile)
                        put("start_ts_utc",     s.startTsUtc)
                        put("end_ts_utc",       s.endTsUtc ?: "")
                        if (s.durationMs != null) put("duration_ms", s.durationMs)
                        put("protected",        s.protected)
                        put("protect_reason",   s.protectReason ?: "")
                        put("status",           s.status)
                        put("rear_size_bytes",  s.rearSizeBytes)
                        put("front_size_bytes", s.frontSizeBytes)
                    })
                }
            }
            val root = JSONObject().apply {
                put("session_id", sessionId)
                put("schema",     "1.0")
                put("segments",   arr)
            }
            val tmp = File(segmentsDir, "$INDEX_FILENAME.tmp")
            tmp.writeText(root.toString(2))
            tmp.renameTo(indexFile)     // atomic on ext4/F2FS
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist segment index: ${e.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val utcFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .also { it.timeZone = TimeZone.getTimeZone("UTC") }

    fun utcNow(): String = utcFmt.format(Date())

    private fun parseUtcMs(ts: String): Long? = try { utcFmt.parse(ts)?.time } catch (_: Exception) { null }
}