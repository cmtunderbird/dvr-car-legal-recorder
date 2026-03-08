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
import com.dashcam.dvr.camera.DVRCameraManager
import com.dashcam.dvr.camera.CameraValidator
import com.dashcam.dvr.service.RecordingService
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
            Log.d(TAG, "Bound to RecordingService")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            recordingService = null
            serviceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) onPermissionsReady()
        else Toast.makeText(this, "Camera and location permissions are required", Toast.LENGTH_LONG).show()
    }

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
        bindService(Intent(this, RecordingService::class.java), serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.stopAll()
    }

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
            if (recordingService?.state?.value is RecordingService.ServiceState.Recording)
                RecordingService.stopRecording(this)
            else
                RecordingService.startRecording(this)
        }
        btnEvent.setOnClickListener {
            RecordingService.triggerManualEvent(this)
            Toast.makeText(this, "⚡ Event saved!", Toast.LENGTH_SHORT).show()
        }
    }


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
        if (missing.isEmpty()) onPermissionsReady() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun onPermissionsReady() {
        Log.i(TAG, "All permissions granted — starting camera")
        runCameraValidation()
        startDualPreview()
    }

    private fun runCameraValidation() {
        lifecycleScope.launch {
            val result = cameraValidator.validate()
            if (!result.isViable) {
                tvCameraStatus.text = "⚠️ Camera issue detected"
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
                val rearId  = rear?.logicalId  ?: "N/A"
                val frontId = front?.logicalId ?: "N/A"
                tvCameraStatus.text = "📷 R:$rearId  F:$frontId"
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

    private fun observeServiceState() {
        // ── Recording state → button + colour ─────────────────────────────
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

        // ── Timer — driven by elapsedSeconds from the service ──────────────
        lifecycleScope.launch {
            recordingService?.elapsedSeconds?.collectLatest { secs ->
                val h = secs / 3600
                val m = (secs % 3600) / 60
                val s = secs % 60
                tvTimer.text = "%02d:%02d:%02d".format(h, m, s)
            }
        }
    }
}
