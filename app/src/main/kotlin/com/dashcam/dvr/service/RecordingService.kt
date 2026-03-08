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
        startService(Intent(applicationContext, RecordingService::class.java))
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("DVR Ready"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved — resetting to Idle")
        timerJob?.cancel(); timerJob = null
        releaseWakeLock()
        _state.value = ServiceState.Idle
        _elapsedSeconds.value = 0L
        updateNotification("DVR Ready")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        timerJob?.cancel()
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Force-reset to Idle.
     * Called by MainActivity.onServiceConnected() when it finds the service
     * already in Recording state with no camera session — this happens on HyperOS
     * because the service process survives after the app is killed/restarted,
     * retaining the old Recording state but with no live camera attached.
     */
    fun resetToIdle() {
        Log.w(TAG, "resetToIdle() — clearing orphaned state (was: ${_state.value})")
        timerJob?.cancel(); timerJob = null
        releaseWakeLock()
        _state.value = ServiceState.Idle
        _elapsedSeconds.value = 0L
        updateNotification("DVR Ready")
    }

    fun startRecording() {
        if (_state.value is ServiceState.Recording || _state.value is ServiceState.Starting) return
        Log.i(TAG, "startRecording()")
        _state.value = ServiceState.Starting
        acquireWakeLock()
        updateNotification("REC | Starting...")
        lifecycleScope.launch {
            _elapsedSeconds.value = 0L
            _state.value = ServiceState.Recording
            updateNotification("REC | 00:00:00")
            startTimer()
        }
    }

    fun stopRecording() {
        if (_state.value !is ServiceState.Recording) return
        Log.i(TAG, "stopRecording()")
        _state.value = ServiceState.Stopping
        lifecycleScope.launch {
            timerJob?.cancel(); timerJob = null
            _elapsedSeconds.value = 0L
            releaseWakeLock()
            _state.value = ServiceState.Idle
            updateNotification("DVR Ready")
        }
    }

    fun triggerEvent() {
        if (_state.value !is ServiceState.Recording) return
        lifecycleScope.launch { updateNotification("REC | Event saved!") }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun startTimer() {
        timerJob = lifecycleScope.launch {
            while (true) {
                delay(1_000)
                _elapsedSeconds.value += 1
                val s = _elapsedSeconds.value
                updateNotification("REC | %02d:%02d:%02d"
                    .format(s / 3600, (s % 3600) / 60, s % 60))
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DVR:RecordingWakeLock")
            .also { it.setReferenceCounted(false); it.acquire(12 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_RECORDING)
            .setContentTitle("DVR Evidence")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_rec)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(R.drawable.ic_stop, "Stop", PendingIntent.getService(
                this, 1,
                Intent(this, RecordingService::class.java).setAction(ACTION_STOP_RECORDING),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(R.drawable.ic_event, "Event", PendingIntent.getService(
                this, 2,
                Intent(this, RecordingService::class.java).setAction(ACTION_TRIGGER_EVENT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()

    private fun updateNotification(text: String) =
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
}
