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
 * DVRCameraManager — CameraX, rear camera only.
 *
 * Binds rear camera with Preview + VideoCapture in a single bindToLifecycle() call.
 * Front camera is handled independently by FrontCameraRecorder (Camera2 + MediaRecorder),
 * which opens its own CameraDevice session and does not conflict with this CameraX session.
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

    var rearCameraInfo:   CameraInfo? = null; private set
    var frontCameraInfo:  CameraInfo? = null; private set

    private var sharedProvider:      ProcessCameraProvider? = null
    private var rearVideoCapture:    VideoCapture<Recorder>? = null
    private var activeRearRecording: Recording? = null

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
            Log.i(TAG, "FRONT: $frontCameraInfo (handled by FrontCameraRecorder)")
            Pair(rearCameraInfo, frontCameraInfo)
        }

    private fun resolutionSelector(w: Int, h: Int) =
        ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(
                Size(w, h),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()

    // ── startPreview: rear camera only (Preview + VideoCapture) ──────────

    suspend fun startPreview(
        lifecycleOwner   : LifecycleOwner,
        rearPreviewView  : PreviewView
    ) {
        _state.value = CameraState.Initialising
        try {
            if (rearCameraInfo == null) enumerateCameras()
            val provider = getCameraProvider()
            sharedProvider = provider
            provider.unbindAll()

            val rearPreview = Preview.Builder()
                .setResolutionSelector(resolutionSelector(
                    AppConstants.REAR_CAM_WIDTH, AppConstants.REAR_CAM_HEIGHT))
                .build().also { it.setSurfaceProvider(rearPreviewView.surfaceProvider) }

            val rearVc = VideoCapture.withOutput(
                Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(
                        Quality.HD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .build())
            rearVideoCapture = rearVc

            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                rearPreview,
                rearVc
            )
            Log.i(TAG, "Rear camera bound (Preview + VideoCapture)")
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

    @SuppressLint("MissingPermission")
    fun startVideoRecording(sessionDir: File): Boolean {
        if (_state.value == CameraState.Recording) return true
        val vc = rearVideoCapture ?: run {
            Log.e(TAG, "startVideoRecording: rearVideoCapture null — startPreview not called")
            return false
        }
        sessionDir.mkdirs()
        val rearFile = File(sessionDir, AppConstants.REAR_VIDEO_FILENAME)
        return try {
            activeRearRecording = vc.output
                .prepareRecording(context, FileOutputOptions.Builder(rearFile).build())
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context)) { event ->
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
            Log.i(TAG, "Rear recording → ${sessionDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "prepareRecording failed: ${e.message}", e)
            false
        }
    }

    /**
     * Start recording to an arbitrary output file (for LoopRecorder segment files).
     * Identical to startVideoRecording() but writes to [outputFile] directly instead
     * of using the hardcoded rear_camera.mp4 filename inside sessionDir.
     *
     * Module 7 (LoopRecorder) calls this to write numbered segment files:
     *   segments/seg_0001_rear.mp4, segments/seg_0002_rear.mp4, ...
     */
    @SuppressLint("MissingPermission")
    fun startVideoRecordingToFile(outputFile: File): Boolean {
        if (_state.value == CameraState.Recording) {
            Log.w(TAG, "startVideoRecordingToFile: already recording")
            return false
        }
        val vc = rearVideoCapture ?: run {
            Log.e(TAG, "startVideoRecordingToFile: rearVideoCapture null")
            return false
        }
        outputFile.parentFile?.mkdirs()
        return try {
            activeRearRecording = vc.output
                .prepareRecording(context, FileOutputOptions.Builder(outputFile).build())
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start    ->
                            Log.i(TAG, "Rear REC started -> ${outputFile.name}")
                        is VideoRecordEvent.Finalize ->
                            if (event.hasError())
                                Log.e(TAG, "Rear REC error ${event.error}: ${event.cause?.message}")
                            else
                                Log.i(TAG, "Rear saved: ${outputFile.name} (${outputFile.length() / 1024} KB)")
                        else -> {}
                    }
                }
            _state.value = CameraState.Recording
            Log.i(TAG, "Rear recording -> ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "startVideoRecordingToFile failed: ${e.message}", e)
            false
        }
    }
    fun stopVideoRecording() {
        activeRearRecording?.stop()
        activeRearRecording = null
        if (_state.value == CameraState.Recording) _state.value = CameraState.Previewing
        Log.i(TAG, "Rear recording stopped")
    }

    fun stopAll() {
        stopVideoRecording()
        sharedProvider?.unbindAll()
        sharedProvider = null
        rearVideoCapture = null
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
