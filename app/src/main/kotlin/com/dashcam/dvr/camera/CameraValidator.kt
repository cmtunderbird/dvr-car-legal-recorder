package com.dashcam.dvr.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * CameraValidator
 *
 * Runs at session start to validate the device camera capabilities
 * against the Blueprint requirements. Results are logged and shown
 * to the user in the Setup Wizard if any capability is missing.
 *
 * Blueprint §3: Physical camera ID binding & capability validation.
 */
class CameraValidator(private val context: Context) {

    companion object {
        private const val TAG = "CameraValidator"
    }

    data class ValidationResult(
        val rearCameraFound      : Boolean,
        val frontCameraFound     : Boolean,
        val rearSupportsFullLevel: Boolean,
        val rearSupports1080p    : Boolean,
        val frontSupports720p    : Boolean,
        val hasDualCameras       : Boolean,
        val warnings             : List<String>
    ) {
        val isViable: Boolean get() = rearCameraFound && rearSupports1080p
        val summary: String get() = buildString {
            appendLine("=== Camera Validation ===")
            appendLine("Rear camera:        ${if (rearCameraFound) "✅" else "❌"}")
            appendLine("Front camera:       ${if (frontCameraFound) "✅" else "⚠️ (optional)"}")
            appendLine("Rear FULL level:    ${if (rearSupportsFullLevel) "✅" else "⚠️ LIMITED"}")
            appendLine("Rear 1080p:         ${if (rearSupports1080p) "✅" else "❌"}")
            appendLine("Front 720p:         ${if (frontSupports720p) "✅" else "⚠️"}")
            appendLine("Dual cameras:       ${if (hasDualCameras) "✅" else "⚠️"}")
            if (warnings.isNotEmpty()) {
                appendLine("Warnings:")
                warnings.forEach { appendLine("  ⚠️ $it") }
            }
        }
    }

    fun validate(): ValidationResult {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val warnings = mutableListOf<String>()

        var rearFound       = false
        var frontFound      = false
        var rearFullLevel   = false
        var rear1080p       = false
        var front720p       = false

        for (id in cm.cameraIdList) {
            try {
                val chars = cm.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                val level  = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
                val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = streamMap?.getOutputSizes(android.media.MediaRecorder::class.java)
                    ?: emptyArray()

                when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> {
                        rearFound = true
                        rearFullLevel = level >= CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL
                        rear1080p = sizes.any { it.width >= 1920 && it.height >= 1080 }

                        if (!rearFullLevel) {
                            warnings.add("Rear camera is LEGACY/LIMITED level — some features may be restricted")
                        }
                        if (!rear1080p) {
                            warnings.add("Rear camera does not support 1080p video recording")
                        }
                    }
                    CameraCharacteristics.LENS_FACING_FRONT -> {
                        frontFound = true
                        front720p = sizes.any { it.width >= 1280 && it.height >= 720 }
                        if (!front720p) {
                            warnings.add("Front camera does not support 720p — will use max available size")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not validate camera $id: ${e.message}")
            }
        }

        if (!frontFound) {
            warnings.add("No front camera found — front stream will be unavailable")
        }

        val result = ValidationResult(
            rearCameraFound       = rearFound,
            frontCameraFound      = frontFound,
            rearSupportsFullLevel = rearFullLevel,
            rearSupports1080p     = rear1080p,
            frontSupports720p     = front720p,
            hasDualCameras        = rearFound && frontFound,
            warnings              = warnings
        )

        Log.i(TAG, result.summary)
        return result
    }
}
