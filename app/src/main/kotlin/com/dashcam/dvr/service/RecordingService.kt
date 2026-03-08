package com.dashcam.dvr.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.dashcam.dvr.DVRApplication.Companion.CHANNEL_ALERT
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

    /** Elapsed recording seconds — observed by MainActivity to drive tvTimer */
    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds

    private var wakeLock: PowerManager.WakeLock? = null
    private var statusUpdateJob: Job? = null
    private val NOTIFICATION_ID = 1001

    companion object {
        const val ACTION_START_RECORDING = "com.dashcam.dvr.START_RECORDING"
        const val ACTION_STOP_RECORDING  = "com.dashcam.dvr.STOP_RECORDING"
        const val ACTION_TRIGGER_EVENT   = "com.dashcam.dvr.TRIGGER_EVENT"
        const val ACTION_MUTE_AUDIO      = "com.dashcam.dvr.MUTE_AUDIO"
        private const val TAG            = "RecordingService"

        fun startRecording(context: Context) {
            context.startForegroundService(
                Intent(context, RecordingService::class.java).setAction(ACTION_START_RECORDING))
        }
        fun stopRecording(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP_RECORDING))
        }
        fun triggerManualEvent(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(ACTION_TRIGGER_EVENT))
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        // WakeLock NOT acquired here — only when recording starts.
        // Acquiring at bind time triggered HyperOS privacy indicators that minimized the app.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_RECORDING -> handleStartRecording()
            ACTION_STOP_RECORDING  -> handleStopRecording()
            ACTION_TRIGGER_EVENT   -> handleManualEventTrigger()
            else -> { if (_state.value == ServiceState.Idle) handleStartRecording() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder { super.onBind(intent); return binder }

    override fun onDestroy() {
        releaseWakeLock()
        statusUpdateJob?.cancel()
        super.onDestroy()
    }

    private fun handleStartRecording() {
        if (_state.value is ServiceState.Recording) return
        _state.value = ServiceState.Starting
        acquireWakeLock()                                      // FIX: here not in onCreate
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        lifecycleScope.launch {
            try {
                _elapsedSeconds.value = 0L
                _state.value = ServiceState.Recording
                updateNotification("● REC  |  00:00:00")
                startStatusUpdates()
                Log.i(TAG, "Recording started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start: ${e.message}", e)
                _state.value = ServiceState.Error(e.message ?: "Unknown error")
                showErrorNotification("Failed to start: ${e.message}")
            }
        }
    }

    private fun handleStopRecording() {
        if (_state.value !is ServiceState.Recording) return
        _state.value = ServiceState.Stopping
        lifecycleScope.launch {
            try {
                statusUpdateJob?.cancel()
                _elapsedSeconds.value = 0L
                _state.value = ServiceState.Idle
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Log.i(TAG, "Recording stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping: ${e.message}", e)
            }
        }
    }

    private fun handleManualEventTrigger() {
        lifecycleScope.launch { updateNotification("● REC  |  ⚡ Event saved") }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DVR:RecordingWakeLock").also {
                it.setReferenceCounted(false)
                it.acquire(12 * 60 * 60 * 1000L)
            }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Log.d(TAG, "WakeLock released")
    }

    /**
     * Increments _elapsedSeconds every second.
     * MainActivity observes elapsedSeconds to update tvTimer.
     * Notification is also updated here for status bar display.
     */
    private fun startStatusUpdates() {
        statusUpdateJob = lifecycleScope.launch {
            while (true) {
                delay(1_000)
                _elapsedSeconds.value += 1
                val secs = _elapsedSeconds.value
                val h = secs / 3600
                val m = (secs % 3600) / 60
                val s = secs % 60
                updateNotification("● REC  |  %02d:%02d:%02d".format(h, m, s))
            }
        }
    }

    private fun buildNotification(statusText: String) =
        NotificationCompat.Builder(this, CHANNEL_RECORDING)
            .setContentTitle("DVR Active")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_rec)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(openMainActivityIntent())
            .addAction(R.drawable.ic_stop,  "Stop",       stopPendingIntent())
            .addAction(R.drawable.ic_event, "Save Event", triggerEventPendingIntent())
            .build()

    private fun updateNotification(statusText: String) {
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun showErrorNotification(message: String) {
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .notify(NOTIFICATION_ID + 1,
                NotificationCompat.Builder(this, CHANNEL_ALERT)
                    .setContentTitle("DVR Error").setContentText(message)
                    .setSmallIcon(R.drawable.ic_alert)
                    .setPriority(NotificationCompat.PRIORITY_HIGH).build())
    }

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
