package com.dashcam.dvr.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager as AndroidCameraManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.dashcam.dvr.util.AppConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * DVRCameraManager — CameraX 1.4.x
 *
 * STRATEGY: Standard multi-camera bind (NOT concurrent camera API).
 *
 * The ConcurrentCamera API only supports Preview use cases — adding VideoCapture
 * to a concurrent UseCaseGroup throws an IllegalArgumentException. Attempting to
 * bind VideoCapture separately on top of an existing concurrent session also fails
 * because the two binding modes are mutually exclusive in CameraX.
 *
 * Instead: use standard bindToLifecycle() for each camera independently.
 * CameraX internally handles multi-camera allocation the same way the OS would.
 * Devices that support opening two cameras simultaneously (like the Redmi Note 14 Pro,
 * confirmed by working dual preview) handle this without the concurrent API's restrictions.
 *
 * Bind order:
 *   1. Rear: Preview + VideoCapture  (primary recording + display)
 *   2. Front: Preview only            (secondary display; audio shared via rear mic)
 *
 * If front bind fails → rear-only mode (rear still records normally).
 * If rear bind fails  → Error state (recording is impossible).
 */
@SuppressLint("UnsafeOptInUsageError")
class DVRCameraManager(private val context: Context) {

    companion object { private const val TAG = "DVRCameraManager" }

    sealed class CameraState {
        object Idle         : CameraState()
        object Initialising : CameraState()
        object Previewing   : CameraState()
        object Recording    : CameraState()
        data class Error(val message: String) : CameraState()
    }

    private val _state = MutableStateFlow<CameraState>(CameraState.Idle)
    val state: StateFlow<CameraState> = _state

    data class CameraInfo(
        val logicalId    : String,
        val physicalId   : String?,
        val facing       : Int,
        val focalLengths : FloatArray?,
        val supportLevel : Int,
        val maxVideoSize : Size?
    ) {
        override fun toString() =
            "CameraInfo(id=$logicalId, facing=${facingName(facing)}, " +
            "level=${supportLevelName(supportLevel)}, maxVideo=$maxVideoSize)"
        private fun facingName(f: Int) = when (f) {
            CameraCharacteristics.LENS_FACING_BACK  -> "BACK"
            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            else -> "EXTERNAL($f)"
        }
        private fun supportLevelName(l: Int) = when (l) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY  -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL    -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3       -> "LEVEL_3"
            else -> "UNKNOWN($l)"
        }
    }

    var rearCameraInfo:  CameraInfo? = null; private set
    var frontCameraInfo: CameraInfo? = null; private set

    private var sharedProvider:       ProcessCameraProvider? = null
    private var rearVideoCapture:     VideoCapture<Recorder>? = null
    private var activeRearRecording:  Recording? = null
    var isDualCamera:                 Boolean = false; private set

    // ── Camera enumeration ────────────────────────────────────────────────

    suspend fun enumerateCameras(): Pair<CameraInfo?, CameraInfo?> =
        withContext(Dispatchers.IO) {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as AndroidCameraManager
            val all = mutableListOf<CameraInfo>()
            for (id in cm.cameraIdList) {
                try {
                    val c      = cm.getCameraCharacteristics(id)
                    val facing = c.get(CameraCharacteristics.LENS_FACING) ?: continue
                    val level  = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                                   ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
                    val focal  = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val map    = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val maxSz  = map?.getOutputSizes(MediaRecorder::class.java)
                                    ?.maxByOrNull { it.width * it.height }
                    val physId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                                    c.physicalCameraIds.firstOrNull() else null
                    all.add(CameraInfo(id, physId, facing, focal, level, maxSz))
                } catch (e: Exception) { Log.w(TAG, "Skip camera $id: ${e.message}") }
            }
            rearCameraInfo  = all.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
                                 .maxByOrNull { it.focalLengths?.maxOrNull() ?: 0f }
            frontCameraInfo = all.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
            Log.i(TAG, "REAR : $rearCameraInfo")
            Log.i(TAG, "FRONT: $frontCameraInfo")
            Pair(rearCameraInfo, frontCameraInfo)
        }

    private fun resolutionSelector(w: Int, h: Int) =
        ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(
                Size(w, h),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()

    private fun makeRecorder() = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(
            Quality.HD,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
        .build()

    // ── startPreview: binds Preview + VideoCapture for rear, Preview for front ──

    suspend fun startPreview(
        lifecycleOwner   : LifecycleOwner,
        rearPreviewView  : PreviewView,
        frontPreviewView : PreviewView
    ) {
        _state.value = CameraState.Initialising
        try {
            if (rearCameraInfo == null) enumerateCameras()
            val provider = getCameraProvider()
            sharedProvider = provider
            provider.unbindAll()

            // ── Rear camera: Preview + VideoCapture (always) ───────────────
            val rearPreview = Preview.Builder()
                .setResolutionSelector(resolutionSelector(
                    AppConstants.REAR_CAM_WIDTH, AppConstants.REAR_CAM_HEIGHT))
                .build().also { it.setSurfaceProvider(rearPreviewView.surfaceProvider) }

            val rearVc = VideoCapture.withOutput(makeRecorder())
            rearVideoCapture = rearVc

            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                rearPreview,
                rearVc
            )
            Log.i(TAG, "Rear camera bound (Preview + VideoCapture)")

            // ── Front camera: Preview only (best effort) ───────────────────
            try {
                val frontPreview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector(
                        AppConstants.FRONT_CAM_WIDTH, AppConstants.FRONT_CAM_HEIGHT))
                    .build().also { it.setSurfaceProvider(frontPreviewView.surfaceProvider) }

                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    frontPreview
                )
                isDualCamera = true
                Log.i(TAG, "Front camera bound (Preview) — dual mode active")
            } catch (e: Exception) {
                isDualCamera = false
                Log.w(TAG, "Front camera bind failed (rear-only mode): ${e.message}")
            }

            _state.value = CameraState.Previewing

        } catch (e: Exception) {
            Log.e(TAG, "Rear camera bind failed: ${e.message}", e)
            rearVideoCapture = null
            sharedProvider = null
            _state.value = CameraState.Error(e.message ?: "Camera init failed")
            throw e
        }
    }

    // ── Video recording ───────────────────────────────────────────────────

    /**
     * Start recording rear_camera.mp4 into [sessionDir].
     *
     * VideoCapture is already bound in startPreview() — no extra bind needed here.
     * We simply call prepareRecording().start() on the existing Recorder output.
     * Returns true if recording started, false if VideoCapture is not ready.
     */
    @SuppressLint("MissingPermission")
    fun startVideoRecording(sessionDir: File): Boolean {
        if (_state.value == CameraState.Recording) {
            Log.w(TAG, "startVideoRecording: already recording")
            return true
        }
        val vc = rearVideoCapture ?: run {
            Log.e(TAG, "startVideoRecording: rearVideoCapture is null — startPreview not called or failed")
            return false
        }

        sessionDir.mkdirs()
        val rearFile = File(sessionDir, AppConstants.REAR_VIDEO_FILENAME)
        val executor = ContextCompat.getMainExecutor(context)

        try {
            activeRearRecording = vc.output
                .prepareRecording(context, FileOutputOptions.Builder(rearFile).build())
                .withAudioEnabled()
                .start(executor) { event ->
                    when (event) {
                        is VideoRecordEvent.Start    ->
                            Log.i(TAG, "Rear REC started → ${rearFile.name}")
                        is VideoRecordEvent.Finalize ->
                            if (event.hasError())
                                Log.e(TAG, "Rear REC error ${event.error}: ${event.cause?.message}")
                            else
                                Log.i(TAG, "Rear saved: ${rearFile.absolutePath} (${rearFile.length() / 1024} KB)")
                        else -> {}
                    }
                }
            _state.value = CameraState.Recording
            Log.i(TAG, "Recording started → ${sessionDir.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "prepareRecording failed: ${e.message}", e)
            return false
        }
    }

    /** Stop the active recording. File finalises asynchronously — watch Logcat for KB size. */
    fun stopVideoRecording() {
        activeRearRecording?.stop()
        activeRearRecording = null
        if (_state.value == CameraState.Recording) {
            _state.value = CameraState.Previewing
        }
        Log.i(TAG, "Recording stopped")
    }

    fun stopAll() {
        stopVideoRecording()
        sharedProvider?.unbindAll()
        sharedProvider = null
        rearVideoCapture = null
        isDualCamera = false
        _state.value = CameraState.Idle
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            ProcessCameraProvider.getInstance(context).also { f ->
                f.addListener({
                    try   { cont.resume(f.get()) }
                    catch (e: Exception) { cont.resumeWithException(e) }
                }, ContextCompat.getMainExecutor(context))
            }
        }
}
