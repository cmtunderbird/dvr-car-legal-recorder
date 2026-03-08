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
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
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
 * ARCHITECTURE — Preview and Recording are kept separate:
 *
 * PREVIEW: Uses ConcurrentCamera bindToLifecycle(List<SingleCameraConfig>) with
 * Preview-only UseCaseGroups. CameraX concurrent mode only supports Preview —
 * adding VideoCapture to concurrent UseCaseGroups causes an IllegalArgumentException.
 *
 * RECORDING: Uses a separate standard single-camera VideoCapture bind on the rear
 * camera only. CameraX allows adding a VideoCapture use case on top of an existing
 * concurrent Preview session on the primary camera.
 * Front camera audio is captured via the rear camera's withAudioEnabled() call
 * (the microphone is shared — both streams get audio).
 *
 * For a future full dual-video recording: replace VideoCapture with Camera2
 * MediaRecorder sessions (planned for Module 7 evidence packager).
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
    private var recordingLifecycle:   LifecycleOwner? = null

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

    // ── Preview (concurrent, Preview-only UseCaseGroups) ──────────────────

    suspend fun startPreview(
        lifecycleOwner   : LifecycleOwner,
        rearPreviewView  : PreviewView,
        frontPreviewView : PreviewView
    ) {
        _state.value = CameraState.Initialising
        try {
            if (rearCameraInfo == null || frontCameraInfo == null) enumerateCameras()
            val provider = getCameraProvider()
            sharedProvider = provider
            recordingLifecycle = lifecycleOwner
            provider.unbindAll()
            bindDualPreview(provider, lifecycleOwner, rearPreviewView, frontPreviewView)
        } catch (e: Exception) {
            Log.e(TAG, "startPreview failed: ${e.message}", e)
            _state.value = CameraState.Error(e.message ?: "Preview failed")
            throw e
        }
    }

    private fun bindDualPreview(
        provider       : ProcessCameraProvider,
        lifecycleOwner : LifecycleOwner,
        rearView       : PreviewView,
        frontView      : PreviewView
    ) {
        val rearPreview = Preview.Builder()
            .setResolutionSelector(resolutionSelector(1280, 720))
            .build().also { it.setSurfaceProvider(rearView.surfaceProvider) }
        val frontPreview = Preview.Builder()
            .setResolutionSelector(resolutionSelector(
                AppConstants.FRONT_CAM_WIDTH, AppConstants.FRONT_CAM_HEIGHT))
            .build().also { it.setSurfaceProvider(frontView.surfaceProvider) }

        try {
            // Concurrent mode: Preview-only UseCaseGroups (VideoCapture not supported here)
            val primary = ConcurrentCamera.SingleCameraConfig(
                CameraSelector.DEFAULT_BACK_CAMERA,
                UseCaseGroup.Builder().addUseCase(rearPreview).build(),
                lifecycleOwner)
            val secondary = ConcurrentCamera.SingleCameraConfig(
                CameraSelector.DEFAULT_FRONT_CAMERA,
                UseCaseGroup.Builder().addUseCase(frontPreview).build(),
                lifecycleOwner)
            provider.bindToLifecycle(listOf(primary, secondary))
            Log.i(TAG, "Dual preview bind SUCCESS")
            _state.value = CameraState.Previewing

        } catch (e: Exception) {
            Log.e(TAG, "Dual preview failed: ${e.message} — rear-only fallback")
            provider.unbindAll()
            val rearOnly = Preview.Builder()
                .setResolutionSelector(resolutionSelector(
                    AppConstants.REAR_CAM_WIDTH, AppConstants.REAR_CAM_HEIGHT))
                .build().also { it.setSurfaceProvider(rearView.surfaceProvider) }
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, rearOnly)
            Log.w(TAG, "REAR-only preview active")
            _state.value = CameraState.Previewing
        }
    }

    // ── Video recording (separate bind on rear camera) ────────────────────

    /**
     * Start recording rear_camera.mp4 into [sessionDir].
     *
     * Uses a separate VideoCapture use case bound on the rear camera.
     * CameraX allows adding VideoCapture on top of an existing concurrent
     * preview session — the provider merges the use cases internally.
     *
     * Must be called on the main thread after startPreview() has completed.
     */
    @SuppressLint("MissingPermission")
    fun startVideoRecording(sessionDir: File): Boolean {
        if (_state.value == CameraState.Recording) {
            Log.w(TAG, "startVideoRecording called while already recording — ignored")
            return true
        }
        val provider = sharedProvider ?: run {
            Log.e(TAG, "startVideoRecording: provider null — startPreview not called")
            return false
        }
        val lifecycle = recordingLifecycle ?: run {
            Log.e(TAG, "startVideoRecording: lifecycleOwner null")
            return false
        }

        sessionDir.mkdirs()

        // Bind VideoCapture on the rear camera
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(
                Quality.HD,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build()
        val videoCapture = VideoCapture.withOutput(recorder)
        rearVideoCapture = videoCapture

        try {
            provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, videoCapture)
            Log.i(TAG, "VideoCapture bound to rear camera")
        } catch (e: Exception) {
            Log.e(TAG, "VideoCapture bind failed: ${e.message}", e)
            rearVideoCapture = null
            return false
        }

        val rearFile = File(sessionDir, AppConstants.REAR_VIDEO_FILENAME)
        val executor = ContextCompat.getMainExecutor(context)
        activeRearRecording = videoCapture.output
            .prepareRecording(context, FileOutputOptions.Builder(rearFile).build())
            .withAudioEnabled()
            .start(executor) { event ->
                when (event) {
                    is VideoRecordEvent.Start    ->
                        Log.i(TAG, "Rear REC started → ${rearFile.name}")
                    is VideoRecordEvent.Finalize ->
                        if (event.hasError())
                            Log.e(TAG, "Rear REC finalize error ${event.error}: ${event.cause?.message}")
                        else
                            Log.i(TAG, "Rear saved: ${rearFile.absolutePath} (${rearFile.length()/1024} KB)")
                    else -> {}
                }
            }

        _state.value = CameraState.Recording
        Log.i(TAG, "Recording started → ${sessionDir.absolutePath}")
        return true
    }

    /** Stop the active recording. File is finalized asynchronously (watch Logcat for KB size). */
    fun stopVideoRecording() {
        activeRearRecording?.stop()
        activeRearRecording = null
        // Unbind the VideoCapture use case — preview continues unaffected
        rearVideoCapture?.let { vc ->
            try { sharedProvider?.unbind(vc) } catch (_: Exception) {}
            rearVideoCapture = null
        }
        _state.value = CameraState.Previewing
        Log.i(TAG, "Recording stopped — VideoCapture unbound, preview continues")
    }

    fun stopAll() {
        stopVideoRecording()
        sharedProvider?.unbindAll()
        sharedProvider = null
        recordingLifecycle = null
        _state.value = CameraState.Idle
    }

    fun validateCameraIds(prevRearId: String?, prevFrontId: String?): Boolean {
        val rm = prevRearId  == null || prevRearId  == rearCameraInfo?.logicalId
        val fm = prevFrontId == null || prevFrontId == frontCameraInfo?.logicalId
        if (!rm) Log.w(TAG, "REAR ID changed: $prevRearId -> ${rearCameraInfo?.logicalId}")
        if (!fm) Log.w(TAG, "FRONT ID changed: $prevFrontId -> ${frontCameraInfo?.logicalId}")
        return rm && fm
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
