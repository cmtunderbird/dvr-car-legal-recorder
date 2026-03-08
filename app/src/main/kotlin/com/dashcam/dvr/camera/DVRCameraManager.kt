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
 * CameraX 1.3.x dual-camera strategy:
 * - Call unbindAll() ONCE before any binding
 * - Bind rear then front WITHOUT unbindAll() between them
 * - CameraX accumulates use cases per lifecycle — the second bind does NOT
 *   replace the first as long as we don't call unbindAll() in between
 */
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
            "CameraInfo(logical=$logicalId, physical=$physicalId, " +
            "facing=${facingName(facing)}, focalLengths=${focalLengths?.joinToString()}, " +
            "level=${supportLevelName(supportLevel)}, maxVideo=$maxVideoSize)"
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

    var rearCameraInfo:  CameraInfo? = null; private set
    var frontCameraInfo: CameraInfo? = null; private set
    private var sharedProvider: ProcessCameraProvider? = null


    @SuppressLint("UnsafeOptInUsageError")
    suspend fun enumerateCameras(): Pair<CameraInfo?, CameraInfo?> =
        withContext(Dispatchers.IO) {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as AndroidCameraManager
            val all = mutableListOf<CameraInfo>()
            for (id in cm.cameraIdList) {
                try {
                    val c = cm.getCameraCharacteristics(id)
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
            Log.i(TAG, "REAR:  $rearCameraInfo")
            Log.i(TAG, "FRONT: $frontCameraInfo")
            Pair(rearCameraInfo, frontCameraInfo)
        }

    /**
     * Bind rear AND front preview in CameraX 1.3.x.
     *
     * Key rule: call unbindAll() ONCE, then bind rear, then bind front.
     * Never call unbindAll() between the two binds — CameraX accumulates
     * use cases per lifecycle; a second bind without unbind ADDS the use case,
     * it does not replace the first.
     */
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
            provider.unbindAll()          // ← ONE clear, before anything is bound

            val rearPreview = Preview.Builder()
                .setTargetResolution(Size(AppConstants.REAR_CAM_WIDTH, AppConstants.REAR_CAM_HEIGHT))
                .build().also { it.setSurfaceProvider(rearPreviewView.surfaceProvider) }

            val frontPreview = Preview.Builder()
                .setTargetResolution(Size(AppConstants.FRONT_CAM_WIDTH, AppConstants.FRONT_CAM_HEIGHT))
                .build().also { it.setSurfaceProvider(frontPreviewView.surfaceProvider) }

            // Bind rear — no unbindAll after this
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, rearPreview)
            Log.i(TAG, "REAR bound ✅")

            // Bind front — accumulates alongside rear, does not replace it
            try {
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, frontPreview)
                Log.i(TAG, "FRONT bound ✅")
            } catch (e: Exception) {
                Log.w(TAG, "FRONT bind failed (device may not support concurrent): ${e.message}")
            }

            _state.value = CameraState.Previewing
            Log.i(TAG, "Dual preview started ✅")
        } catch (e: Exception) {
            Log.e(TAG, "Preview failed: ${e.message}", e)
            _state.value = CameraState.Error(e.message ?: "Preview failed")
            throw e
        }
    }

    fun stopAll() {
        sharedProvider?.unbindAll()
        sharedProvider = null
        _state.value = CameraState.Idle
    }

    fun validateCameraIds(prevRearId: String?, prevFrontId: String?): Boolean {
        val rm = prevRearId  == null || prevRearId  == rearCameraInfo?.logicalId
        val fm = prevFrontId == null || prevFrontId == frontCameraInfo?.logicalId
        if (!rm) Log.w(TAG, "REAR ID changed: $prevRearId → ${rearCameraInfo?.logicalId}")
        if (!fm) Log.w(TAG, "FRONT ID changed: $prevFrontId → ${frontCameraInfo?.logicalId}")
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
