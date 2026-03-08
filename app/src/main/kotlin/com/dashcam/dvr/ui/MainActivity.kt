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
 * SERVICE STARTUP — startForegroundService() + bindService() in onStart():
 *
 * Android 14 requires a service to be properly *started* (via startForegroundService)
 * before startForeground() is valid. BIND_AUTO_CREATE alone creates the service but
 * doesn't satisfy this requirement — startForeground() silently fails or throws.
 *
 * Fix: call startForegroundService() in onStart() so the service is both started
 * AND bound. The HyperOS privacy banner fires ONCE at app launch while you're
 * already looking at the screen — safe. Button press is a pure binder call — no
 * system events, no banner, no minimize.
 *
 * onStop() UNBIND GUARD — covers Starting + Stopping too:
 * If any system event (banner, screen-off) triggers onStop() while state is
 * transitioning, we must NOT unbind or the service dies mid-start.
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
        // 1. startForegroundService() — satisfies Android 14's requirement that the service
        //    be *started* before startForeground() is valid. The HyperOS privacy banner
        //    fires here, at app startup, not on button press.
        // 2. bindService() — gets us the binder for direct method calls.
        // Both calls are safe to repeat if service is already running.
        val svcIntent = Intent(this, RecordingService::class.java)
        startForegroundService(svcIntent)
        if (!serviceBound) {
            bindService(svcIntent, serviceConnection, BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        // Guard ALL active states — Starting and Stopping are transitional and must
        // not be interrupted by an unbind (e.g. if HyperOS briefly backgrounds us).
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
