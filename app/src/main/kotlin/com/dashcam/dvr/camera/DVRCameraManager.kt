package com.dashcam.dvr.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager as AndroidCameraManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
 * DVRCameraManager — Dual Camera Pipeline (CameraX 1.3.x)
 *
 * FRONT CAMERA FIX:
 * Using DEFAULT_FRONT_CAMERA / DEFAULT_BACK_CAMERA replaces the active camera
 * session on each bindToLifecycle call — so rear always won, front was always black.
 *
 * Fix: Build an explicit CameraSelector per logical camera ID using Camera2CameraInfo.
 * CameraX then opens each camera as a distinct session, both run concurrently.
 *
 * Both Preview instances use ResolutionSelector (deprecated setTargetResolution removed).
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
            "focal=${focalLengths?.joinToString()}, level=${supportLevelName(supportLevel)}, " +
            "maxVideo=$maxVideoSize)"
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
    private var sharedProvider: ProcessCameraProvider? = null


    @SuppressLint("UnsafeOptInUsageError")
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

    /**
     * Build a CameraSelector that matches one specific logical camera ID.
     *
     * Using DEFAULT_BACK/DEFAULT_FRONT replaces the active CameraX session on each
     * bindToLifecycle call — the second bind kills the first. Using Camera2CameraInfo
     * to filter by exact ID lets CameraX keep both sessions open simultaneously.
     */
    private fun selectorForId(cameraId: String): CameraSelector =
        CameraSelector.Builder()
            .addCameraFilter { cameras ->
                cameras.filter { Camera2CameraInfo.from(it).getCameraId() == cameraId }
            }
            .build()

    private fun resolutionSelector(w: Int, h: Int) =
        ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(w, h),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()


    suspend fun startPreview(
        lifecycleOwner   : LifecycleOwner,
        rearPreviewView  : PreviewView,
        frontPreviewView : PreviewView
    ) {
        _state.value = CameraState.Initialising
        try {
            if (rearCameraInfo == null || frontCameraInfo == null) enumerateCameras()

            val rearId  = rearCameraInfo?.logicalId
                ?: throw IllegalStateException("No rear camera found")
            val frontId = frontCameraInfo?.logicalId
                ?: throw IllegalStateException("No front camera found")

            val provider = getCameraProvider()
            sharedProvider = provider
            provider.unbindAll()

            // ── Rear camera ─────────────────────────────────────────────────
            val rearPreview = Preview.Builder()
                .setResolutionSelector(resolutionSelector(AppConstants.REAR_CAM_WIDTH, AppConstants.REAR_CAM_HEIGHT))
                .build()
                .also { it.setSurfaceProvider(rearPreviewView.surfaceProvider) }

            provider.bindToLifecycle(lifecycleOwner, selectorForId(rearId), rearPreview)
            Log.i(TAG, "REAR  bound ✅ (id=$rearId)")

            // ── Front camera ─────────────────────────────────────────────────
            // Explicit ID selector — does NOT replace the rear session above.
            val frontPreview = Preview.Builder()
                .setResolutionSelector(resolutionSelector(AppConstants.FRONT_CAM_WIDTH, AppConstants.FRONT_CAM_HEIGHT))
                .build()
                .also { it.setSurfaceProvider(frontPreviewView.surfaceProvider) }

            try {
                provider.bindToLifecycle(lifecycleOwner, selectorForId(frontId), frontPreview)
                Log.i(TAG, "FRONT bound ✅ (id=$frontId)")
            } catch (e: Exception) {
                Log.w(TAG, "FRONT bind failed — device may not support concurrent preview: ${e.message}")
            }

            _state.value = CameraState.Previewing
            Log.i(TAG, "Dual preview running ✅")

        } catch (e: Exception) {
            Log.e(TAG, "startPreview failed: ${e.message}", e)
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



