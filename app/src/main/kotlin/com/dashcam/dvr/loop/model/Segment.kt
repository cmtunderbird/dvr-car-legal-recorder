package com.dashcam.dvr.loop.model

/**
 * Segment — one video loop-recording segment record.
 *
 * Blueprint §4 Recording Modes — Continuous Loop Recording
 * ─────────────────────────────────────────────────────────────────────────────
 * A segment pairs a rear-camera MP4 with a front-camera MP4.
 * Both files cover the same time window (same [startTsUtc, endTsUtc]).
 *
 * File paths are stored relative to the session directory so the index
 * remains valid if the DVR folder is moved (e.g. copied to PC).
 *
 * STATUS VALUES
 * ──────────────
 *   ACTIVE    — recording in progress, endTsUtc/durationMs are null
 *   COMPLETE  — recording cleanly finalized (moov atom written)
 *   PARTIAL   — recording was interrupted (power loss / crash); file may be
 *               partially playable; logged at session open via scanForPartials()
 *   DELETED   — segment files deleted by circular-buffer eviction;
 *               metadata row kept for audit trail
 *
 * PROTECTION
 * ───────────
 *   protected = true → excluded from circular-buffer overwrite pool.
 *   Reasons: "COLLISION" | "BRAKE" | "MANUAL" | "DROWSY" (future).
 *   Protected segments count against MAX_PROTECTED_EVENTS cap (25).
 *   When cap is reached the service emits a user notification.
 *
 * OVERLAP NOTE
 * ─────────────
 *   Blueprint specifies 2-second segment overlap to eliminate coverage gaps.
 *   CameraX VideoCapture and MediaRecorder do not support simultaneous dual-
 *   output to separate files. Overlap is therefore implemented as a seamless
 *   atomic handover: segment N+1 is started and segment N is stopped within
 *   the same 300ms window. The startTsUtc of segment N+1 is set to
 *   segment N's endTsUtc minus SEGMENT_OVERLAP_MS (2s) so the timestamp
 *   metadata declares the intended overlap, and PC viewer can show continuous
 *   coverage with no gap.
 */
data class Segment(
    /** 1-based, monotonically increasing within the session */
    val id:              Int,
    /** Relative path from session dir, e.g. "segments/seg_0001_rear.mp4" */
    val rearFile:        String,
    /** Relative path from session dir, e.g. "segments/seg_0001_front.mp4" */
    val frontFile:       String,
    /** ISO-8601 UTC — actual start of recording (adjusted for 2s overlap on N>1) */
    val startTsUtc:      String,
    /** ISO-8601 UTC — null while ACTIVE */
    val endTsUtc:        String?    = null,
    /** Milliseconds — null while ACTIVE */
    val durationMs:      Long?      = null,
    /** true = excluded from overwrite pool */
    val protected:       Boolean    = false,
    /** "COLLISION" | "BRAKE" | "MANUAL" | "DROWSY" | null */
    val protectReason:   String?    = null,
    /** "ACTIVE" | "COMPLETE" | "PARTIAL" | "DELETED" */
    val status:          String     = STATUS_ACTIVE,
    val rearSizeBytes:   Long       = 0L,
    val frontSizeBytes:  Long       = 0L
) {
    companion object {
        const val STATUS_ACTIVE   = "ACTIVE"
        const val STATUS_COMPLETE = "COMPLETE"
        const val STATUS_PARTIAL  = "PARTIAL"
        const val STATUS_DELETED  = "DELETED"
    }
}