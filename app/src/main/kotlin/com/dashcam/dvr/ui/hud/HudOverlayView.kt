package com.dashcam.dvr.ui.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * HudOverlayView — military/aviation-style transparent overlay on the rear camera preview.
 *
 * Blueprint §13: overlays are DISPLAY-ONLY — never burned into recorded files.
 */
class HudOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Military color palette ────────────────────────────────────────────────
    companion object {
        private const val GREEN       = 0xFF00FF41.toInt()  // phosphor green
        private const val GREEN_DIM   = 0xFF00CC33.toInt()  // dimmer green
        private const val GREEN_FAINT = 0x5000FF41.toInt()  // grid lines
        private const val AMBER       = 0xFFFFB000.toInt()  // warning amber
        private const val RED         = 0xFFFF3333.toInt()  // alert red
        private const val WHITE       = 0xFFFFFFFF.toInt()
        private const val BG_PANEL    = 0x99111111.toInt()  // 60% dark
        private const val BG_CHIP     = 0xCC1A1A1A.toInt()  // 80% dark chip

        private const val SPEED_HISTORY_SIZE = 60  // 60 samples (~1 min at 1 Hz)
        private const val ACCEL_HISTORY_SIZE = 50  // 50 samples for g-force trail
        private const val G_METER_MAX_G      = 3.0f
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private var state = HudState()

    // ── Circular buffers for graphs ───────────────────────────────────────────
    private val speedHistory  = FloatArray(SPEED_HISTORY_SIZE)
    private var speedHistHead = 0
    private var speedHistCount = 0
    private var lastSpeedPushMs = 0L

    private val accelHistX = FloatArray(ACCEL_HISTORY_SIZE)
    private val accelHistY = FloatArray(ACCEL_HISTORY_SIZE)
    private var accelHistHead  = 0
    private var accelHistCount = 0

    // ── Paints ────────────────────────────────────────────────────────────────
    private val mono = Typeface.MONOSPACE
    private val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private val pGreen = mkPaint(GREEN, style = Paint.Style.STROKE, width = 1.5f)
    private val pGreenFill = mkPaint(GREEN, style = Paint.Style.FILL)
    private val pGreenDim = mkPaint(GREEN_DIM, style = Paint.Style.FILL)
    private val pGreenFaint = mkPaint(GREEN_FAINT, style = Paint.Style.STROKE, width = 0.8f)
    private val pAmber = mkPaint(AMBER, style = Paint.Style.FILL)
    private val pRed = mkPaint(RED, style = Paint.Style.FILL)
    private val pRedStroke = mkPaint(RED, style = Paint.Style.STROKE, width = 2f)
    private val pBg = mkPaint(BG_PANEL, style = Paint.Style.FILL)
    private val pBgChip = mkPaint(BG_CHIP, style = Paint.Style.FILL)

    // Text paints — sizes set in onSizeChanged
    private val pTxtLarge  = mkTextPaint(GREEN, monoBold, 0f)
    private val pTxtMed    = mkTextPaint(GREEN, mono, 0f)
    private val pTxtSmall  = mkTextPaint(GREEN, mono, 0f)
    private val pTxtTiny   = mkTextPaint(GREEN_DIM, mono, 0f)
    private val pTxtAmber  = mkTextPaint(AMBER, monoBold, 0f)
    private val pTxtRed    = mkTextPaint(RED, monoBold, 0f)
    private val pTxtWhite  = mkTextPaint(WHITE, monoBold, 0f)
    private val pRecDot = mkPaint(RED, style = Paint.Style.FILL)
    private val pSpeedLine = mkPaint(GREEN, style = Paint.Style.STROKE, width = 1.5f)
    private val pAccelTrail = mkPaint(GREEN_FAINT, style = Paint.Style.FILL)
    private val pAccelDot = mkPaint(GREEN, style = Paint.Style.FILL)
    private val pDashed = mkPaint(GREEN_FAINT, style = Paint.Style.STROKE, width = 0.8f).apply {
        pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
    }

    private val tempRect = RectF()
    private val tempPath = Path()

    // ── Dimensions (set in onSizeChanged) ─────────────────────────────────────
    private var pad = 0f
    private var dotR = 0f
    private var szLarge = 0f
    private var szMed = 0f
    private var szSmall = 0f
    private var szTiny = 0f

    private val utcFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        .also { it.timeZone = TimeZone.getTimeZone("UTC") }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pad     = h * 0.020f
        dotR    = h * 0.018f
        szLarge = h * 0.110f
        szMed   = h * 0.042f
        szSmall = h * 0.032f
        szTiny  = h * 0.026f
        pTxtLarge.textSize  = szLarge
        pTxtMed.textSize    = szMed
        pTxtSmall.textSize  = szSmall
        pTxtTiny.textSize   = szTiny
        pTxtAmber.textSize  = szSmall
        pTxtRed.textSize    = szSmall
        pTxtWhite.textSize  = szSmall

        pGreen.strokeWidth      = h * 0.003f
        pGreenFaint.strokeWidth = h * 0.002f
        pRedStroke.strokeWidth  = h * 0.004f
        pDashed.strokeWidth     = h * 0.002f
        pSpeedLine.strokeWidth  = h * 0.004f
    }

    // ── Public update API ─────────────────────────────────────────────────────

    fun update(newState: HudState) {
        // Push speed sample (~1 Hz)
        val now = System.currentTimeMillis()
        if (now - lastSpeedPushMs >= 900) {
            lastSpeedPushMs = now
            val spd = newState.gps?.speedKmh ?: 0f
            speedHistory[speedHistHead] = spd
            speedHistHead = (speedHistHead + 1) % SPEED_HISTORY_SIZE
            if (speedHistCount < SPEED_HISTORY_SIZE) speedHistCount++
        }

        // Push accel sample (on every call — ~5-10 Hz effective from GPS+timer cadence)
        accelHistX[accelHistHead] = newState.accelAxG
        accelHistY[accelHistHead] = newState.accelAyG
        accelHistHead = (accelHistHead + 1) % ACCEL_HISTORY_SIZE
        if (accelHistCount < ACCEL_HISTORY_SIZE) accelHistCount++

        state = newState
        invalidate()
    }

    // Backward-compat shim for old API (blink loop still uses this pattern)
    fun update(
        recording: Boolean, elapsed: Long, gpsFix: Boolean,
        gps: com.dashcam.dvr.telemetry.model.GpsRecord?, protected: Int, blink: Boolean
    ) {
        update(HudState(
            isRecording = recording, elapsedSeconds = elapsed,
            hasGpsFix = gpsFix, gps = gps,
            protectedCount = protected, blinkVisible = blink
        ))
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 10 || h < 10) return

        drawTopBar(canvas, w, h)
        drawSpeedBlock(canvas, w, h)
        drawGMeter(canvas, w, h)
        drawClinometer(canvas, w, h)
        drawPositionBlock(canvas, w, h)
        drawBottomBar(canvas, w, h)
        drawSpeedGraph(canvas, w, h)
    }

    // ── TOP BAR: REC + GPS status ─────────────────────────────────────────────

    private fun drawTopBar(canvas: Canvas, w: Float, h: Float) {
        val barH = szMed * 1.8f
        // Semi-transparent bar background
        tempRect.set(0f, 0f, w, barH)
        canvas.drawRect(tempRect, pBg)
        // Thin green line under bar
        canvas.drawLine(0f, barH, w, barH, pGreen)

        // ── Left: REC indicator + timer
        val cy = barH / 2
        var cx = pad + dotR
        if (state.isRecording) {
            if (state.blinkVisible) canvas.drawCircle(cx, cy, dotR, pRecDot)
            cx += dotR * 2f
            canvas.drawText("REC", cx, cy + szMed * 0.35f, pTxtRed)
            cx += pTxtRed.measureText("REC") + pad
            canvas.drawText(formatElapsed(state.elapsedSeconds), cx, cy + szMed * 0.35f, pTxtMed)
        } else {
            pTxtAmber.textSize = szMed
            canvas.drawText("STANDBY", cx, cy + szMed * 0.35f, pTxtAmber)
        }

        // ── Right: GPS constellation status
        val gps = state.gps
        val satCount = gps?.satellites ?: 0
        val hdop = gps?.hdop ?: 99.9f
        val hasSbas = gps?.sbasActive == true
        val fixLabel = if (state.hasGpsFix) "3D FIX" else "NO FIX"
        val fixPaint = if (state.hasGpsFix) pTxtMed else pTxtAmber
        // Build GPS string: SAT:14 | HDOP:0.9 | 3D FIX | SBAS
        val sbasStr = if (hasSbas) " SBAS" else ""
        val gpsStr = "SAT:%-2d  HDOP:%-4.1f  %s%s".format(satCount, hdop, fixLabel, sbasStr)

        val gpsW = fixPaint.measureText(gpsStr)
        val gpsX = w - gpsW - pad

        // GPS chip background
        val chipPad = pad * 0.4f
        tempRect.set(gpsX - chipPad, pad * 0.3f, w - pad * 0.5f + chipPad, barH - pad * 0.3f)
        canvas.drawRoundRect(tempRect, 4f, 4f, pBgChip)
        canvas.drawRoundRect(tempRect, 4f, 4f, if (state.hasGpsFix) pGreen else mkPaint(AMBER, Paint.Style.STROKE, pGreen.strokeWidth))
        canvas.drawText(gpsStr, gpsX, cy + szSmall * 0.35f, fixPaint)

        // Sat constellation breakdown (tiny text under the main bar)
        if (gps != null && state.hasGpsFix) {
            val constStr = "GP:${gps.satsGps} GL:${gps.satsGlo} GA:${gps.satsGal} BD:${gps.satsBds}"
            val constW = pTxtTiny.measureText(constStr)
            canvas.drawText(constStr, w - constW - pad, barH + szTiny * 1.2f, pTxtTiny)
        }
    }

    // ── SPEED BLOCK: large speed + unit (left side, vertically centered) ──────

    private fun drawSpeedBlock(canvas: Canvas, w: Float, h: Float) {
        val speed = state.gps?.speedKmh?.roundToInt() ?: 0
        val speedStr = "%3d".format(speed)
        val x = pad * 1.5f
        val y = h * 0.50f

        // Speed value
        canvas.drawText(speedStr, x, y, pTxtLarge)
        // Unit label
        val speedW = pTxtLarge.measureText(speedStr)
        pTxtTiny.color = GREEN_DIM
        canvas.drawText("km/h", x + speedW + pad * 0.3f, y, pTxtTiny)
        pTxtTiny.color = GREEN_DIM

        // Heading arrow + degrees
        val heading = state.gps?.headingDeg ?: 0f
        val headingStr = "%03.0f°".format(heading)
        val compassStr = headingToCompass(heading)
        canvas.drawText("HDG $headingStr $compassStr", x, y + szMed * 1.4f, pTxtSmall)
    }

    // ── G-METER: circular acceleration crosshair (center of screen) ───────────

    private fun drawGMeter(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.42f
        val cy = h * 0.48f
        val r  = h * 0.22f

        // Background circle
        tempRect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawOval(tempRect, pBg)
        canvas.drawOval(tempRect, pGreen)

        // Grid: crosshair lines
        canvas.drawLine(cx - r, cy, cx + r, cy, pGreenFaint)
        canvas.drawLine(cx, cy - r, cx, cy + r, pGreenFaint)

        // Grid: 1g and 2g rings
        val r1g = r / G_METER_MAX_G
        val r2g = r1g * 2f
        tempRect.set(cx - r1g, cy - r1g, cx + r1g, cy + r1g)
        canvas.drawOval(tempRect, pDashed)
        tempRect.set(cx - r2g, cy - r2g, cx + r2g, cy + r2g)
        canvas.drawOval(tempRect, pDashed)

        // Labels on axes
        pTxtTiny.color = GREEN_DIM
        canvas.drawText("FWD", cx - pTxtTiny.measureText("FWD") / 2, cy - r + szTiny, pTxtTiny)
        canvas.drawText("BRK", cx - pTxtTiny.measureText("BRK") / 2, cy + r - szTiny * 0.2f, pTxtTiny)
        canvas.drawText("L", cx - r + pad, cy + szTiny * 0.3f, pTxtTiny)
        canvas.drawText("R", cx + r - pad - pTxtTiny.measureText("R"), cy + szTiny * 0.3f, pTxtTiny)
        // Ring labels
        canvas.drawText("1g", cx + r1g + 2, cy - 2, pTxtTiny)
        canvas.drawText("2g", cx + r2g + 2, cy - 2, pTxtTiny)
        pTxtTiny.color = GREEN_DIM

        // Acceleration trail (older samples faded)
        for (i in 0 until accelHistCount) {
            val idx = (accelHistHead - accelHistCount + i + ACCEL_HISTORY_SIZE) % ACCEL_HISTORY_SIZE
            val ax = accelHistX[idx].coerceIn(-G_METER_MAX_G, G_METER_MAX_G)
            val ay = accelHistY[idx].coerceIn(-G_METER_MAX_G, G_METER_MAX_G)
            // Map: +ax = forward = up, +ay = right = right
            val dx = cx + (ay / G_METER_MAX_G) * r
            val dy = cy - (ax / G_METER_MAX_G) * r
            val alpha = (40 + 180 * i / max(accelHistCount, 1)).coerceIn(0, 255)
            pAccelTrail.color = (alpha shl 24) or (GREEN and 0x00FFFFFF)
            canvas.drawCircle(dx, dy, 2f, pAccelTrail)
        }

        // Current acceleration dot (bright)
        val curAx = state.accelAxG.coerceIn(-G_METER_MAX_G, G_METER_MAX_G)
        val curAy = state.accelAyG.coerceIn(-G_METER_MAX_G, G_METER_MAX_G)
        val dotX = cx + (curAy / G_METER_MAX_G) * r
        val dotY = cy - (curAx / G_METER_MAX_G) * r
        val totalG = state.accelTotalG
        val dotColor = when {
            totalG > 2.0f -> RED
            totalG > 1.0f -> AMBER
            else -> GREEN
        }
        pAccelDot.color = dotColor
        canvas.drawCircle(dotX, dotY, h * 0.012f, pAccelDot)

        // G-force readout text below circle
        val gStr = "%.2fg".format(totalG)
        val gPaint = when {
            totalG > 2.0f -> pTxtRed
            totalG > 1.0f -> pTxtAmber
            else -> pTxtMed
        }
        gPaint.textSize = szSmall
        canvas.drawText(gStr, cx - gPaint.measureText(gStr) / 2, cy + r + szSmall * 1.4f, gPaint)

        // Direction label
        val dirStr = accelDirection(state.accelAxG, state.accelAyG)
        pTxtTiny.color = GREEN_DIM
        canvas.drawText(dirStr, cx - pTxtTiny.measureText(dirStr) / 2, cy + r + szSmall * 1.4f + szTiny * 1.3f, pTxtTiny)
    }

    // ── CLINOMETER: pitch/roll indicator (right-center) ───────────────────────

    private fun drawClinometer(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.78f
        val cy = h * 0.36f
        val size = h * 0.15f

        // Box outline
        tempRect.set(cx - size, cy - size, cx + size, cy + size)
        canvas.drawRect(tempRect, pBg)
        canvas.drawRect(tempRect, pGreen)

        // Crosshair
        canvas.drawLine(cx - size, cy, cx + size, cy, pGreenFaint)
        canvas.drawLine(cx, cy - size, cx, cy + size, pGreenFaint)

        // Pitch/roll dot (pitch = vertical, roll = horizontal)
        // Clamp to ±30° for display
        val maxDeg = 30f
        val roll  = state.rollDeg.coerceIn(-maxDeg, maxDeg)
        val pitch = state.pitchDeg.coerceIn(-maxDeg, maxDeg)
        val dx = cx + (roll / maxDeg) * size * 0.9f
        val dy = cy - (pitch / maxDeg) * size * 0.9f

        // Horizon line (rotated by roll)
        val horizLen = size * 0.7f
        val rollRad = Math.toRadians(roll.toDouble()).toFloat()
        val hlx1 = dx - horizLen * kotlin.math.cos(rollRad)
        val hly1 = dy + horizLen * kotlin.math.sin(rollRad)
        val hlx2 = dx + horizLen * kotlin.math.cos(rollRad)
        val hly2 = dy - horizLen * kotlin.math.sin(rollRad)
        canvas.drawLine(hlx1, hly1, hlx2, hly2, pGreen)

        // Center indicator dot
        canvas.drawCircle(dx, dy, h * 0.008f, pGreenFill)

        // Labels
        canvas.drawText("CLINOMETER", cx - pTxtTiny.measureText("CLINOMETER") / 2, cy - size - pad * 0.3f, pTxtTiny)
        val pitchStr = "P:%+05.1f°".format(state.pitchDeg)
        val rollStr  = "R:%+05.1f°".format(state.rollDeg)
        canvas.drawText(pitchStr, cx - size, cy + size + szTiny * 1.2f, pTxtTiny)
        canvas.drawText(rollStr, cx + size - pTxtTiny.measureText(rollStr), cy + size + szTiny * 1.2f, pTxtTiny)

        // Degree tick marks on edges
        for (deg in listOf(-20f, -10f, 10f, 20f)) {
            val frac = deg / maxDeg
            // Horizontal ticks (pitch)
            val ty = cy - frac * size * 0.9f
            canvas.drawLine(cx - size, ty, cx - size + pad, ty, pGreenFaint)
            canvas.drawLine(cx + size - pad, ty, cx + size, ty, pGreenFaint)
        }
    }

    // ── POSITION BLOCK: lat/lon (nautical), altitude, UTC (right side) ────────

    private fun drawPositionBlock(canvas: Canvas, w: Float, h: Float) {
        val gps = state.gps
        val x = w * 0.66f
        val y = h * 0.68f
        val lineH = szSmall * 1.4f

        // Format coordinates in nautical: DD°MM.MM' N/S, DDD°MM.MM' E/W
        val latStr = if (gps != null) formatLatNautical(gps.lat) else "---°--.--' -"
        val lonStr = if (gps != null) formatLonNautical(gps.lon) else "----°--.--' -"

        // Altitude
        val altStr = if (gps != null) "ALT: %.0fm".format(gps.altM) else "ALT: ---m"

        // UTC time
        val utcStr = utcFmt.format(Date()) + " UTC"

        // Background panel
        val maxW = maxOf(
            pTxtSmall.measureText(latStr),
            pTxtSmall.measureText(lonStr),
            pTxtSmall.measureText(altStr),
            pTxtSmall.measureText(utcStr)
        )
        tempRect.set(x - pad, y - szSmall - pad * 0.5f, x + maxW + pad, y + lineH * 3.2f + pad * 0.5f)
        canvas.drawRoundRect(tempRect, 3f, 3f, pBg)
        canvas.drawRoundRect(tempRect, 3f, 3f, pGreenFaint)

        canvas.drawText(latStr, x, y, pTxtSmall)
        canvas.drawText(lonStr, x, y + lineH, pTxtSmall)
        canvas.drawText(altStr, x, y + lineH * 2, pTxtSmall)
        pTxtTiny.color = GREEN_DIM
        canvas.drawText(utcStr, x, y + lineH * 3, pTxtTiny)
    }

    // ── BOTTOM BAR: road quality + event + protected ──────────────────────────

    private fun drawBottomBar(canvas: Canvas, w: Float, h: Float) {
        val barTop = h - szMed * 1.8f
        // Background
        tempRect.set(0f, barTop, w, h)
        canvas.drawRect(tempRect, pBg)
        canvas.drawLine(0f, barTop, w, barTop, pGreen)

        val cy = barTop + (h - barTop) / 2
        var x = pad

        // Road quality indicator
        val roadLabel = "ROAD:"
        canvas.drawText(roadLabel, x, cy + szSmall * 0.35f, pTxtSmall)
        x += pTxtSmall.measureText(roadLabel) + pad * 0.3f

        val roadPaint = when (state.roadState) {
            "SMOOTH"     -> pTxtMed
            "ROUGH"      -> pTxtAmber
            "VERY_ROUGH" -> pTxtRed
            else         -> pTxtTiny
        }
        roadPaint.textSize = szSmall
        canvas.drawText(state.roadState, x, cy + szSmall * 0.35f, roadPaint)
        x += roadPaint.measureText(state.roadState) + pad * 2f

        // RMS value
        pTxtTiny.color = GREEN_DIM
        val rmsStr = "%.1f m/s²".format(state.roadRmsMs2)
        canvas.drawText(rmsStr, x, cy + szSmall * 0.35f, pTxtTiny)
        x += pTxtTiny.measureText(rmsStr) + pad * 3f
        // Event status
        val evtAge = System.currentTimeMillis() - state.lastEventTimeMs
        val hasRecentEvent = state.lastEventDirection != null && evtAge < 10_000
        val evtLabel = "EVT:"
        canvas.drawText(evtLabel, x, cy + szSmall * 0.35f, pTxtSmall)
        x += pTxtSmall.measureText(evtLabel) + pad * 0.3f

        if (hasRecentEvent) {
            val evtStr = state.lastEventDirection ?: "---"
            val evtGStr = " %.1fg".format(state.lastEventPeakG)
            pTxtRed.textSize = szSmall
            canvas.drawText(evtStr + evtGStr, x, cy + szSmall * 0.35f, pTxtRed)
            x += pTxtRed.measureText(evtStr + evtGStr) + pad * 2f
        } else {
            canvas.drawText("NONE", x, cy + szSmall * 0.35f, pTxtMed)
            x += pTxtMed.measureText("NONE") + pad * 2f
        }

        // Protected count (right-aligned)
        val protStr = "PROT: ${state.protectedCount}/25"
        val protPaint = if (state.protectedCount > 0) pTxtAmber else pTxtSmall
        protPaint.textSize = szSmall
        val protW = protPaint.measureText(protStr)
        canvas.drawText(protStr, w - protW - pad, cy + szSmall * 0.35f, protPaint)
    }

    // ── SPEED GRAPH: horizontal sparkline below speed block ───────────────────

    private fun drawSpeedGraph(canvas: Canvas, w: Float, h: Float) {
        if (speedHistCount < 2) return

        val graphL = pad * 1.5f
        val graphR = w * 0.30f
        val graphT = h * 0.58f
        val graphB = h * 0.72f
        val graphW = graphR - graphL
        val graphH = graphB - graphT

        // Background
        tempRect.set(graphL, graphT, graphR, graphB)
        canvas.drawRect(tempRect, pBg)
        canvas.drawRect(tempRect, pGreenFaint)

        // Find max speed for scaling (min 30 km/h to avoid flat line)
        var maxSpd = 30f
        for (i in 0 until speedHistCount) {
            val idx = (speedHistHead - speedHistCount + i + SPEED_HISTORY_SIZE) % SPEED_HISTORY_SIZE
            if (speedHistory[idx] > maxSpd) maxSpd = speedHistory[idx]
        }
        maxSpd *= 1.1f // 10% headroom

        // Draw speed line
        tempPath.reset()
        var first = true
        for (i in 0 until speedHistCount) {
            val idx = (speedHistHead - speedHistCount + i + SPEED_HISTORY_SIZE) % SPEED_HISTORY_SIZE
            val px = graphL + (i.toFloat() / (SPEED_HISTORY_SIZE - 1)) * graphW
            val py = graphB - (speedHistory[idx] / maxSpd) * graphH
            if (first) { tempPath.moveTo(px, py); first = false }
            else tempPath.lineTo(px, py)
        }
        canvas.drawPath(tempPath, pSpeedLine)
        // Scale labels
        pTxtTiny.color = GREEN_DIM
        canvas.drawText("%.0f".format(maxSpd), graphR + 2, graphT + szTiny, pTxtTiny)
        canvas.drawText("0", graphR + 2, graphB, pTxtTiny)
        canvas.drawText("SPD", graphL, graphT - pad * 0.3f, pTxtTiny)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ── HELPERS ───────────────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    private fun formatElapsed(secs: Long): String {
        val h = secs / 3600; val m = (secs % 3600) / 60; val s = secs % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    /** DD°MM.MM' N/S */
    private fun formatLatNautical(lat: Double): String {
        val ns = if (lat >= 0) "N" else "S"
        val a = abs(lat)
        val deg = a.toInt()
        val min = (a - deg) * 60.0
        return "%02d°%05.2f' %s".format(deg, min, ns)
    }

    /** DDD°MM.MM' E/W */
    private fun formatLonNautical(lon: Double): String {
        val ew = if (lon >= 0) "E" else "W"
        val a = abs(lon)
        val deg = a.toInt()
        val min = (a - deg) * 60.0
        return "%03d°%05.2f' %s".format(deg, min, ew)
    }

    private fun headingToCompass(deg: Float): String = when {
        deg < 22.5f  || deg >= 337.5f -> "N"
        deg < 67.5f  -> "NE"
        deg < 112.5f -> "E"
        deg < 157.5f -> "SE"
        deg < 202.5f -> "S"
        deg < 247.5f -> "SW"
        deg < 292.5f -> "W"
        else         -> "NW"
    }

    private fun accelDirection(ax: Float, ay: Float): String {
        if (abs(ax) < 0.15f && abs(ay) < 0.15f) return "NEUTRAL"
        return if (abs(ax) > abs(ay)) {
            if (ax > 0) "ACCEL" else "BRAKE"
        } else {
            if (ay > 0) "RIGHT" else "LEFT"
        }
    }

    // ── Paint factories ───────────────────────────────────────────────────────

    private fun mkPaint(color: Int, style: Paint.Style = Paint.Style.FILL, width: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.style = style
            if (width > 0) strokeWidth = width
        }

    private fun mkTextPaint(color: Int, face: Typeface, size: Float) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.typeface = face
            this.textSize = size
        }
}
