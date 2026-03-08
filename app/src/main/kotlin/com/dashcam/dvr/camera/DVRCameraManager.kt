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
 * DVRCameraManager — Dual concurrent camera pipeline (CameraX 1.4.x)
 *
 * STRATEGY: Directly attempt ConcurrentCamera bind with DEFAULT_BACK + DEFAULT_FRONT.
 * We deliberately skip getAvailableConcurrentCameraInfos() because:
 *   (a) It's @RequiresOptIn and causes compiler errors even with @SuppressLint
 *   (b) Xiaomi/HyperOS devices return empty list even when hardware supports dual-cam
 * The bindToLifecycle(List<SingleCameraConfig>) call is the ground truth —
 * if hardware supports it, it works; if not, the catch block falls back to rear-only.
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
    private var sharedProvider: ProcessCameraProvider? = null


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


    private fun bindDualCameras(
        provider       : ProcessCameraProvider,
        lifecycleOwner : LifecycleOwner,
        rearView       : PreviewView,
        frontView      : PreviewView
    ) {
        // Concurrent mode: CameraX caps each stream at 720p.
        // We use DEFAULT_BACK and DEFAULT_FRONT — no getAvailableConcurrentCameraInfos()
        // needed. That API is @RequiresOptIn and Xiaomi returns empty anyway.
        val rearPreview = Preview.Builder()
            .setResolutionSelector(resolutionSelector(1280, 720))
            .build().also { it.setSurfaceProvider(rearView.surfaceProvider) }

        val frontPreview = Preview.Builder()
            .setResolutionSelector(resolutionSelector(
                AppConstants.FRONT_CAM_WIDTH, AppConstants.FRONT_CAM_HEIGHT))
            .build().also { it.setSurfaceProvider(frontView.surfaceProvider) }

        try {
            val primary = ConcurrentCamera.SingleCameraConfig(
                CameraSelector.DEFAULT_BACK_CAMERA,
                UseCaseGroup.Builder().addUseCase(rearPreview).build(),
                lifecycleOwner
            )
            val secondary = ConcurrentCamera.SingleCameraConfig(
                CameraSelector.DEFAULT_FRONT_CAMERA,
                UseCaseGroup.Builder().addUseCase(frontPreview).build(),
                lifecycleOwner
            )
            // bindToLifecycle(List<SingleCameraConfig>) opens both sessions atomically
            provider.bindToLifecycle(listOf(primary, secondary))
            Log.i(TAG, "Dual concurrent bind SUCCESS")
            _state.value = CameraState.Previewing

        } catch (e: Exception) {
            // Hardware truly cannot run both simultaneously — rear-only fallback
            Log.e(TAG, "Dual bind failed (${e.message}) — falling back to rear-only")
            provider.unbindAll()
            val rearOnly = Preview.Builder()
                .setResolutionSelector(resolutionSelector(
                    AppConstants.REAR_CAM_WIDTH, AppConstants.REAR_CAM_HEIGHT))
                .build().also { it.setSurfaceProvider(rearView.surfaceProvider) }
            provider.bindToLifecycle(lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA, rearOnly)
            Log.w(TAG, "REAR-only mode active")
            _state.value = CameraState.Previewing
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
