package com.dashcam.dvr.ui.events

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.dashcam.dvr.R
import com.dashcam.dvr.loop.model.Segment
import java.text.DecimalFormat

/**
 * SegmentAdapter — RecyclerView adapter for the Event Review screen.
 *
 * Displays protected segments grouped by session. Each item shows:
 *   - Segment number + reason (MANUAL / IMPACT / HARSH_BRAKE / etc.)
 *   - Timestamp range
 *   - Duration + combined file size
 *   - Status badge (COMPLETE / PARTIAL)
 */
class SegmentAdapter(
    private val onDeleteClick: (SessionSegmentItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM   = 1
    }

    /** Flat list of header + segment items in display order. */
    private val items = mutableListOf<ListItem>()

    sealed class ListItem {
        data class Header(val sessionId: String, val count: Int) : ListItem()
        data class Item(val data: SessionSegmentItem)            : ListItem()
    }

    data class SessionSegmentItem(
        val sessionId:   String,
        val sessionDir:  String,
        val segment:     Segment
    )

    fun submitSessions(sessions: List<Pair<String, List<Segment>>>) {
        items.clear()
        for ((sessionId, segs) in sessions) {
            val protected = segs.filter { it.protected && it.status != Segment.STATUS_DELETED }
            if (protected.isEmpty()) continue
            items.add(ListItem.Header(sessionId, protected.size))
            protected.forEach { items.add(ListItem.Item(SessionSegmentItem(sessionId, sessionId, it))) }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) =
        if (items[position] is ListItem.Header) TYPE_HEADER else TYPE_ITEM

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inf.inflate(R.layout.item_event_header, parent, false))
        } else {
            ItemVH(inf.inflate(R.layout.item_protected_segment, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.Header -> (holder as HeaderVH).bind(item)
            is ListItem.Item   -> (holder as ItemVH).bind(item.data, onDeleteClick)
        }
    }

    // ── ViewHolders ────────────────────────────────────────────────────────────

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvTitle: TextView = v.findViewById(R.id.tvEventHeader)
        fun bind(h: ListItem.Header) {
            tvTitle.text = "${h.sessionId}  •  ${h.count} protected"
        }
    }

    class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvReason   : TextView = v.findViewById(R.id.tvSegReason)
        private val tvTimestamp: TextView = v.findViewById(R.id.tvSegTimestamp)
        private val tvMeta     : TextView = v.findViewById(R.id.tvSegMeta)
        private val tvStatus   : TextView = v.findViewById(R.id.tvSegStatus)
        private val btnDelete  : View     = v.findViewById(R.id.btnSegDelete)

        private val mbFmt = DecimalFormat("0.0")

        fun bind(item: SessionSegmentItem, onDelete: (SessionSegmentItem) -> Unit) {
            val seg = item.segment
            tvReason.text    = "Seg #${seg.id}  •  ${seg.protectReason ?: "MANUAL"}"
            tvTimestamp.text = formatTs(seg.startTsUtc)
            val dur  = if (seg.durationMs != null) "${seg.durationMs / 1000}s" else "?"
            val size = mbFmt.format((seg.rearSizeBytes + seg.frontSizeBytes) / (1024.0 * 1024.0))
            tvMeta.text   = "Duration: $dur  •  Size: ${size} MB"
            tvStatus.text = seg.status.uppercase()
            tvStatus.setBackgroundResource(
                if (seg.status == Segment.STATUS_COMPLETE) R.drawable.chip_green
                else R.drawable.chip_amber
            )
            btnDelete.setOnClickListener { onDelete(item) }
        }

        private fun formatTs(ts: String): String = try {
            // "2026-03-08T23:26:40.123Z" -> "08 Mar 23:26:40"
            val parts = ts.split("T")
            val date  = parts[0].split("-")
            val months = arrayOf("","Jan","Feb","Mar","Apr","May","Jun",
                                 "Jul","Aug","Sep","Oct","Nov","Dec")
            val m = date[1].toIntOrNull() ?: 0
            "${date[2]} ${months.getOrElse(m){m.toString()}} ${parts[1].take(8)}"
        } catch (_: Exception) { ts }
    }
}
