package com.example.camera2study

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.camera2study.util.CameraUtils
import com.example.camera2study.util.LensInfo
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCamera2Interop::class)
class CameraController(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewUseCase: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null

    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var targetBackCameraId: String? = null

    private var awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO
    private var exposureTimeNs: Long? = null
    private var aperture: Float? = null
    private var manualWbGains: FloatArray? = null

    // --- 신규 설정값 ---
    var zoomRatio: Float = 1.0f
        private set

    var videoQuality: Quality = Quality.HD
        set(value) {
            if (field != value) {
                field = value
                if (!isRecording() && cameraProvider != null) {
                    bindUseCases()
                }
            }
        }

    var maxDurationMs: Long = 0L // 0이면 무제한
    var maxRepeatCount: Int = 1  // 기본 1회 (반복 없음)
    var currentRepeatCount: Int = 0
        private set
    var isFileSizeOptimizationEnabled: Boolean = false

    // 타이머 핸들러 (최대 녹화 시간 제어용)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoStopRunnable = Runnable {
        isAutoStopping = true
        stopRecording()
    }
    private var isAutoStopping = false // 최대 시간에 의해 중지되었는지 여부

    var onRecordingEvent: ((VideoRecordEvent) -> Unit)? = null
    var onCameraChanged: ((String?) -> Unit)? = null
    var onZoomChanged: ((Float, Float) -> Unit)? = null // current, max

    val backLenses: List<LensInfo> by lazy { CameraUtils.listBackLenses(context) }

    fun init(
        owner: LifecycleOwner,
        onReady: () -> Unit = {}
    ) {
        lifecycleOwner = owner
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
            if (targetBackCameraId == null) {
                targetBackCameraId = backLenses.firstOrNull { it.isWide }?.cameraId
                    ?: backLenses.firstOrNull()?.cameraId
            }
            bindUseCases()
            onReady()
        }, ContextCompat.getMainExecutor(context))
    }

    fun attachPreview(view: PreviewView) {
        previewView = view
        previewUseCase?.setSurfaceProvider(view.surfaceProvider)
        updateZoomBounds()
    }

    fun detachPreview() {
        previewUseCase?.setSurfaceProvider(null)
        previewView = null
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return

        if (isRecording()) return

        provider.unbindAll()

        val previewBuilder = Preview.Builder()
        val preview = previewBuilder.build()
        previewUseCase = preview

        previewView?.let {
            preview.setSurfaceProvider(it.surfaceProvider)
        }

        // Recorder Builder 설정
        val recorderBuilder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(videoQuality))
        
        val recorder = recorderBuilder.build()
        val videoUseCase = VideoCapture.withOutput(recorder)

        val selector = buildSelector()
        try {
            camera = provider.bindToLifecycle(owner, selector, preview, videoUseCase)
            videoCapture = videoUseCase

            applyOptionsLive()
            updateZoomBounds()

            val activeId = camera?.let { Camera2CameraInfo.from(it.cameraInfo).cameraId }
            onCameraChanged?.invoke(activeId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildSelector(): CameraSelector {
        val builder = CameraSelector.Builder().requireLensFacing(lensFacing)
        val targetId = targetBackCameraId
        if (lensFacing == CameraSelector.LENS_FACING_BACK && targetId != null) {
            builder.addCameraFilter { cameras ->
                cameras.filter { Camera2CameraInfo.from(it).cameraId == targetId }
                    .ifEmpty { cameras }
            }
        }
        return builder.build()
    }

    private fun applyOptionsLive() {
        val cam = camera ?: return
        val control = Camera2CameraControl.from(cam.cameraControl)
        val builder = CaptureRequestOptions.Builder()
        
        // AWB 모드
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)

        if (awbMode == CameraMetadata.CONTROL_AWB_MODE_OFF) {
            manualWbGains?.let { gains ->
                builder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_GAINS,
                    android.hardware.camera2.params.RggbChannelVector(
                        gains[0], gains[1], gains[2], gains[3]
                    )
                )
            }
        }

        // 수동 노출
        val expNs = exposureTimeNs
        if (expNs != null) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_OFF
            )
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_ON
            )
        }

        // 조리개 수동 조정
        aperture?.let {
            builder.setCaptureRequestOption(CaptureRequest.LENS_APERTURE, it)
        }

        // 자동 초점 모드 강제 주입 (상시 연속 자동초점 보장)
        builder.setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_MODE,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        )

        control.setCaptureRequestOptions(builder.build())
    }

    // 줌 제어 API
    fun setZoomRatio(ratio: Float) {
        val cam = camera ?: return
        val zoomState = cam.cameraInfo.zoomState.value ?: return
        val clamped = ratio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        cam.cameraControl.setZoomRatio(clamped)
        zoomRatio = clamped
        onZoomChanged?.invoke(clamped, zoomState.maxZoomRatio)
    }

    fun getMinZoomRatio(): Float {
        return camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1.0f
    }

    fun getMaxZoomRatio(): Float {
        return camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 8.0f
    }

    private fun updateZoomBounds() {
        val cam = camera ?: return
        val zoomState = cam.cameraInfo.zoomState.value ?: return
        zoomRatio = zoomState.zoomRatio
        onZoomChanged?.invoke(zoomRatio, zoomState.maxZoomRatio)
    }

    // 원터치 초점 (Tap to Focus)
    fun tapToFocus(x: Float, y: Float) {
        val cam = camera ?: return
        val view = previewView ?: return
        val factory = view.meteringPointFactory
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS) // 3초 후 연속 초점으로 복귀
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun switchFacing() {
        if (isRecording()) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            targetBackCameraId = backLenses.firstOrNull { it.isWide }?.cameraId
                ?: backLenses.firstOrNull()?.cameraId
        } else {
            targetBackCameraId = null
        }
        bindUseCases()
    }

    fun selectBackLens(cameraId: String) {
        if (isRecording()) return
        lensFacing = CameraSelector.LENS_FACING_BACK
        targetBackCameraId = cameraId
        bindUseCases()
    }

    fun activeCameraId(): String? =
        camera?.let { Camera2CameraInfo.from(it.cameraInfo).cameraId }

    fun isFacingBack(): Boolean = lensFacing == CameraSelector.LENS_FACING_BACK

    fun setAwbMode(mode: Int) {
        awbMode = mode
        applyOptionsLive()
    }

    fun setManualWbGains(gains: FloatArray?) {
        manualWbGains = gains
        applyOptionsLive()
    }

    fun setExposureTime(ns: Long?) {
        exposureTimeNs = ns
        applyOptionsLive()
    }

    fun setAperture(value: Float?) {
        aperture = value
        applyOptionsLive()
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        val vc = videoCapture ?: return
        if (recording != null) return

        val name = "Camera2Study_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Camera2Study")
        }
        val output = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        isAutoStopping = false

        recording = vc.output
            .prepareRecording(context, output)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                onRecordingEvent?.invoke(event)
                
                if (event is VideoRecordEvent.Start) {
                    if (maxDurationMs > 0) {
                        mainHandler.removeCallbacks(autoStopRunnable)
                        mainHandler.postDelayed(autoStopRunnable, maxDurationMs)
                    }
                }
                
                if (event is VideoRecordEvent.Finalize) {
                    mainHandler.removeCallbacks(autoStopRunnable)
                    recording = null

                    val wasAutoStop = isAutoStopping
                    isAutoStopping = false
                    
                    if (wasAutoStop && !event.hasError()) {
                        currentRepeatCount++
                        if (currentRepeatCount < maxRepeatCount) {
                            mainHandler.postDelayed({
                                startRecording()
                            }, 500L)
                        } else {
                            currentRepeatCount = 0
                        }
                    } else {
                        currentRepeatCount = 0
                    }
                }
            }
    }

    fun resetRepeatCount() {
        currentRepeatCount = 0
    }

    fun stopRecording() {
        recording?.stop()
        recording = null
        mainHandler.removeCallbacks(autoStopRunnable)
    }

    fun isRecording(): Boolean = recording != null

    fun release() {
        recording?.stop()
        recording = null
        mainHandler.removeCallbacks(autoStopRunnable)
        cameraProvider?.unbindAll()
        camera = null
        videoCapture = null
    }
}
