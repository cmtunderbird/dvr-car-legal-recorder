package com.dashcam.dvr.service

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.dashcam.dvr.DVRApplication.Companion.CHANNEL_RECORDING
import com.dashcam.dvr.R
import com.dashcam.dvr.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * RecordingService — Foreground service for DVR session management.
 *
 * HYPEROS MINIMIZE FIX — FINAL:
 * FOREGROUND_SERVICE_TYPE_CAMERA must NOT be declared in startForeground() here.
 *
 * Why: The camera is owned by DVRCameraManager, bound to the Activity's LifecycleOwner
 * via CameraX ProcessCameraProvider. The service never opens a camera session directly.
 * Declaring CAMERA type causes HyperOS to display a full-screen privacy security overlay
 * ("App is accessing camera in background") that:
 *   - Steals window focus
 *   - Sends the foreground Activity to background (app minimizes)
 *   - Kills the camera preview (Activity is no longer in foreground)
 *
 * MICROPHONE + LOCATION are the correct types for this service (audio recording + GPS).
 * These display a small indicator bar only — no overlay, no minimize.
 *
 * ARCHITECTURE: Service is started via startForegroundService() in Activity.onStart(),
 * then bound via bindService(). startForeground() is called in onCreate() so the
 * service is already foreground before the user ever taps Record.
 * Button presses call startRecording()/stopRecording() directly via binder —
 * no system transitions, no banners, no minimize.
 */
class RecordingService : LifecycleService() {

    inner class RecordingBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }
    private val binder = RecordingBinder()

    sealed class ServiceState {
        object Idle      : ServiceState()
        object Starting  : ServiceState()
        object Recording : ServiceState()
        object Stopping  : ServiceState()
        data class Error(val message: String) : ServiceState()
    }

    private val _state = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val state: StateFlow<ServiceState> = _state

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds

    private var wakeLock: PowerManager.WakeLock? = null
    private var timerJob: Job? = null
    private val NOTIFICATION_ID = 1001

    companion object {
        const val ACTION_START_RECORDING = "com.dashcam.dvr.START_RECORDING"
        const val ACTION_STOP_RECORDING  = "com.dashcam.dvr.STOP_RECORDING"
        const val ACTION_TRIGGER_EVENT   = "com.dashcam.dvr.TRIGGER_EVENT"
        private const val TAG            = "RecordingService"
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        // Promote to foreground immediately on creation.
        // NO CAMERA type — camera belongs to Activity/CameraX, not this service.
        // MICROPHONE + LOCATION show a small status bar indicator only (no overlay).
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("DVR Standby"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        Log.i(TAG, "Service created — foreground (MICROPHONE|LOCATION only, no CAMERA)")
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING  -> stopRecording()
            ACTION_TRIGGER_EVENT   -> triggerEvent()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        timerJob?.cancel()
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    // ── Public API — called via binder from MainActivity ──────────────────

    fun startRecording() {
        if (_state.value is ServiceState.Recording || _state.value is ServiceState.Starting) return
        Log.i(TAG, "startRecording() called via binder")
        _state.value = ServiceState.Starting
        acquireWakeLock()
        // Service is already foreground since onCreate() — no startForeground() call needed.
        // Just update notification text. No system events, no banner, no minimize.
        updateNotification("REC | Starting...")
        lifecycleScope.launch {
            _elapsedSeconds.value = 0L
            _state.value = ServiceState.Recording
            updateNotification("REC | 00:00:00")
            startTimer()
            Log.i(TAG, "Recording started")
        }
    }

    fun stopRecording() {
        if (_state.value !is ServiceState.Recording) return
        Log.i(TAG, "stopRecording() called")
        _state.value = ServiceState.Stopping
        lifecycleScope.launch {
            timerJob?.cancel()
            _elapsedSeconds.value = 0L
            releaseWakeLock()
            _state.value = ServiceState.Idle
            updateNotification("DVR Standby")
            Log.i(TAG, "Recording stopped — back to standby")
        }
    }

    fun triggerEvent() {
        if (_state.value !is ServiceState.Recording) return
        Log.i(TAG, "Manual event triggered")
        lifecycleScope.launch { updateNotification("REC | Event saved") }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private fun startTimer() {
        timerJob = lifecycleScope.launch {
            while (true) {
                delay(1_000)
                _elapsedSeconds.value += 1
                val s = _elapsedSeconds.value
                updateNotification("REC | %02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60))
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DVR:RecordingWakeLock")
            .also { it.setReferenceCounted(false); it.acquire(12 * 60 * 60 * 1000L) }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(statusText: String) =
        NotificationCompat.Builder(this, CHANNEL_RECORDING)
            .setContentTitle("DVR Evidence")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_rec)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(openMainActivityIntent())
            .addAction(R.drawable.ic_stop,  "Stop",       stopPendingIntent())
            .addAction(R.drawable.ic_event, "Save Event", triggerEventPendingIntent())
            .build()

    private fun updateNotification(statusText: String) =
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(statusText))

    private fun openMainActivityIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun stopPendingIntent() = PendingIntent.getService(
        this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP_RECORDING),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun triggerEventPendingIntent() = PendingIntent.getService(
        this, 2, Intent(this, RecordingService::class.java).setAction(ACTION_TRIGGER_EVENT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
