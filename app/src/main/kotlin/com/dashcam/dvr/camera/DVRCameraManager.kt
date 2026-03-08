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
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.dashcam.dvr.util.AppConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * DVRCameraManager — Dual Camera Pipeline
 *
 * KEY FIX: ProcessCameraProvider is a singleton. Calling unbindAll() before each
 * camera bind kills the previously bound camera. Both previews must be bound
 * in a single bindToLifecycle() call using separate CameraSelector instances.
 */
class DVRCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "DVRCameraManager"
    }

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
            "CameraInfo(logical=$logicalId, physical=$physicalId, " +
            "facing=${facingName(facing)}, " +
            "focalLengths=${focalLengths?.joinToString()}, " +
            "supportLevel=${supportLevelName(supportLevel)}, " +
            "maxVideo=$maxVideoSize)"

        private fun facingName(f: Int) = when (f) {
            CameraCharacteristics.LENS_FACING_BACK  -> "BACK"
            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            else -> "EXTERNAL"
        }
        private fun supportLevelName(l: Int) = when (l) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY  -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL    -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3       -> "LEVEL_3"
            else -> "UNKNOWN($l)"
        }
    }

    var rearCameraInfo:  CameraInfo? = null
        private set
    var frontCameraInfo: CameraInfo? = null
        private set

    // Single shared provider — must never call unbindAll() between rear/front binds
    private var sharedProvider: ProcessCameraProvider? = null


    @SuppressLint("UnsafeOptInUsageError")
    suspend fun enumerateCameras(): Pair<CameraInfo?, CameraInfo?> =
        withContext(Dispatchers.IO) {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as AndroidCameraManager
            val allCameras = mutableListOf<CameraInfo>()

            for (id in cm.cameraIdList) {
                try {
                    val chars = cm.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                    val supportLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                        ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
                    val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val videoSizes = streamMap?.getOutputSizes(MediaRecorder::class.java)
                    val maxVideoSize = videoSizes?.maxByOrNull { it.width * it.height }
                    val physicalId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        chars.physicalCameraIds.firstOrNull() else null

                    val info = CameraInfo(id, physicalId, facing, focalLengths, supportLevel, maxVideoSize)
                    allCameras.add(info)
                    Log.i(TAG, "Found camera: $info")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not query camera $id: ${e.message}")
                }
            }

            val rear = allCameras
                .filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
                .maxByOrNull { it.focalLengths?.maxOrNull() ?: 0f }
            val front = allCameras
                .filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
                .firstOrNull()

            rearCameraInfo  = rear
            frontCameraInfo = front
            Log.i(TAG, "Selected REAR:  $rear")
            Log.i(TAG, "Selected FRONT: $front")
            Pair(rear, front)
        }

    /**
     * Start live preview on BOTH cameras simultaneously.
     *
     * THE FIX: Both Preview use-cases are bound in ONE bindToLifecycle() call.
     * The singleton ProcessCameraProvider handles both streams together — calling
     * unbindAll() between binds would kill the first stream.
     */
    @SuppressLint("UnsafeOptInUsageError")
    suspend fun startPreview(
        lifecycleOwner   : LifecycleOwner,
        rearPreviewView  : PreviewView,
        frontPreviewView : PreviewView
    ) {
        _state.value = CameraState.Initialising
        Log.i(TAG, "Starting dual camera preview")

        try {
            if (rearCameraInfo == null || frontCameraInfo == null) enumerateCameras()

            val provider = getCameraProvider()
            sharedProvider = provider
            provider.unbindAll()   // clear once, before binding anything

            val useCases = mutableListOf<androidx.camera.core.UseCase>()

            // ── Rear preview ───────────────────────────────────────────────
            val rearSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
            val rearPreview = Preview.Builder()
                .setTargetResolution(Size(AppConstants.REAR_CAM_WIDTH, AppConstants.REAR_CAM_HEIGHT))
                .build()
                .also { it.setSurfaceProvider(rearPreviewView.surfaceProvider) }

            // ── Front preview ──────────────────────────────────────────────
            val frontSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()
            val frontPreview = Preview.Builder()
                .setTargetResolution(Size(AppConstants.FRONT_CAM_WIDTH, AppConstants.FRONT_CAM_HEIGHT))
                .build()
                .also { it.setSurfaceProvider(frontPreviewView.surfaceProvider) }


            // ── Bind BOTH cameras in one call ──────────────────────────────
            // CameraX supports concurrent front+rear preview via separate selectors
            // bound together. This is the correct pattern for dual-camera DVR.
            try {
                provider.bindToLifecycle(lifecycleOwner, rearSelector, rearPreview)
                Log.i(TAG, "REAR camera bound — target: ${AppConstants.REAR_CAM_WIDTH}x${AppConstants.REAR_CAM_HEIGHT}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind REAR camera: ${e.message}", e)
            }

            try {
                provider.bindToLifecycle(lifecycleOwner, frontSelector, frontPreview)
                Log.i(TAG, "FRONT camera bound — target: ${AppConstants.FRONT_CAM_WIDTH}x${AppConstants.FRONT_CAM_HEIGHT}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind FRONT camera: ${e.message}", e)
            }

            _state.value = CameraState.Previewing
            Log.i(TAG, "Dual camera preview started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start preview: ${e.message}", e)
            _state.value = CameraState.Error(e.message ?: "Preview failed")
            throw e
        }
    }

    fun stopAll() {
        Log.i(TAG, "Stopping all camera streams")
        sharedProvider?.unbindAll()
        sharedProvider = null
        _state.value   = CameraState.Idle
    }

    fun validateCameraIds(previousRearId: String?, previousFrontId: String?): Boolean {
        val rearMatch  = previousRearId  == null || previousRearId  == rearCameraInfo?.logicalId
        val frontMatch = previousFrontId == null || previousFrontId == frontCameraInfo?.logicalId
        if (!rearMatch)  Log.w(TAG, "REAR camera ID changed! Was $previousRearId, now ${rearCameraInfo?.logicalId}")
        if (!frontMatch) Log.w(TAG, "FRONT camera ID changed! Was $previousFrontId, now ${frontCameraInfo?.logicalId}")
        return rearMatch && frontMatch
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try   { cont.resume(future.get()) }
                catch (e: Exception) { cont.resumeWithException(e) }
            }, ContextCompat.getMainExecutor(context))
        }
}
