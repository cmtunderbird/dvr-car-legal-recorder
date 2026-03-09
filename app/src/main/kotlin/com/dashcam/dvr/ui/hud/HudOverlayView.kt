package com.dashcam.dvr.ui.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.dashcam.dvr.telemetry.model.GpsRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * HudOverlayView — transparent overlay drawn on top of the rear camera preview.
 *
 * Blueprint §13: "HUD overlays are applied during playback or export only —
 * never burned into the original recorded files."
 *
 * This View is DISPLAY-ONLY. It does NOT modify any recorded video file.
 *
 * Layout regions:
 *   Top-left     : ● REC indicator + elapsed timer
 *   Top-right    : GPS fix status chip (VALID / ACQUIRING / NO FIX)
 *   Bottom-left  : Speed (large) + heading arrow
 *   Bottom-right : Lat / Lon + UTC clock
 *   Bottom-center: Protected events badge (shown only when > 0)
 */
class HudOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── State ─────────────────────────────────────────────────────────────────
    private var isRecording      = false
    private var elapsedSeconds   = 0L
    private var hasGpsFix        = false
    private var gpsRecord: GpsRecord? = null
    private var protectedCount   = 0
    private var blinkVisible     = true   // toggled externally for REC blink

    // ── Paints ────────────────────────────────────────────────────────────────
    private val paintRecDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C0392B")   // dvr_red
        style = Paint.Style.FILL
    }
    private val paintTimer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 0f   // set in onSizeChanged
        typeface = Typeface.MONOSPACE
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AEB6BF")   // text_secondary
        textSize = 0f
        typeface = Typeface.MONOSPACE
    }
    private val paintSpeed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 0f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val paintSpeedUnit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AEB6BF")
        textSize = 0f
        typeface = Typeface.MONOSPACE
    }
    private val paintGpsChip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#17A589")   // dvr_teal
        style = Paint.Style.FILL
    }
    private val paintGpsText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 0f
        typeface = Typeface.MONOSPACE
    }
    private val paintCoords = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AEB6BF")
        textSize = 0f
        typeface = Typeface.MONOSPACE
    }
    private val paintBadgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C0392B")
        style = Paint.Style.FILL
    }
    private val paintBadgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 0f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val chipRect = RectF()
    private val badgeRect = RectF()

    // ── Dimensions (set in onSizeChanged) ────────────────────────────────────
    private var pad = 0f
    private var dotR = 0f

    private val utcFmt = SimpleDateFormat("HH:mm:ss 'UTC'", Locale.US)
        .also { it.timeZone = TimeZone.getTimeZone("UTC") }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pad  = h * 0.025f
        dotR = h * 0.022f
        val timerTs  = h * 0.055f
        val labelTs  = h * 0.038f
        val speedTs  = h * 0.110f
        val unitTs   = h * 0.038f
        val gpsTs    = h * 0.034f
        val coordTs  = h * 0.032f
        val badgeTs  = h * 0.038f
        paintTimer.textSize     = timerTs
        paintLabel.textSize     = labelTs
        paintSpeed.textSize     = speedTs
        paintSpeedUnit.textSize = unitTs
        paintGpsText.textSize   = gpsTs
        paintCoords.textSize    = coordTs
        paintBadgeText.textSize = badgeTs
    }

    // ── Public update API (called from MainActivity coroutines) ───────────────

    fun update(
        recording: Boolean,
        elapsed: Long,
        gpsFix: Boolean,
        gps: GpsRecord?,
        protected: Int,
        blink: Boolean
    ) {
        isRecording    = recording
        elapsedSeconds = elapsed
        hasGpsFix      = gpsFix
        gpsRecord      = gps
        protectedCount = protected
        blinkVisible   = blink
        invalidate()
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        drawRecIndicator(canvas, h)
        drawGpsChip(canvas, w, h)
        drawSpeedBlock(canvas, h)
        drawCoordsBlock(canvas, w, h)
        if (protectedCount > 0) drawProtectedBadge(canvas, w, h)
    }

    /** Top-left: ● REC  00:00:00  (dot blinks while recording) */
    private fun drawRecIndicator(canvas: Canvas, h: Float) {
        var cx = pad + dotR
        val cy = pad + dotR
        if (isRecording) {
            if (blinkVisible) canvas.drawCircle(cx, cy, dotR, paintRecDot)
            cx += dotR * 1.6f
            canvas.drawText("REC  ${formatElapsed(elapsedSeconds)}", cx, cy + paintTimer.textSize * 0.35f, paintTimer)
        } else {
            paintLabel.color = Color.parseColor("#AEB6BF")
            canvas.drawText("STANDBY", cx, cy + paintLabel.textSize * 0.35f, paintLabel)
        }
    }

    /** Top-right: GPS fix chip */
    private fun drawGpsChip(canvas: Canvas, w: Float, h: Float) {
        val text = if (hasGpsFix) "GPS VALID" else "GPS ACQUIRING..."
        val chipColor = if (hasGpsFix) Color.parseColor("#17A589") else Color.parseColor("#D4AC0D")
        paintGpsChip.color = chipColor
        val textW   = paintGpsText.measureText(text)
        val chipPad = pad * 0.6f
        val chipH   = paintGpsText.textSize + chipPad * 2
        val right   = w - pad
        val left    = right - textW - chipPad * 2
        val top     = pad
        val bot     = pad + chipH
        chipRect.set(left, top, right, bot)
        canvas.drawRoundRect(chipRect, chipH / 2, chipH / 2, paintGpsChip)
        canvas.drawText(text, left + chipPad, bot - chipPad * 0.9f, paintGpsText)
    }

    /** Bottom-left: large speed number + km/h label */
    private fun drawSpeedBlock(canvas: Canvas, h: Float) {
        val speed = gpsRecord?.speedKmh?.roundToInt() ?: 0
        val speedStr = "%3d".format(speed)
        val baseline = h - pad
        canvas.drawText(speedStr, pad, baseline, paintSpeed)
        val speedW = paintSpeed.measureText(speedStr)
        canvas.drawText(" km/h", pad + speedW, baseline, paintSpeedUnit)
    }

    /** Bottom-right: lat/lon + UTC time */
    private fun drawCoordsBlock(canvas: Canvas, w: Float, h: Float) {
        val rec   = gpsRecord
        val lat   = if (rec != null) "%.6f".format(rec.lat) else "---"
        val lon   = if (rec != null) "%.6f".format(rec.lon) else "---"
        val utc   = utcFmt.format(Date())
        val lineH = paintCoords.textSize * 1.3f
        val maxW  = maxOf(
            paintCoords.measureText(lat),
            paintCoords.measureText(lon),
            paintCoords.measureText(utc)
        )
        val x = w - maxW - pad
        canvas.drawText(utc, x, h - pad - lineH * 2, paintCoords)
        canvas.drawText(lat, x, h - pad - lineH,     paintCoords)
        canvas.drawText(lon, x, h - pad,              paintCoords)
    }

    /** Bottom-center: protected events badge */
    private fun drawProtectedBadge(canvas: Canvas, w: Float, h: Float) {
        val text = "PROTECTED: $protectedCount / 25"
        val tw   = paintBadgeText.measureText(text)
        val bp   = pad * 0.5f
        val bh   = paintBadgeText.textSize + bp * 2
        val cx   = w / 2
        badgeRect.set(cx - tw / 2 - bp, h - pad - bh, cx + tw / 2 + bp, h - pad)
        canvas.drawRoundRect(badgeRect, bh / 2, bh / 2, paintBadgeBg)
        canvas.drawText(text, cx - tw / 2, h - pad - bp * 0.4f, paintBadgeText)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun formatElapsed(secs: Long): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
