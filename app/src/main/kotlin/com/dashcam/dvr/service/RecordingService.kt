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
 * RecordingService
 *
 * KEY FIX — Android 14 (targetSdk 34) requires ServiceCompat.startForeground() with
 * explicit service type flags.  The old 2-arg startForeground() throws
 * MissingForegroundServiceTypeException silently: service never reaches foreground,
 * HyperOS kills it immediately → app minimizes, timer never starts.
 *
 * ARCHITECTURE: Binder-only control.
 * Activity binds with BIND_AUTO_CREATE and calls startRecording()/stopRecording()
 * directly on the binder instance.  The service promotes itself to foreground
 * internally — the Activity NEVER calls startForegroundService().
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

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
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

        // *** THE CRITICAL FIX ***
        // Android 14 (targetSdk=34): startForeground() without a service type throws
        // MissingForegroundServiceTypeException — service silently fails to go foreground,
        // HyperOS kills it → app minimizes, timer never starts.
        // ServiceCompat.startForeground() handles the type flags and API level automatically.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Starting..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )

        lifecycleScope.launch {
            _elapsedSeconds.value = 0L
            _state.value = ServiceState.Recording
            updateNotification("REC  |  00:00:00")
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
            _state.value = ServiceState.Idle
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Log.i(TAG, "Recording stopped")
        }
    }

    fun triggerEvent() {
        if (_state.value !is ServiceState.Recording) return
        Log.i(TAG, "Manual event triggered")
        lifecycleScope.launch { updateNotification("REC  |  Event saved") }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun startTimer() {
        timerJob = lifecycleScope.launch {
            while (true) {
                delay(1_000)
                _elapsedSeconds.value += 1
                val s = _elapsedSeconds.value
                updateNotification("REC  |  %02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60))
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
