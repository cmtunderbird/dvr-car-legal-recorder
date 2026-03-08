package com.dashcam.dvr.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.dashcam.dvr.util.AppConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * FrontCameraRecorder — Camera2 + MediaRecorder
 *
 * Operates completely independently of CameraX. This lets the front camera
 * open and record while CameraX holds the rear camera with VideoCapture.
 *
 * Lifecycle:
 *   1. open(textureView)       — starts preview (call once texture is available)
 *   2. startRecording(file)    — begins MediaRecorder to front_camera.mp4
 *   3. stopRecording()         — finalises the file
 *   4. close()                 — releases camera + thread (call in onDestroy)
 */
@SuppressLint("MissingPermission")
class FrontCameraRecorder(private val context: Context) {

    companion object { private const val TAG = "FrontCameraRecorder" }

    enum class State { CLOSED, PREVIEW, RECORDING, ERROR }

    private val _state = MutableStateFlow(State.CLOSED)
    val state: StateFlow<State> = _state

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraId:      String?              = null
    private var cameraDevice:  CameraDevice?        = null
    private var captureSession:CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder?       = null
    private var previewSurface:Surface?             = null
    private var recorderSurface:Surface?            = null
    private var currentFile:   File?                = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler?      = null

    private val previewSize = Size(AppConstants.FRONT_CAM_WIDTH, AppConstants.FRONT_CAM_HEIGHT)

    // ── Open ──────────────────────────────────────────────────────────────

    fun open(textureView: TextureView) {
        cameraId = findFrontCameraId() ?: run {
            Log.e(TAG, "No front camera found")
            _state.value = State.ERROR
            return
        }
        startBackgroundThread()

        if (textureView.isAvailable) {
            openCamera(textureView.surfaceTexture!!)
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    openCamera(st)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    private fun openCamera(surfaceTexture: SurfaceTexture) {
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface = Surface(surfaceTexture)

        try {
            cameraManager.openCamera(cameraId!!, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    Log.i(TAG, "Front camera opened: $cameraId")
                    startPreviewSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); cameraDevice = null
                    Log.w(TAG, "Front camera disconnected")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); cameraDevice = null
                    _state.value = State.ERROR
                    Log.e(TAG, "Front camera error: $error")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "openCamera failed: ${e.message}", e)
            _state.value = State.ERROR
        }
    }


    // ── Preview session ───────────────────────────────────────────────────

    private fun startPreviewSession() {
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        try {
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    }.build()
                    session.setRepeatingRequest(req, null, backgroundHandler)
                    _state.value = State.PREVIEW
                    Log.i(TAG, "Front preview active")
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    _state.value = State.ERROR
                    Log.e(TAG, "Front preview session configure failed")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "startPreviewSession: ${e.message}", e)
        }
    }

    // ── Recording ─────────────────────────────────────────────────────────

    fun startRecording(outputFile: File): Boolean {
        val device = cameraDevice ?: run {
            Log.e(TAG, "startRecording: camera not open"); return false
        }
        val prevSurface = previewSurface ?: run {
            Log.e(TAG, "startRecording: no preview surface"); return false
        }
        currentFile = outputFile

        // Stop preview session before reconfiguring with recorder surface
        captureSession?.close(); captureSession = null

        val recorder = buildMediaRecorder(outputFile) ?: return false
        mediaRecorder = recorder
        recorderSurface = recorder.surface

        try {
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(prevSurface, recorderSurface!!),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(prevSurface)
                            addTarget(recorderSurface!!)
                            set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }.build()
                        session.setRepeatingRequest(req, null, backgroundHandler)
                        recorder.start()
                        _state.value = State.RECORDING
                        Log.i(TAG, "Front REC started → ${outputFile.name}")
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        _state.value = State.ERROR
                        Log.e(TAG, "Front record session configure failed")
                    }
                }, backgroundHandler)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "startRecording session failed: ${e.message}", e)
            mediaRecorder?.release(); mediaRecorder = null
            recorderSurface = null
            return false
        }
    }

    fun stopRecording() {
        if (_state.value != State.RECORDING) return
        try {
            captureSession?.stopRepeating()
            captureSession?.close(); captureSession = null
            mediaRecorder?.stop()
            Log.i(TAG, "Front REC stopped → ${currentFile?.length()?.div(1024)} KB")
        } catch (e: Exception) {
            Log.w(TAG, "stopRecording exception (file may still be valid): ${e.message}")
        } finally {
            mediaRecorder?.release(); mediaRecorder = null
            recorderSurface?.release(); recorderSurface = null
            currentFile = null
        }
        // Restart preview session so the display stays live
        startPreviewSession()
    }


    // ── Close ─────────────────────────────────────────────────────────────

    fun close() {
        try {
            captureSession?.close(); captureSession = null
            cameraDevice?.close();   cameraDevice   = null
            previewSurface?.release(); previewSurface = null
            mediaRecorder?.release(); mediaRecorder  = null
            recorderSurface?.release(); recorderSurface = null
        } catch (e: Exception) {
            Log.w(TAG, "close: ${e.message}")
        }
        stopBackgroundThread()
        _state.value = State.CLOSED
        Log.i(TAG, "Front camera closed")
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun buildMediaRecorder(outputFile: File): MediaRecorder? {
        return try {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            mr.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(outputFile.absolutePath)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoSize(AppConstants.FRONT_CAM_WIDTH, AppConstants.FRONT_CAM_HEIGHT)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(AppConstants.FRONT_CAM_BITRATE)
                setOrientationHint(270)   // front camera landscape correction
                prepare()
            }
        } catch (e: Exception) {
            Log.e(TAG, "buildMediaRecorder failed: ${e.message}", e)
            null
        }
    }

    private fun findFrontCameraId(): String? {
        for (id in cameraManager.cameraIdList) {
            val facing = cameraManager
                .getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) return id
        }
        return null
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("FrontCameraThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (_: InterruptedException) {}
        backgroundThread = null
        backgroundHandler = null
    }
}
