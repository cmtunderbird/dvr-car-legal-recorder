package com.dashcam.dvr.ui.events

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dashcam.dvr.R
import com.dashcam.dvr.loop.SegmentIndex
import com.dashcam.dvr.loop.model.Segment
import com.dashcam.dvr.session.CustodyLog
import com.dashcam.dvr.util.AppConstants
import java.io.File

/**
 * EventReviewActivity -- browse and manage protected evidence segments.
 *
 * Blueprint S4: "When the cap is reached, the user is notified and oldest
 * protected events are queued for manual review before they can be overwritten.
 * The system must never silently discard protected evidence."
 *
 * Shows all session folders -> each session's protected segments -> allows deletion
 * (writes a custody.log entry before removing).
 */
class EventReviewActivity : AppCompatActivity() {

    private lateinit var rvEvents  : RecyclerView
    private lateinit var tvEmpty   : TextView
    private lateinit var tvCapWarn : TextView
    private lateinit var adapter   : SegmentAdapter

    // Installation UUID stored by SessionManager in dvr_session_prefs
    private val installationUuid: String by lazy {
        getSharedPreferences("dvr_session_prefs", Context.MODE_PRIVATE)
            .getString("installation_uuid", "UNKNOWN") ?: "UNKNOWN"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_review)
        rvEvents  = findViewById(R.id.rvProtectedEvents)
        tvEmpty   = findViewById(R.id.tvEventsEmpty)
        tvCapWarn = findViewById(R.id.tvEventsCapWarning)

        adapter = SegmentAdapter(onDeleteClick = ::confirmDelete)
        rvEvents.layoutManager = LinearLayoutManager(this)
        rvEvents.adapter = adapter

        supportActionBar?.apply {
            title = "Protected Events"
            setDisplayHomeAsUpEnabled(true)
        }
        loadSessions()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadSessions() {
        val sessionsRoot = getExternalFilesDir("sessions") ?: filesDir.resolve("sessions")
        val sessions = sessionsRoot
            .listFiles { f -> f.isDirectory && f.name.startsWith(AppConstants.SESSION_DIR_PREFIX) }
            ?.sortedByDescending { it.name }
            ?.map { dir ->
                val idx  = SegmentIndex(dir, dir.name)
                val segs = idx.load()
                Pair(dir.name, segs)
            } ?: emptyList()

        val totalProtected = sessions.sumOf { (_, segs) ->
            segs.count { it.protected && it.status != Segment.STATUS_DELETED }
        }

        tvCapWarn.visibility = if (totalProtected >= AppConstants.MAX_PROTECTED_EVENTS)
            View.VISIBLE else View.GONE
        tvCapWarn.text = "\u26a0 Protected event cap reached ($totalProtected / " +
            "${AppConstants.MAX_PROTECTED_EVENTS}). " +
            "Delete old events to allow new ones to be protected."

        adapter.submitSessions(sessions)
        tvEmpty.visibility  = if (totalProtected == 0) View.VISIBLE else View.GONE
        rvEvents.visibility = if (totalProtected == 0) View.GONE    else View.VISIBLE
    }

    // ── Delete flow ───────────────────────────────────────────────────────────

    private fun confirmDelete(item: SegmentAdapter.SessionSegmentItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Protected Segment?")
            .setMessage(
                "Segment #${item.segment.id} " +
                "(${item.segment.protectReason ?: "MANUAL"}) " +
                "from session ${item.sessionId} will be permanently deleted.\n\n" +
                "This action is recorded in the custody log and cannot be undone."
            )
            .setPositiveButton("Delete") { _, _ -> deleteSegment(item) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSegment(item: SegmentAdapter.SessionSegmentItem) {
        val sessionsRoot = getExternalFilesDir("sessions") ?: filesDir.resolve("sessions")
        val sessionDir   = File(sessionsRoot, item.sessionId)

        // Write custody entry before deletion (CustodyLog is an object singleton)
        CustodyLog.append(
            sessionDir       = sessionDir,
            installationUuid = installationUuid,
            action           = "DELETE_PROTECTED_SEGMENT",
            sessionId        = item.sessionId,
            detail           = "seg_id=${item.segment.id} " +
                               "reason=${item.segment.protectReason} " +
                               "files=${item.segment.rearFile},${item.segment.frontFile}",
            result           = "OK"
        )

        // Delete video files
        listOf(item.segment.rearFile, item.segment.frontFile).forEach { rel ->
            val f = File(sessionDir, rel)
            if (f.exists()) f.delete()
        }

        // Mark segment DELETED in the index, then refresh UI
        val idx = SegmentIndex(sessionDir, item.sessionId)
        idx.load()
        idx.markDeleted(item.segment.id)
        loadSessions()
    }
}
