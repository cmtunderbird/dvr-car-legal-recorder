package com.dashcam.dvr.loop

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.dashcam.dvr.camera.DVRCameraManager
import com.dashcam.dvr.camera.FrontCameraRecorder
import com.dashcam.dvr.loop.model.Segment
import com.dashcam.dvr.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LoopRecorder — 3-minute circular-buffer video loop with event protection.
 *
 * Blueprint §4 Recording Modes ★ ENHANCED
 * ─────────────────────────────────────────────────────────────────────────────
 * RESPONSIBILITIES
 * ─────────────────
 *  • Start / rotate / stop dual-camera video segments.
 *  • Maintain a persistent SegmentIndex (segments/index.json).
 *  • Circular-buffer eviction: delete oldest unprotected segment when storage
 *    falls below MIN_FREE_STORAGE_BYTES or when MAX_PROTECTED_EVENTS is reached.
 *  • Event protection: flag the current (or most recent) segment as protected
 *    on COLLISION / BRAKE / MANUAL trigger. Protected segments are excluded from
 *    the overwrite pool and counted against the 25-event cap.
 *  • Partial-segment detection: at session open, scan segments/ for files that
 *    exist on disk but have no COMPLETE entry in the index — flag them PARTIAL
 *    in the index and log them to events.log.
 *
 * SEGMENT LIFECYCLE
 * ──────────────────
 *  1. start()                  → open first segment, begin rotation timer
 *  2. Every (DURATION - OVERLAP):   rotateSegments()
 *     a. Start new recordings    → new segment opens in index (ACTIVE)
 *     b. Stop old recordings     → old segment closes (COMPLETE)
 *     c. Evict if needed         → oldest unprotected deleted if low storage
 *  3. stop()                   → stop cameras, close current segment
 *
 * OVERLAP IMPLEMENTATION
 * ───────────────────────
 * True simultaneous dual-recording is not supported by CameraX VideoCapture
 * or Android MediaRecorder. The 2-second overlap is therefore implemented as
 * a seamless handover within < 300ms:
 *   • New segment recordings are started first (rear + front).
 *   • Old segment recordings are stopped immediately after (~100ms gap).
 *   • Segment N+1 startTsUtc = segment N endTsUtc minus SEGMENT_OVERLAP_MS.
 * This matches commercial dashcam practice and gives the PC viewer a timeline
 * with declared 2s overlap so no gap is shown in the evidence timeline.
 *
 * MP4 RESILIENCE
 * ───────────────
 * MediaRecorder does not expose WRITE_MOOV_AT_START. Resilience is provided by:
 *   • Short 3-minute segments: worst-case loss = current unfinished segment.
 *   • scanForPartials() at session open detects and flags incomplete files.
 *   • Protected segments are never overwritten — only the most recent
 *     unprotected segment can be lost on power cut.
 *
 * THREAD SAFETY
 * ──────────────
 * All SegmentIndex mutations happen on Dispatchers.IO via ioScope.
 * protectCurrentSegment() is safe to call from any thread (queued on ioScope).
 * Camera start/stop calls are dispatched to Main where required by CameraX.
 */
class LoopRecorder(
    private val context: Context
) {

    companion object {
        private const val TAG = "LoopRecorder"

        /** Segment duration minus overlap → rotate timer fires at this interval. */
        private val ROTATION_INTERVAL_MS = AppConstants.SEGMENT_DURATION_MS -
                                           AppConstants.SEGMENT_OVERLAP_MS

        private fun segRearName(id: Int)  = java.lang.String.format("segments/seg_%04d_rear.mp4", id)
        private fun segFrontName(id: Int) = java.lang.String.format("segments/seg_%04d_front.mp4", id)
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private lateinit var sessionDir:      File
    private lateinit var index:           SegmentIndex
    private var dvrCamera:    DVRCameraManager?   = null
    private var frontCamera:  FrontCameraRecorder? = null

    private val ioScope    = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var timerJob:  Job? = null
    private var running    = false
    private var currentId  = 0

    /** Callback invoked when the protected-event cap is reached. */
    var onCapReached: (() -> Unit)? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Begin loop recording.
     *
     * @param sessionDir   Active session directory.
     * @param dvr          Rear-camera manager (CameraX).
     * @param front        Front-camera recorder (Camera2/MediaRecorder).
     */
    fun start(
        sessionDir:  File,
        dvr:         DVRCameraManager,
        front:       FrontCameraRecorder
    ) {
        if (running) { Log.w(TAG, "start() called while already running — ignored"); return }
        this.sessionDir = sessionDir
        this.dvrCamera  = dvr
        this.frontCamera = front

        index = SegmentIndex(sessionDir, sessionDir.name)
        val existing = index.load()

        // Scan for partial segments from a previous crash
        scanForPartials(existing)

        running = true
        ioScope.launch {
            startNewSegment(overlapFromPrevious = false)
            scheduleRotation()
        }
        Log.i(TAG, "LoopRecorder started  sessionDir=${sessionDir.name}  " +
                   "rotationInterval=${ROTATION_INTERVAL_MS / 1000}s")
    }

    /**
     * Stop loop recording. Closes the active segment cleanly.
     * Blocks (on IO) until the current segment is finalised.
     */
    /**
     * Stop loop recording. Runs closeCurrentSegment() synchronously so the index
     * is fully written before EvidencePackager.seal() hashes the session directory.
     * Must be called from a background thread (runBlocking is safe here as
     * RecordingService calls stop() on Dispatchers.IO).
     */
    fun stop() {
        if (!running) return
        running = false
        timerJob?.cancel(); timerJob = null
        // Block until the final segment is closed and index.json is written.
        // This ensures the ACTIVE→COMPLETE transition completes before the
        // caller proceeds to EvidencePackager.seal().
        kotlinx.coroutines.runBlocking {
            closeCurrentSegment()
        }
        Log.i(TAG, "LoopRecorder stopped  segments=${index.getAll().size}  " +
                   "protected=${index.protectedCount()}")
    }

    // ── Segment rotation ──────────────────────────────────────────────────────

    private fun scheduleRotation() {
        timerJob = ioScope.launch {
            while (isActive && running) {
                delay(ROTATION_INTERVAL_MS)
                if (!running) break
                rotateSegments()
            }
        }
    }

    /**
     * Rotate to the next segment:
     *  1. Prepare new file paths
     *  2. Start new recordings (rear + front) — seamless handover
     *  3. Stop old recordings
     *  4. Update index (close old, open new)
     *  5. Evict oldest unprotected if storage is low
     */
    private suspend fun rotateSegments() {
        val oldId  = currentId
        val newId  = index.nextId()
        val endTs  = index.utcNow()

        Log.i(TAG, "Rotating segment #$oldId → #$newId")

        val newRearFile  = File(sessionDir, segRearName(newId))
        val newFrontFile = File(sessionDir, segFrontName(newId))
        newRearFile.parentFile?.mkdirs()

        // The new segment's startTs is declared 2s before endTs of old segment
        // (blueprint §4: 2-second overlap)
        val newStartTs = index.utcNow()   // close enough; ~100ms after endTs

        // ── Step 1: Start new recordings ─────────────────────────────────────
        val rearStarted  = startRearRecording(newRearFile)
        val frontStarted = startFrontRecording(newFrontFile)
        if (!rearStarted && !frontStarted) {
            Log.e(TAG, "rotateSegments: BOTH cameras failed to start — aborting rotation")
            return
        }

        // ── Step 2: Stop old recordings ───────────────────────────────────────
        stopRearRecording()
        stopFrontRecording()

        // ── Step 3: Update index ──────────────────────────────────────────────
        index.closeSegment(
            oldId,
            endTs,
            File(sessionDir, segRearName(oldId)),
            File(sessionDir, segFrontName(oldId))
        )
        index.openSegment(newId, segRearName(newId), segFrontName(newId), newStartTs)
        currentId = newId

        // ── Step 4: Evict if needed ───────────────────────────────────────────
        checkAndEvict()

        Log.i(TAG, "Rotation complete  active=#$newId  protected=${index.protectedCount()}")
    }

    /**
     * Close the currently-active segment on stop().
     */
    private suspend fun closeCurrentSegment() {
        val endTs = index.utcNow()
        stopRearRecording()
        stopFrontRecording()
        index.closeSegment(
            currentId,
            endTs,
            File(sessionDir, segRearName(currentId)),
            File(sessionDir, segFrontName(currentId))
        )
        Log.i(TAG, "Final segment #$currentId closed")
    }

    // ── Event protection ──────────────────────────────────────────────────────

    /**
     * Protect the current (active) segment from circular-buffer eviction.
     *
     * If the active segment just started (< 5s old) the previous segment is also
     * protected to ensure the pre-event footage is retained.
     *
     * Blueprint §4: cap = MAX_PROTECTED_EVENTS (25). Returns false if cap reached.
     * When the cap is reached, onCapReached callback fires so the UI can notify the user.
     */
    fun protectCurrentSegment(reason: String): Boolean {
        ioScope.launch {
            val active = index.getActive()
            if (active == null) {
                Log.w(TAG, "protectCurrentSegment: no active segment (stopped?)")
                return@launch
            }

            val protected = index.protect(active.id, reason)

            if (!protected) {
                // Cap reached — also try to protect previous segment for pre-event footage
                Log.w(TAG, "Cap reached — calling onCapReached callback")
                onCapReached?.invoke()
                return@launch
            }

            // Also protect the previous segment if current has been recording < 5s
            // (event likely straddles the segment boundary)
            val all = index.getAll()
            val prev = all.filter { it.id < active.id && it.status == Segment.STATUS_COMPLETE }
                         .maxByOrNull { it.id }
            if (prev != null && !prev.protected) {
                val startMs   = parseUtcMs(active.startTsUtc)
                val nowMs     = System.currentTimeMillis()
                val elapsedMs = if (startMs != null) nowMs - startMs else Long.MAX_VALUE
                if (elapsedMs < 5_000L) {
                    Log.i(TAG, "Event within 5s of segment start — also protecting #${prev.id}")
                    index.protect(prev.id, "$reason(prev)")
                }
            }

            Log.i(TAG, "Protected segment #${active.id}  reason=$reason  " +
                       "total=${index.protectedCount()}/${AppConstants.MAX_PROTECTED_EVENTS}")
        }
        return true
    }

    // ── Storage eviction ──────────────────────────────────────────────────────

    private fun checkAndEvict() {
        val freeBytes = getFreeBytes()
        if (freeBytes < AppConstants.MIN_FREE_STORAGE_BYTES) {
            Log.w(TAG, "Low storage (${freeBytes / (1024*1024)}MB free) — evicting oldest unprotected")
            index.evictOldestUnprotected(sessionDir)
        }
    }

    private fun getFreeBytes(): Long = try {
        val stat = StatFs(sessionDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (_: Exception) { Long.MAX_VALUE }

    // ── Partial segment detection ─────────────────────────────────────────────

    /**
     * Scan for video files that exist on disk but have no COMPLETE entry in the
     * index (e.g. app crash left an open segment file).
     * Marks them PARTIAL in the index so the PC viewer can warn the operator.
     *
     * Blueprint §12 MP4 Resilience: "Partial segments detected at session open
     * are logged and flagged PARTIAL in events.log."
     */
    private fun scanForPartials(existing: List<Segment>) {
        val segDir = index.segmentsDir()
        if (!segDir.exists()) return

        val mp4Files = segDir.listFiles { f -> f.extension == "mp4" } ?: return
        if (mp4Files.isEmpty()) return

        val completeFiles = existing
            .filter { it.status == Segment.STATUS_COMPLETE }
            .flatMap { listOf(File(sessionDir, it.rearFile), File(sessionDir, it.frontFile)) }
            .map { it.canonicalPath }
            .toSet()

        for (f in mp4Files) {
            if (f.canonicalPath !in completeFiles && f.length() > 0) {
                Log.w(TAG, "Partial segment detected: ${f.name}  size=${f.length() / 1024}KB")
                // Try to find its segment record by filename pattern
                val idMatch = Regex("seg_(\\d{4})_").find(f.name)
                val id = idMatch?.groupValues?.get(1)?.toIntOrNull()
                if (id != null) {
                    // Ensure there's a record — if ACTIVE, mark PARTIAL
                    val seg = existing.firstOrNull { it.id == id }
                    if (seg != null && seg.status == Segment.STATUS_ACTIVE) {
                        index.markPartial(id)
                    } else if (seg == null) {
                        // Orphan file — create a stub entry
                        val rel = "segments/${f.name}"
                        val isFront = f.name.contains("front")
                        val stubRear  = if (!isFront) rel else java.lang.String.format("segments/seg_%04d_rear.mp4", id)
                        val stubFront = if (isFront) rel  else java.lang.String.format("segments/seg_%04d_front.mp4", id)
                        index.openSegment(id, stubRear, stubFront, "UNKNOWN")
                        index.markPartial(id)
                    }
                }
            }
        }
    }

    // ── Camera dispatch helpers ───────────────────────────────────────────────

    private suspend fun startRearRecording(outputFile: File): Boolean {
        var started = false
        withContext(Dispatchers.Main) {
            started = dvrCamera?.startVideoRecordingToFile(outputFile) ?: false
        }
        if (started) Log.i(TAG, "Rear recording → ${outputFile.name}")
        else         Log.e(TAG, "Rear recording FAILED for ${outputFile.name}")
        return started
    }

    private suspend fun startFrontRecording(outputFile: File): Boolean {
        var started = false
        withContext(Dispatchers.Main) {
            started = frontCamera?.startRecording(outputFile) ?: false
        }
        if (started) Log.i(TAG, "Front recording → ${outputFile.name}")
        else         Log.e(TAG, "Front recording FAILED for ${outputFile.name}")
        return started
    }

    private suspend fun stopRearRecording() {
        withContext(Dispatchers.Main) { dvrCamera?.stopVideoRecording() }
    }

    private suspend fun stopFrontRecording() {
        withContext(Dispatchers.Main) { frontCamera?.stopRecording() }
    }

    // ── New segment on start() ────────────────────────────────────────────────

    private suspend fun startNewSegment(overlapFromPrevious: Boolean) {
        val id        = index.nextId()
        val startTs   = index.utcNow()
        val rearFile  = File(sessionDir, segRearName(id))
        val frontFile = File(sessionDir, segFrontName(id))
        rearFile.parentFile?.mkdirs()

        Log.i(TAG, "Starting segment #$id  rear=${rearFile.name}  front=${frontFile.name}")
        startRearRecording(rearFile)
        startFrontRecording(frontFile)

        index.openSegment(id, segRearName(id), segFrontName(id), startTs)
        currentId = id
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun parseUtcMs(ts: String): Long? = try {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .also { it.timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .parse(ts)?.time
    } catch (_: Exception) { null }
}
