package com.dashcam.dvr.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
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
import android.view.WindowManager
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

    // Camera native frame size: 1280×720 landscape (16:9)
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
     * Camera2Basic-style configureTransform.
     *
     * Problem: TextureView silently stretches the raw sensor buffer (1280×720) to
     * fill whatever view size it has.  We must apply a Matrix that undoes this stretch,
     * then rotates so the image is upright, then scales back to fill the 16:9 window.
     *
     * The official Camera2Basic approach (used verbatim here):
     *   1. setRectToRect( viewRect → swapped-buffer-rect )  — maps view ↔ buffer coords,
     *      accounting for the 90°/270° dimension swap.
     *   2. postScale to fill            — scaled so the shorter buffer axis fills the view.
     *   3. postRotate by display angle  — rotates the corrected content upright.
     *
     * For ROTATION_90 (landscape, display rotated 90° from natural):
     *   postRotate( 90 × (1-2) ) = postRotate(-90°) = 270° CCW = 90° CW  ✓
     *   This is consistent with the orientation fix confirmed on Redmi Note 14 Pro.
     *
     * The TextureView in the layout is constrained to 16:9 (H,16:9 dimensionRatio),
     * so the view is always wider than tall — matching the camera's native 16:9 frame.
     * The setRectToRect + scale fills it exactly with no black bars.
     */
    @Suppress("DEPRECATION")
    private fun configureTransform(textureView: TextureView) {
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        if (vw == 0f || vh == 0f) { textureView.post { configureTransform(textureView) }; return }

        val displayRotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.rotation   // ROTATION_0=0, ROTATION_90=1, ROTATION_270=3

        val bw = previewSize.width.toFloat()    // 1280
        val bh = previewSize.height.toFloat()   // 720
        val cx = vw / 2f;  val cy = vh / 2f

        Log.i(TAG, "Front transform: displayRotation=$displayRotation  view=${vw.toInt()}x${vh.toInt()}")

        val matrix = Matrix()
        when (displayRotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                // Device is landscape — Camera2Basic setRectToRect approach.
                // bufferRect uses swapped dims (bh×bw) because the sensor buffer will be
                // rotated 90°/270° relative to the view coordinate space.
                val bufferRect = RectF(0f, 0f, bh, bw)
                bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
                matrix.setRectToRect(RectF(0f, 0f, vw, vh), bufferRect, Matrix.ScaleToFit.FILL)
                // Scale so the shorter axis fills the view (fill, no black bars)
                val scale = maxOf(vh / bh, vw / bw)
                matrix.postScale(scale, scale, cx, cy)
                // Rotate to correct orientation (for ROTATION_90: -90° = 270° CW = 90° CW ✓)
                matrix.postRotate(90f * (displayRotation - 2), cx, cy)
            }
            Surface.ROTATION_180 -> {
                matrix.postRotate(180f, cx, cy)
            }
            // ROTATION_0 (portrait): no transform needed
        }
        textureView.setTransform(matrix)
        Log.i(TAG, "Front transform applied: displayRotation=$displayRotation  postRotateDeg=${90*(displayRotation-2)}")
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
