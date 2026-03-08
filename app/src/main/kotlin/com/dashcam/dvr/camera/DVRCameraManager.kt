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
 * DVRCameraManager — Dual concurrent camera pipeline + video recording (CameraX 1.4.x)
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
    private var sharedProvider:      ProcessCameraProvider? = null
    private var rearVideoCapture:    VideoCapture<Recorder>? = null
    private var frontVideoCapture:   VideoCapture<Recorder>? = null
    private var activeRearRecording:  Recording? = null
    private var activeFrontRecording: Recording? = null
    private var isDualCamera = false

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

    // ── Preview ───────────────────────────────────────────────────────────

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
            provider.unbindAll()
            bindDualCameras(provider, lifecycleOwner, rearPreviewView, frontPreviewView)
        } catch (e: Exception) {
            Log.e(TAG, "startPreview failed: ${e.message}", e)
            _state.value = CameraState.Error(e.message ?: "Preview failed")
            throw e
        }
    }

    private fun makeVideoCapture() = VideoCapture.withOutput(
        Recorder.Builder()
            .setQualitySelector(QualitySelector.from(
                Quality.HD,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build()
    )

    private fun bindDualCameras(
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

        val rearVc  = makeVideoCapture().also { rearVideoCapture  = it }
        val frontVc = makeVideoCapture().also { frontVideoCapture = it }

        try {
            val primary = ConcurrentCamera.SingleCameraConfig(
                CameraSelector.DEFAULT_BACK_CAMERA,
                UseCaseGroup.Builder().addUseCase(rearPreview).addUseCase(rearVc).build(),
                lifecycleOwner)
            val secondary = ConcurrentCamera.SingleCameraConfig(
                CameraSelector.DEFAULT_FRONT_CAMERA,
                UseCaseGroup.Builder().addUseCase(frontPreview).addUseCase(frontVc).build(),
                lifecycleOwner)
            provider.bindToLifecycle(listOf(primary, secondary))
            isDualCamera = true
            Log.i(TAG, "Dual bind SUCCESS — Preview + VideoCapture both cameras")
            _state.value = CameraState.Previewing

        } catch (e: Exception) {
            Log.e(TAG, "Dual bind failed: ${e.message} — rear-only fallback")
            isDualCamera = false
            frontVideoCapture = null
            provider.unbindAll()
            val rearOnly = Preview.Builder()
                .setResolutionSelector(resolutionSelector(
                    AppConstants.REAR_CAM_WIDTH, AppConstants.REAR_CAM_HEIGHT))
                .build().also { it.setSurfaceProvider(rearView.surfaceProvider) }
            val rearOnlyVc = makeVideoCapture().also { rearVideoCapture = it }
            provider.bindToLifecycle(lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA, rearOnly, rearOnlyVc)
            Log.w(TAG, "REAR-only mode — Preview + VideoCapture")
            _state.value = CameraState.Previewing
        }
    }

    // ── Video recording ───────────────────────────────────────────────────

    /**
     * Start recording to [sessionDir] (created if needed).
     * Files: rear_camera.mp4 + front_camera.mp4 (if dual).
     * Call from main thread after startPreview() has completed.
     * Returns true if rear recording started successfully.
     */
    @SuppressLint("MissingPermission")
    fun startVideoRecording(sessionDir: File): Boolean {
        if (_state.value == CameraState.Recording) return true
        sessionDir.mkdirs()
        val rearCapture = rearVideoCapture ?: run {
            Log.e(TAG, "startVideoRecording: no VideoCapture — call startPreview first")
            return false
        }
        val executor = ContextCompat.getMainExecutor(context)
        val rearFile = File(sessionDir, AppConstants.REAR_VIDEO_FILENAME)
        activeRearRecording = rearCapture.output
            .prepareRecording(context, FileOutputOptions.Builder(rearFile).build())
            .withAudioEnabled()
            .start(executor) { event ->
                when (event) {
                    is VideoRecordEvent.Start    -> Log.i(TAG, "Rear REC started")
                    is VideoRecordEvent.Finalize ->
                        if (event.hasError()) Log.e(TAG, "Rear REC error ${event.error}: ${event.cause}")
                        else Log.i(TAG, "Rear saved: ${rearFile.absolutePath} (${rearFile.length()/1024}KB)")
                    else -> {}
                }
            }
        if (isDualCamera) {
            frontVideoCapture?.let { fc ->
                val frontFile = File(sessionDir, AppConstants.FRONT_VIDEO_FILENAME)
                activeFrontRecording = fc.output
                    .prepareRecording(context, FileOutputOptions.Builder(frontFile).build())
                    .withAudioEnabled()
                    .start(executor) { event ->
                        when (event) {
                            is VideoRecordEvent.Start    -> Log.i(TAG, "Front REC started")
                            is VideoRecordEvent.Finalize ->
                                if (event.hasError()) Log.e(TAG, "Front REC error ${event.error}: ${event.cause}")
                                else Log.i(TAG, "Front saved: ${frontFile.absolutePath} (${frontFile.length()/1024}KB)")
                            else -> {}
                        }
                    }
            }
        }
        _state.value = CameraState.Recording
        Log.i(TAG, "Recording started → ${sessionDir.absolutePath}")
        return true
    }

    fun stopVideoRecording() {
        activeRearRecording?.stop()
        activeFrontRecording?.stop()
        activeRearRecording  = null
        activeFrontRecording = null
        _state.value = CameraState.Previewing
        Log.i(TAG, "Recording stopped")
    }

    fun stopAll() {
        stopVideoRecording()
        sharedProvider?.unbindAll()
        sharedProvider = null; rearVideoCapture = null; frontVideoCapture = null
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
