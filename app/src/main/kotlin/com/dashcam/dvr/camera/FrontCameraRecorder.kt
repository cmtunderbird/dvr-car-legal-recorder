package com.dashcam.dvr.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
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
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.dashcam.dvr.util.AppConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

@SuppressLint("MissingPermission")
class FrontCameraRecorder(private val context: Context) {

    companion object { private const val TAG = "FrontCameraRecorder" }

    enum class State { CLOSED, PREVIEW, RECORDING, ERROR }

    private val _state = MutableStateFlow(State.CLOSED)
    val state: StateFlow<State> = _state

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraId:        String?               = null
    private var cameraDevice:    CameraDevice?         = null
    private var captureSession:  CameraCaptureSession? = null
    private var mediaRecorder:   MediaRecorder?        = null
    private var previewSurface:  Surface?              = null
    private var recorderSurface: Surface?              = null
    private var currentFile:     File?                 = null
    private var textureViewRef:  TextureView?          = null

    private var backgroundThread:  HandlerThread? = null
    private var backgroundHandler: Handler?       = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val previewSize = Size(AppConstants.FRONT_CAM_WIDTH, AppConstants.FRONT_CAM_HEIGHT)

    fun open(textureView: TextureView) {
        cameraId = findFrontCameraId() ?: run {
            Log.e(TAG, "No front camera found"); _state.value = State.ERROR; return
        }
        textureViewRef = textureView
        startBackgroundThread()
        if (textureView.isAvailable) {
            configureTransform(textureView)
            openCamera(textureView.surfaceTexture!!)
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    configureTransform(textureView); openCamera(st)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                    configureTransform(textureView)
                }
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    /**
     * Apply corrective rotation + fill-scale matrix to the TextureView.
     *
     * Camera2 buffers arrive in raw sensor coordinates; the OS does NOT rotate them.
     * correctionDeg = (sensorOrientation - deviceDeg + 360) % 360
     *
     * Redmi Note 14 Pro front sensor orientation = 270°.
     * App is always landscape but the sensor reference is portrait (deviceDeg = 0).
     *   correctionDeg = (270 - 0 + 360) % 360 = 270  → hits the 90/270 branch
     *   postRotate(270°) = 90° CW  ✓
     *
     * deviceDeg = 0 (not 90) because on this device the TextureView coordinate
     * space already accounts for the display orientation; we only need to correct
     * for the sensor's own mounting angle relative to the natural device axis.
     */
    private fun configureTransform(textureView: TextureView) {
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        if (vw == 0f || vh == 0f) { textureView.post { configureTransform(textureView) }; return }

        val sensorOrientation = try {
            cameraManager.getCameraCharacteristics(cameraId!!)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
        } catch (e: Exception) { 270 }

        Log.i(TAG, "Front transform: sensor=$sensorOrientation  view=${vw.toInt()}x${vh.toInt()}")

        val cx = vw / 2f;  val cy = vh / 2f
        val bw = previewSize.width.toFloat()   // 1280
        val bh = previewSize.height.toFloat()  // 720

        // deviceDeg = 0 → correctionDeg = 270 → 90° CW (confirmed on Redmi Note 14 Pro)
        val deviceDeg = 0
        val correctionDeg = ((sensorOrientation - deviceDeg) + 360) % 360
        Log.i(TAG, "Front transform: correctionDeg=$correctionDeg")

        val matrix = Matrix()
        when (correctionDeg) {
            90, 270 -> {
                matrix.postRotate(correctionDeg.toFloat(), cx, cy)
                // After 90°/270° rotation the effective dims swap: bh×bw
                val scale = maxOf(vw / bh, vh / bw)
                matrix.postScale(scale, scale, cx, cy)
            }
            180 -> {
                matrix.postRotate(180f, cx, cy)
                val scale = maxOf(vw / bw, vh / bh)
                matrix.postScale(scale, scale, cx, cy)
            }
            else -> {  // 0°
                val scale = maxOf(vw / bw, vh / bh)
                matrix.postScale(scale, scale, cx, cy)
            }
        }
        textureView.setTransform(matrix)
        Log.i(TAG, "Front transform applied")
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
                    textureViewRef?.let { tv -> mainHandler.post { configureTransform(tv) } }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); cameraDevice = null
                    _state.value = State.ERROR
                    Log.e(TAG, "Front camera error: $error")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "openCamera failed: ${e.message}", e); _state.value = State.ERROR
        }
    }

    private fun startPreviewSession() {
        val device = cameraDevice ?: return; val surface = previewSurface ?: return
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
                    _state.value = State.ERROR; Log.e(TAG, "Front preview configure failed")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) { Log.e(TAG, "startPreviewSession: ${e.message}", e) }
    }

    fun startRecording(outputFile: File): Boolean {
        val device = cameraDevice ?: run { Log.e(TAG, "startRecording: camera not open"); return false }
        val prevSurface = previewSurface ?: run { Log.e(TAG, "startRecording: no preview surface"); return false }
        currentFile = outputFile
        captureSession?.close(); captureSession = null
        val recorder = buildMediaRecorder(outputFile) ?: return false
        mediaRecorder = recorder; recorderSurface = recorder.surface
        try {
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(prevSurface, recorderSurface!!),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(prevSurface); addTarget(recorderSurface!!)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }.build()
                        session.setRepeatingRequest(req, null, backgroundHandler)
                        recorder.start(); _state.value = State.RECORDING
                        Log.i(TAG, "Front REC started → ${outputFile.name}")
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        _state.value = State.ERROR; Log.e(TAG, "Front record configure failed")
                    }
                }, backgroundHandler)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed: ${e.message}", e)
            mediaRecorder?.release(); mediaRecorder = null; recorderSurface = null; return false
        }
    }

    fun stopRecording() {
        if (_state.value != State.RECORDING) return
        try {
            captureSession?.stopRepeating(); captureSession?.close(); captureSession = null
            mediaRecorder?.stop()
            Log.i(TAG, "Front REC stopped → ${currentFile?.length()?.div(1024)} KB")
        } catch (e: Exception) { Log.w(TAG, "stopRecording: ${e.message}") }
        finally {
            mediaRecorder?.release(); mediaRecorder = null
            recorderSurface?.release(); recorderSurface = null; currentFile = null
        }
        startPreviewSession()
        textureViewRef?.let { tv -> mainHandler.post { configureTransform(tv) } }
    }

    fun close() {
        try {
            captureSession?.close(); cameraDevice?.close(); previewSurface?.release()
            mediaRecorder?.release(); recorderSurface?.release()
        } catch (e: Exception) { Log.w(TAG, "close: ${e.message}") }
        captureSession = null; cameraDevice = null; previewSurface = null
        mediaRecorder = null; recorderSurface = null; textureViewRef = null
        stopBackgroundThread(); _state.value = State.CLOSED
        Log.i(TAG, "Front camera closed")
    }

    private fun buildMediaRecorder(outputFile: File): MediaRecorder? = try {
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
            setVideoEncodingBitRate(AppConstants.FRONT_CAM_BITRATE_BPS)
            setOrientationHint(270)
            prepare()
        }
    } catch (e: Exception) { Log.e(TAG, "buildMediaRecorder failed: ${e.message}", e); null }

    private fun findFrontCameraId(): String? {
        for (id in cameraManager.cameraIdList) {
            val f = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (f == CameraCharacteristics.LENS_FACING_FRONT) return id
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
        backgroundThread = null; backgroundHandler = null
    }
}
