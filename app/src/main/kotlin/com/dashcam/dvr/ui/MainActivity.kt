package com.dashcam.dvr.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dashcam.dvr.R
import com.dashcam.dvr.camera.CameraValidator
import com.dashcam.dvr.camera.DVRCameraManager
import com.dashcam.dvr.service.RecordingService
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * MainActivity
 *
 * IMPORTANT — service interaction via binder only:
 * Button taps call recordingService?.startRecording() / stopRecording() directly.
 * We NEVER call startForegroundService() from here — that triggers HyperOS camera
 * security banners which steal focus and minimize the app.
 *
 * IMPORTANT — unbind policy:
 * onStop() does NOT unbind while recording is active. Unbinding kills the
 * ServiceConnection, sets recordingService = null, and cancels all Flow collectors
 * — making the timer stop and the button state freeze.
 * We only unbind when the service is idle (not recording).
 */
class MainActivity : AppCompatActivity() {

    companion object { private const val TAG = "MainActivity" }

    private lateinit var previewRear   : PreviewView
    private lateinit var previewFront  : PreviewView
    private lateinit var tvTimer       : TextView
    private lateinit var tvGpsStatus   : TextView
    private lateinit var tvCameraStatus: TextView
    private lateinit var btnRecord     : MaterialButton
    private lateinit var btnEvent      : MaterialButton

    private lateinit var cameraManager  : DVRCameraManager
    private lateinit var cameraValidator: CameraValidator

    private var recordingService: RecordingService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            recordingService = (binder as RecordingService.RecordingBinder).getService()
            serviceBound = true
            observeServiceState()
            Log.d(TAG, "Bound to RecordingService ✅")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            recordingService = null
            serviceBound = false
            Log.w(TAG, "Service disconnected unexpectedly")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) onPermissionsReady()
        else Toast.makeText(this, "Camera and location permissions are required", Toast.LENGTH_LONG).show()
    }


    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        bindViews()
        setupClickListeners()
        cameraManager   = DVRCameraManager(this)
        cameraValidator = CameraValidator(this)
        checkAndRequestPermissions()
    }

    override fun onStart() {
        super.onStart()
        // Always (re)bind — safe to call even if already bound; guards against the
        // case where user returns to app after service was kept alive during recording
        if (!serviceBound) {
            bindService(Intent(this, RecordingService::class.java), serviceConnection, BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        // KEY FIX: Only unbind when the service is idle.
        // While recording, keep the binding alive so Flow collectors stay active
        // and the timer keeps ticking when the user returns to the app.
        val isRecording = recordingService?.state?.value is RecordingService.ServiceState.Recording
        if (serviceBound && !isRecording) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.stopAll()
        // Final cleanup — unbind if still bound (recording was stopped before destroy)
        if (serviceBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            serviceBound = false
        }
    }

    // ── Views & click listeners ────────────────────────────────────────────

    private fun bindViews() {
        previewRear     = findViewById(R.id.previewRear)
        previewFront    = findViewById(R.id.previewFront)
        tvTimer         = findViewById(R.id.tvTimer)
        tvGpsStatus     = findViewById(R.id.tvGpsStatus)
        tvCameraStatus  = findViewById(R.id.tvCameraStatus)
        btnRecord       = findViewById(R.id.btnRecord)
        btnEvent        = findViewById(R.id.btnEvent)
    }

    private fun setupClickListeners() {
        btnRecord.setOnClickListener {
            val svc = recordingService ?: run {
                Toast.makeText(this, "Service not ready — please wait", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Call directly on service instance via binder — never startForegroundService()
            if (svc.state.value is RecordingService.ServiceState.Recording)
                svc.stopRecording()
            else
                svc.startRecording()
        }
        btnEvent.setOnClickListener {
            recordingService?.triggerEvent()
            Toast.makeText(this, "⚡ Event saved!", Toast.LENGTH_SHORT).show()
        }
    }


    // ── Permissions ────────────────────────────────────────────────────────

    private fun checkAndRequestPermissions() {
        val required = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) onPermissionsReady()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun onPermissionsReady() {
        Log.i(TAG, "All permissions granted")
        runCameraValidation()
        startDualPreview()
    }

    // ── Camera ─────────────────────────────────────────────────────────────

    private fun runCameraValidation() {
        lifecycleScope.launch {
            val result = cameraValidator.validate()
            if (!result.isViable) {
                tvCameraStatus.text = "⚠️ Camera issue"
                tvCameraStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.dvr_red))
            } else {
                val dual = if (result.hasDualCameras) "Dual ✅" else "Single ⚠️"
                tvCameraStatus.text = "$dual | ${if (result.rearSupportsFullLevel) "FULL" else "LIMITED"}"
            }
        }
    }

    private fun startDualPreview() {
        lifecycleScope.launch {
            try {
                tvCameraStatus.text = "Starting cameras…"
                val (rear, front) = cameraManager.enumerateCameras()
                cameraManager.startPreview(
                    lifecycleOwner   = this@MainActivity,
                    rearPreviewView  = previewRear,
                    frontPreviewView = previewFront
                )
                tvCameraStatus.text = "📷 R:${rear?.logicalId ?: "N/A"}  F:${front?.logicalId ?: "N/A"}"
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed: ${e.message}", e)
                tvCameraStatus.text = "❌ ${e.message}"
            }
        }
        lifecycleScope.launch {
            cameraManager.state.collectLatest { state ->
                when (state) {
                    is DVRCameraManager.CameraState.Previewing ->
                        tvCameraStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.dvr_teal))
                    is DVRCameraManager.CameraState.Error -> {
                        tvCameraStatus.text = "❌ ${state.message}"
                        tvCameraStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.dvr_red))
                    }
                    else -> {}
                }
            }
        }
    }

    // ── Service state observers ────────────────────────────────────────────

    private fun observeServiceState() {
        lifecycleScope.launch {
            recordingService?.state?.collectLatest { state ->
                when (state) {
                    is RecordingService.ServiceState.Recording -> {
                        btnRecord.text = getString(R.string.stop_recording)
                        btnRecord.backgroundTintList =
                            ContextCompat.getColorStateList(this@MainActivity, R.color.dvr_red)
                        btnEvent.isEnabled = true
                    }
                    is RecordingService.ServiceState.Idle -> {
                        btnRecord.text = getString(R.string.start_recording)
                        btnRecord.backgroundTintList =
                            ContextCompat.getColorStateList(this@MainActivity, R.color.dvr_teal)
                        btnEvent.isEnabled = false
                        tvTimer.text = "00:00:00"
                    }
                    is RecordingService.ServiceState.Error ->
                        Toast.makeText(this@MainActivity,
                            "DVR Error: ${state.message}", Toast.LENGTH_LONG).show()
                    else -> {}
                }
            }
        }
        lifecycleScope.launch {
            recordingService?.elapsedSeconds?.collectLatest { secs ->
                tvTimer.text = "%02d:%02d:%02d".format(secs / 3600, (secs % 3600) / 60, secs % 60)
            }
        }
    }
}
