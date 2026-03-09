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
import android.view.View
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
import com.dashcam.dvr.ui.events.EventReviewActivity
import com.dashcam.dvr.ui.hud.HudOverlayView
import com.dashcam.dvr.ui.setup.SetupWizardActivity
import com.dashcam.dvr.util.AppConstants
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var previewRear       : PreviewView
    private lateinit var previewFront      : TextureView
    private lateinit var tvTimer           : TextView
    private lateinit var tvGpsStatus       : TextView
    private lateinit var tvCameraStatus    : TextView
    private lateinit var tvProtectedBadge  : TextView
    private lateinit var btnRecord         : MaterialButton
    private lateinit var btnEvent          : MaterialButton
    private lateinit var btnReviewEvents   : MaterialButton
    private lateinit var hudOverlay        : HudOverlayView

    private lateinit var cameraManager     : DVRCameraManager
    private lateinit var frontRecorder     : FrontCameraRecorder
    private lateinit var cameraValidator   : CameraValidator

    private var recordingService           : RecordingService? = null
    private var serviceBound               = false
    private var currentSessionDir          : File? = null
    private var stateObserversStarted      = false
    private var blinkState                 = true   // HUD REC dot blink toggle

    // Service connection

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as RecordingService.RecordingBinder).getService()
            recordingService = svc
            svc.dvrCamera   = cameraManager
            svc.frontCamera = frontRecorder
            serviceBound = true
            val isOrphaned = (svc.state.value is RecordingService.ServiceState.Recording ||
                              svc.state.value is RecordingService.ServiceState.Starting) &&
                             currentSessionDir == null
            if (isOrphaned) {
                Log.w(TAG, "Orphaned state on bind -- resetting")
                svc.resetToIdle()
            }
            if (!stateObserversStarted) {
                stateObserversStarted = true
                observeServiceState()
                observeHudData()
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

    // Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // First-launch: redirect to setup wizard if not completed
        if (!isSetupComplete()) {
            // FLAG_ACTIVITY_CLEAR_TASK: wipe stale back-stack before wizard
            startActivity(Intent(this, SetupWizardActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        bindViews()
        setupClickListeners()
        startBlinkLoop()

        cameraManager   = DVRCameraManager(this)
        frontRecorder   = FrontCameraRecorder(this)
        cameraValidator = CameraValidator(this)
        checkAndRequestPermissions()
    }

    override fun onStart() {
        super.onStart()
        if (!isSetupComplete()) return  // early-exit guard: don't bind service before setup
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
        // Guard: if onCreate() returned early (setup redirect), lateinit props are uninitialized
        if (::cameraManager.isInitialized) cameraManager.stopAll()
        if (::frontRecorder.isInitialized) frontRecorder.close()
        try { unbindService(serviceConnection) } catch (_: Exception) {}
        serviceBound = false
    }

    // Views Clicks

    private fun bindViews() {
        previewRear          = findViewById(R.id.previewRear)
        previewFront         = findViewById(R.id.previewFront)
        tvTimer              = findViewById(R.id.tvTimer)
        tvGpsStatus          = findViewById(R.id.tvGpsStatus)
        tvCameraStatus       = findViewById(R.id.tvCameraStatus)
        tvProtectedBadge     = findViewById(R.id.tvProtectedBadge)
        btnRecord            = findViewById(R.id.btnRecord)
        btnEvent             = findViewById(R.id.btnEvent)
        btnReviewEvents      = findViewById(R.id.btnReviewEvents)
        hudOverlay           = findViewById(R.id.hudOverlay)
    }

    private fun setupClickListeners() {
        btnRecord.setOnClickListener {
            val svc = recordingService ?: run {
                Toast.makeText(this, "Service not ready", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (svc.state.value is RecordingService.ServiceState.Recording) {
                currentSessionDir?.let { Log.i(TAG, "Session closed: ${it.absolutePath}") }
                currentSessionDir = null
                svc.stopRecording()
            } else {
                val sessionDir = svc.prepareSessionDir().also { currentSessionDir = it }
                svc.startRecording(sessionDir.absolutePath)
            }
        }

        btnEvent.setOnClickListener {
            recordingService?.triggerEvent()
            Toast.makeText(this, "Event saved!", Toast.LENGTH_SHORT).show()
        }

        btnReviewEvents.setOnClickListener {
            startActivity(Intent(this, EventReviewActivity::class.java))
        }
    }

    // First launch check

    private fun isSetupComplete(): Boolean =
        getSharedPreferences(SetupWizardActivity.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(SetupWizardActivity.KEY_SETUP_DONE, false)

    // Permissions

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
        Log.i(TAG, "All permissions granted -- starting cameras")
        runCameraValidation()
        startRearPreview()
        frontRecorder.open(previewFront)
    }

    // Camera

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
                tvCameraStatus.text = "ROAD:OK | CABIN:OK"
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

    // blink loop

    /** Toggles blinkState every 500ms — drives the REC dot blink in HudOverlayView. */
    private fun startBlinkLoop() {
        lifecycleScope.launch {
            while (true) {
                blinkState = !blinkState
                // HUD update is triggered by observeHudData; blink flag read there
                delay(500)
            }
        }
    }

    // observer combines all live flows into HudOverlayView update

    private fun observeHudData() {
        val svc = recordingService ?: return

        // Elapsed timer also updates the legacy text view
        lifecycleScope.launch {
            svc.elapsedSeconds.collectLatest { elapsed ->
                val h = elapsed / 3600; val m = (elapsed % 3600) / 60; val s = elapsed % 60
                tvTimer.text = "%02d:%02d:%02d".format(h, m, s)
            }
        }

        // fix also updates legacy text view
        lifecycleScope.launch {
            svc.hasGpsFix.collectLatest { fix ->
                if (fix) {
                    tvGpsStatus.text = "GPS Valid"
                    tvGpsStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.dvr_teal))
                } else {
                    tvGpsStatus.text      = getString(R.string.gps_acquiring)
                    tvGpsStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.dvr_amber))
                }
            }
        }

        // Protected count badge in panel
        lifecycleScope.launch {
            svc.protectedCount.collectLatest { count ->
                if (count > 0) {
                    tvProtectedBadge.visibility = View.VISIBLE
                    tvProtectedBadge.text = "$count / ${AppConstants.MAX_PROTECTED_EVENTS} protected"
                    tvProtectedBadge.setTextColor(
                        if (count >= AppConstants.MAX_PROTECTED_EVENTS)
                            ContextCompat.getColor(this@MainActivity, R.color.dvr_red)
                        else
                            ContextCompat.getColor(this@MainActivity, R.color.dvr_amber)
                    )
                } else {
                    tvProtectedBadge.visibility = View.GONE
                }
            }
        }

        // Combined update runs driven data flow
        lifecycleScope.launch {
            svc.gpsData.collectLatest { gpsRec ->
                hudOverlay.update(
                    recording  = svc.state.value is RecordingService.ServiceState.Recording,
                    elapsed    = svc.elapsedSeconds.value,
                    gpsFix     = svc.hasGpsFix.value,
                    gps        = gpsRec,
                    protected  = svc.protectedCount.value,
                    blink      = blinkState
                )
            }
        }

        // HUD elapsed sync: driven by the same flow as tvTimer so both show identical seconds
        lifecycleScope.launch {
            svc.elapsedSeconds.collectLatest { elapsed ->
                hudOverlay.update(
                    recording  = svc.state.value is RecordingService.ServiceState.Recording,
                    elapsed    = elapsed,
                    gpsFix     = svc.hasGpsFix.value,
                    gps        = svc.gpsData.value,
                    protected  = svc.protectedCount.value,
                    blink      = blinkState
                )
            }
        }
    }

    // Service state observer updates buttons timer

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
                        // Reset HUD to standby
                        hudOverlay.update(
                            recording = false, elapsed = 0L,
                            gpsFix    = recordingService?.hasGpsFix?.value ?: false,
                            gps       = recordingService?.gpsData?.value,
                            protected = recordingService?.protectedCount?.value ?: 0,
                            blink     = false
                        )
                    }
                    is RecordingService.ServiceState.Error -> {
                        Toast.makeText(this@MainActivity,
                            "Recording error: ${svcState.message}", Toast.LENGTH_LONG).show()
                        btnRecord.text = getString(R.string.start_recording)
                        btnRecord.backgroundTintList =
                            ContextCompat.getColorStateList(this@MainActivity, R.color.dvr_teal)
                        btnEvent.isEnabled = false
                    }
                    else -> {}
                }
            }
        }
    }
}
