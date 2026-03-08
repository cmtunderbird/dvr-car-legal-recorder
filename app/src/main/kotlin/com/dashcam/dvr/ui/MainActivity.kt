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
import android.view.TextureView
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
import com.dashcam.dvr.camera.FrontCameraRecorder
import com.dashcam.dvr.service.RecordingService
import com.dashcam.dvr.util.AppConstants
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object { private const val TAG = "MainActivity" }

    private lateinit var previewRear       : PreviewView
    private lateinit var previewFront      : TextureView   // Camera2 needs TextureView
    private lateinit var tvTimer           : TextView
    private lateinit var tvGpsStatus       : TextView
    private lateinit var tvCameraStatus    : TextView
    private lateinit var btnRecord         : MaterialButton
    private lateinit var btnEvent          : MaterialButton

    private lateinit var cameraManager     : DVRCameraManager
    private lateinit var frontRecorder     : FrontCameraRecorder
    private lateinit var cameraValidator   : CameraValidator

    private var recordingService           : RecordingService? = null
    private var serviceBound               = false
    private var currentSessionDir          : File? = null
    private var stateObserversStarted      = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as RecordingService.RecordingBinder).getService()
            recordingService = svc
            // Module 7: inject camera references so LoopRecorder can drive them
            svc.dvrCamera   = cameraManager
            svc.frontCamera = frontRecorder
            serviceBound = true
            // Reset orphaned Recording state (HyperOS keeps service alive after app kill)
            val isOrphaned = (svc.state.value is RecordingService.ServiceState.Recording ||
                              svc.state.value is RecordingService.ServiceState.Starting) &&
                             currentSessionDir == null
            if (isOrphaned) {
                Log.w(TAG, "Orphaned state on bind — resetting")
                svc.resetToIdle()
            }
            if (!stateObserversStarted) {
                stateObserversStarted = true
                observeServiceState()
            }
            Log.d(TAG, "Bound to RecordingService (state=${svc.state.value})")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            recordingService = null; serviceBound = false
            Log.w(TAG, "Service disconnected")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) onPermissionsReady()
        else Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        bindViews()
        setupClickListeners()
        cameraManager   = DVRCameraManager(this)
        frontRecorder   = FrontCameraRecorder(this)
        cameraValidator = CameraValidator(this)
        checkAndRequestPermissions()
    }

    override fun onStart() {
        super.onStart()
        if (!serviceBound)
            bindService(Intent(this, RecordingService::class.java),
                serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        val s = recordingService?.state?.value
        val isActive = s is RecordingService.ServiceState.Recording ||
                       s is RecordingService.ServiceState.Starting  ||
                       s is RecordingService.ServiceState.Stopping
        if (serviceBound && !isActive) {
            unbindService(serviceConnection); serviceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.stopAll()
        frontRecorder.close()
        if (serviceBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            serviceBound = false
        }
    }

    // ── Views & Clicks ────────────────────────────────────────────────────

    private fun bindViews() {
        previewRear    = findViewById(R.id.previewRear)
        previewFront   = findViewById(R.id.previewFront)
        tvTimer        = findViewById(R.id.tvTimer)
        tvGpsStatus    = findViewById(R.id.tvGpsStatus)
        tvCameraStatus = findViewById(R.id.tvCameraStatus)
        btnRecord      = findViewById(R.id.btnRecord)
        btnEvent       = findViewById(R.id.btnEvent)
    }

    private fun setupClickListeners() {
        btnRecord.setOnClickListener {
            val svc = recordingService ?: run {
                Toast.makeText(this, "Service not ready", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (svc.state.value is RecordingService.ServiceState.Recording) {
                // ── STOP ───────────────────────────────────────────────
                // Module 7: LoopRecorder.stop() finalises last segment (called from RecordingService)
                // frontRecorder stop owned by LoopRecorder
                currentSessionDir?.let { Log.i(TAG, "Session closed: ${it.absolutePath}") }
                currentSessionDir = null
                svc.stopRecording()
            } else {
                // ── START ─────────────────────────────────────────────────────────────────
                // Module 4: SessionManager owns session dir — get it from the service Binder
                // so cameras and telemetry land in the exact same folder.
                val sessionDir = svc.prepareSessionDir().also { currentSessionDir = it }
                // Module 7: LoopRecorder (inside RecordingService) owns camera start/stop
                svc.startRecording(sessionDir.absolutePath)
            }
        }
        btnEvent.setOnClickListener {
            recordingService?.triggerEvent()
            Toast.makeText(this, "Event saved!", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────

    private fun checkAndRequestPermissions() {
        val required = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
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
        Log.i(TAG, "All permissions granted — starting cameras")
        runCameraValidation()
        startRearPreview()
        frontRecorder.open(previewFront)   // Camera2 front — independent of CameraX
    }

    // ── Camera ────────────────────────────────────────────────────────────

    private fun runCameraValidation() {
        lifecycleScope.launch {
            val result = cameraValidator.validate()
            val dual = if (result.hasDualCameras) "Dual OK" else "Single only"
            tvCameraStatus.text = if (!result.isViable) "Camera issue"
                else "$dual | ${if (result.rearSupportsFullLevel) "FULL" else "LIMITED"}"
        }
    }

    private fun startRearPreview() {
        lifecycleScope.launch {
            try {
                tvCameraStatus.text = "Starting rear camera..."
                val (rear, _) = cameraManager.enumerateCameras()
                cameraManager.startPreview(this@MainActivity, previewRear)
                tvCameraStatus.text = "REAR:${rear?.logicalId ?: "N/A"}"
            } catch (e: Exception) {
                Log.e(TAG, "Rear camera start failed: ${e.message}", e)
                tvCameraStatus.text = "Camera error: ${e.message}"
            }
        }
        lifecycleScope.launch {
            cameraManager.state.collectLatest { camState ->
                when (camState) {
                    is DVRCameraManager.CameraState.Previewing ->
                        tvCameraStatus.setTextColor(
                            ContextCompat.getColor(this@MainActivity, R.color.dvr_teal))
                    is DVRCameraManager.CameraState.Error -> {
                        tvCameraStatus.text = "Camera error: ${camState.message}"
                        tvCameraStatus.setTextColor(
                            ContextCompat.getColor(this@MainActivity, R.color.dvr_red))
                    }
                    else -> {}
                }
            }
        }
    }

    // createSessionDir() removed — Module 4: SessionManager owns session dir creation

    // ── Service observer — UI only ────────────────────────────────────────

    private fun observeServiceState() {
        lifecycleScope.launch {
            recordingService?.state?.collectLatest { svcState ->
                when (svcState) {
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
                            "Service error: ${svcState.message}", Toast.LENGTH_LONG).show()
                    else -> {}
                }
            }
        }
        lifecycleScope.launch {
            recordingService?.elapsedSeconds?.collectLatest { secs ->
                tvTimer.text = "%02d:%02d:%02d"
                    .format(secs / 3600, (secs % 3600) / 60, secs % 60)
            }
        }
        lifecycleScope.launch {
            recordingService?.hasGpsFix?.collectLatest { hasFix ->
                tvGpsStatus.text = if (hasFix) "\u2705 GPS Fixed" else "\uD83D\uDCE1 GPS Acquiring\u2026"
                tvGpsStatus.setTextColor(
                    ContextCompat.getColor(this@MainActivity,
                        if (hasFix) R.color.dvr_teal else R.color.dvr_amber))
            }
        }
    }
}
