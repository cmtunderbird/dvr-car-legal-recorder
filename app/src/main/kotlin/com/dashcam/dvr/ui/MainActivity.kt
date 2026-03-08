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
import com.dashcam.dvr.util.AppConstants
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private var recordingService    : RecordingService? = null
    private var serviceBound        = false
    private var currentSessionDir   : File? = null
    // Guard: observeServiceState() must only register coroutines ONCE per Activity instance.
    // onServiceConnected() can fire multiple times (rebind after onStop) — without this flag
    // each rebind adds another collector, causing duplicate Recording events → extra empty dirs.
    private var stateObserversStarted = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            recordingService = (binder as RecordingService.RecordingBinder).getService()
            serviceBound = true
            // Only register observers once — prevents duplicate collectors on rebind
            if (!stateObserversStarted) {
                stateObserversStarted = true
                observeServiceState()
            }
            Log.d(TAG, "Bound to RecordingService (observers started=$stateObserversStarted)")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            recordingService = null
            serviceBound = false
            Log.w(TAG, "Service disconnected")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) onPermissionsReady()
        else Toast.makeText(this, "Camera and location permissions required", Toast.LENGTH_LONG).show()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

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
        if (!serviceBound) {
            bindService(Intent(this, RecordingService::class.java),
                serviceConnection, BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        val state = recordingService?.state?.value
        val isActive = state is RecordingService.ServiceState.Recording ||
                       state is RecordingService.ServiceState.Starting  ||
                       state is RecordingService.ServiceState.Stopping
        if (serviceBound && !isActive) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.stopAll()
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
            if (svc.state.value is RecordingService.ServiceState.Recording)
                svc.stopRecording()
            else
                svc.startRecording()
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
        Log.i(TAG, "All permissions granted")
        runCameraValidation()
        startDualPreview()
    }

    // ── Camera ────────────────────────────────────────────────────────────

    private fun runCameraValidation() {
        lifecycleScope.launch {
            val result = cameraValidator.validate()
            if (!result.isViable) {
                tvCameraStatus.text = "Camera issue"
                tvCameraStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.dvr_red))
            } else {
                val dual = if (result.hasDualCameras) "Dual OK" else "Single only"
                tvCameraStatus.text = "$dual | ${if (result.rearSupportsFullLevel) "FULL" else "LIMITED"}"
            }
        }
    }

    private fun startDualPreview() {
        lifecycleScope.launch {
            try {
                tvCameraStatus.text = "Starting cameras..."
                val (rear, front) = cameraManager.enumerateCameras()
                cameraManager.startPreview(this@MainActivity, previewRear, previewFront)
                tvCameraStatus.text = "R:${rear?.logicalId ?: "N/A"}  F:${front?.logicalId ?: "N/A"}"
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed: ${e.message}", e)
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

    private fun createSessionDir(): File {
        val ts   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = File(getExternalFilesDir(null), "DVR")
        return File(base, "${AppConstants.SESSION_DIR_PREFIX}$ts").also { it.mkdirs() }
    }

    // ── Service observers ─────────────────────────────────────────────────

    private fun observeServiceState() {
        lifecycleScope.launch {
            recordingService?.state?.collectLatest { svcState ->
                Log.d(TAG, "ServiceState → $svcState")
                when (svcState) {
                    is RecordingService.ServiceState.Recording -> {
                        btnRecord.text = getString(R.string.stop_recording)
                        btnRecord.backgroundTintList =
                            ContextCompat.getColorStateList(this@MainActivity, R.color.dvr_red)
                        btnEvent.isEnabled = true
                        val sessionDir = createSessionDir().also { currentSessionDir = it }
                        val ok = cameraManager.startVideoRecording(sessionDir)
                        Log.i(TAG, "startVideoRecording=$ok → ${sessionDir.name}")
                        if (ok) Toast.makeText(this@MainActivity,
                            "REC → ${sessionDir.name}", Toast.LENGTH_SHORT).show()
                        else Toast.makeText(this@MainActivity,
                            "Camera not ready for recording", Toast.LENGTH_LONG).show()
                    }
                    is RecordingService.ServiceState.Idle -> {
                        btnRecord.text = getString(R.string.start_recording)
                        btnRecord.backgroundTintList =
                            ContextCompat.getColorStateList(this@MainActivity, R.color.dvr_teal)
                        btnEvent.isEnabled = false
                        tvTimer.text = "00:00:00"
                        cameraManager.stopVideoRecording()
                        Log.i(TAG, "Session closed: ${currentSessionDir?.absolutePath}")
                        currentSessionDir = null
                    }
                    is RecordingService.ServiceState.Error ->
                        Toast.makeText(this@MainActivity,
                            "Error: ${svcState.message}", Toast.LENGTH_LONG).show()
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
    }
}
