package com.example.camera2study

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
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
import java.io.File
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class CameraController(private val context: Context) {

    companion object {
        const val RATIO_3_4 = 0
        const val RATIO_16_9 = 1
        const val RATIO_FULL = 2
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var previewUseCase: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
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

    var targetFps: Int = 30
        set(value) {
            if (field != value) {
                field = value
                if (!isRecording() && cameraProvider != null) bindUseCases()
            }
        }

    var isHdrEnabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (!isRecording() && cameraProvider != null) bindUseCases()
            }
        }

    var isLocationTagEnabled: Boolean = false

    // --- 추가 기능 변수 ---
    var currentRatioMode: Int = RATIO_3_4 // 3:4, 16:9, Full
        set(value) {
            if (field != value) {
                field = value
                if (!isRecording() && cameraProvider != null) {
                    bindUseCases()
                }
            }
        }

    var isPrivateSave: Boolean = true // 기본 비공개 보관함 저장
    var isAudioMuted: Boolean = false // 기본 마이크 켬
    var isMuteSound: Boolean = false  // 기본 무음 꺼짐

    private var savedRingerMode: Int = -1

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
    var onFinalizeEnded: (() -> Unit)? = null

    // 사진/영상 모드 분리. 자동 모드의 AF mode 결정에 사용한다.
    var isVideoMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                applyOptionsLive()
            }
        }

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
        // 녹화 중에는 surface provider를 null로 만들지 않는다.
        // null로 만들면 백그라운드/화면 꺼짐 상태에서 CameraX 세션이 흔들려
        // 정지 프레임 파일이 저장되는 freeze 문제가 발생한다.
        if (!isRecording()) {
            previewUseCase?.setSurfaceProvider(null)
        }
        previewView = null
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return

        if (isRecording()) return

        provider.unbindAll()

        // 1. 화면 비율 결정 — 3:4는 4:3 sensor, 16:9 / Full은 16:9 sensor 출력으로 통일한다.
        // Full은 디스플레이 비율로 preview를 채우되 저장 결과는 16:9로 둬 Galaxy 기본 동작과 일치시킨다.
        val targetRatio = when (currentRatioMode) {
            RATIO_3_4 -> AspectRatio.RATIO_4_3
            else -> AspectRatio.RATIO_16_9
        }

        // Preview 빌더 — 가능하면 FPS 힌트를 capture request에 주입한다.
        val previewBuilder = Preview.Builder().setTargetAspectRatio(targetRatio)
        try {
            androidx.camera.camera2.interop.Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    android.util.Range(targetFps, targetFps)
                )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val preview = previewBuilder.build()
        previewUseCase = preview

        previewView?.let {
            preview.setSurfaceProvider(it.surfaceProvider)
        }

        // 2. ImageCapture (사진 촬영)
        val imageCaptureBuilder = ImageCapture.Builder()
            .setTargetAspectRatio(targetRatio)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        imageCapture = imageCaptureBuilder.build()

        // 3. VideoCapture
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(videoQuality))
            .build()
        val videoUseCaseBuilder = VideoCapture.Builder(recorder)
        if (isHdrEnabled) {
            // HDR 10-bit 지원 단말에서만 적용된다. 미지원이면 CameraX가 fallback 처리한다.
            try {
                videoUseCaseBuilder.setDynamicRange(
                    androidx.camera.core.DynamicRange.HDR_UNSPECIFIED_10_BIT
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        val videoUseCase = videoUseCaseBuilder.build()

        val selector = buildSelector()
        try {
            camera = provider.bindToLifecycle(owner, selector, preview, imageCapture, videoUseCase)
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

        // 사진 모드는 CONTINUOUS_PICTURE, 영상 모드는 CONTINUOUS_VIDEO로 분기한다.
        val afMode = if (isVideoMode) {
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        } else {
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, afMode)

        // 노출 자동 보정 및 안티밴딩
        if (expNs == null) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
            )
        }

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

    // --- 노출 보정 (AE) 제어 API ---
    fun getMinExposureIndex(): Int =
        camera?.cameraInfo?.exposureState?.exposureCompensationRange?.lower ?: 0

    fun getMaxExposureIndex(): Int =
        camera?.cameraInfo?.exposureState?.exposureCompensationRange?.upper ?: 0

    fun getCurrentExposureIndex(): Int =
        camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0

    fun setExposureIndex(index: Int) {
        val min = getMinExposureIndex()
        val max = getMaxExposureIndex()
        val clamped = index.coerceIn(min, max)
        camera?.cameraControl?.setExposureCompensationIndex(clamped)
    }


    // --- 촬영 무음 유틸리티 ---
    private fun muteSystemSound() {
        if (!isMuteSound) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedRingerMode = am.ringerMode
        am.ringerMode = AudioManager.RINGER_MODE_SILENT
    }

    private fun restoreSystemSound() {
        if (!isMuteSound || savedRingerMode == -1) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.ringerMode = savedRingerMode
        savedRingerMode = -1
    }

    // --- 사진 촬영 (Take Picture) ---
    fun takePicture(onSuccess: (File) -> Unit, onError: (ImageCaptureException) -> Unit) {
        val ic = imageCapture ?: return
        
        muteSystemSound()

        val name = "StudyVault_${System.currentTimeMillis()}.jpg"
        val outputFileOptions = if (isPrivateSave) {
            val privateDir = File(context.getExternalFilesDir(null), "private_vault")
            if (!privateDir.exists()) privateDir.mkdirs()
            val nomedia = File(privateDir, ".nomedia")
            if (!nomedia.exists()) nomedia.createNewFile()
            
            val file = File(privateDir, name)
            ImageCapture.OutputFileOptions.Builder(file).build()
        } else {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/StudyVault")
            }
            ImageCapture.OutputFileOptions.Builder(
                context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ).build()
        }

        ic.takePicture(
            outputFileOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    restoreSystemSound()
                    val savedFile = if (isPrivateSave) {
                        File(File(context.getExternalFilesDir(null), "private_vault"), name)
                    } else {
                        // 공용 갤러리는 임시 File 혹은 URI 정보 제공
                        File("")
                    }
                    onSuccess(savedFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    restoreSystemSound()
                    onError(exception)
                }
            }
        )
    }

    // --- 동영상 녹화 (Start Recording) ---
    @SuppressLint("MissingPermission", "InvalidWakeLockTag")
    fun startRecording() {
        val vc = videoCapture ?: return
        if (recording != null) return

        muteSystemSound()

        // 화면 꺼짐 대기 상태에서 CPU 슬립을 막기 위해 WakeLock 획득
        if (wakeLock == null) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Camera2Study::WakeLock")
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(30 * 60 * 1000L) // 최대 30분 유지
        }

        val name = "StudyVault_${System.currentTimeMillis()}.mp4"
        val prep = if (isPrivateSave) {
            val privateDir = File(context.getExternalFilesDir(null), "private_vault")
            if (!privateDir.exists()) privateDir.mkdirs()
            val nomedia = File(privateDir, ".nomedia")
            if (!nomedia.exists()) nomedia.createNewFile()
            
            val file = File(privateDir, name)
            val fileOptions = FileOutputOptions.Builder(file).build()
            vc.output.prepareRecording(context, fileOptions)
        } else {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/StudyVault")
            }
            val mediaOptions = MediaStoreOutputOptions.Builder(
                context.contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).setContentValues(values).build()
            vc.output.prepareRecording(context, mediaOptions)
        }

        isAutoStopping = false

        if (!isAudioMuted) {
            prep.withAudioEnabled()
        }

        recording = prep.start(ContextCompat.getMainExecutor(context)) { event: VideoRecordEvent ->
            onRecordingEvent?.invoke(event)
            
            if (event is VideoRecordEvent.Start) {
                if (maxDurationMs > 0) {
                    mainHandler.removeCallbacks(autoStopRunnable)
                    mainHandler.postDelayed(autoStopRunnable, maxDurationMs)
                }
            }
            
            if (event is VideoRecordEvent.Finalize) {
                restoreSystemSound()
                mainHandler.removeCallbacks(autoStopRunnable)
                recording = null

                // 연쇄 반복 녹화가 끝났을 때만 WakeLock을 완전 해제
                val wasAutoStop = isAutoStopping
                isAutoStopping = false

                val willRepeat = wasAutoStop && !event.hasError() &&
                    (currentRepeatCount + 1) < maxRepeatCount

                if (wasAutoStop && !event.hasError()) {
                    currentRepeatCount++
                    if (currentRepeatCount < maxRepeatCount) {
                        mainHandler.postDelayed({
                            startRecording()
                        }, 500L)
                    } else {
                        currentRepeatCount = 0
                        releaseWakeLock()
                    }
                } else {
                    currentRepeatCount = 0
                    releaseWakeLock()
                }

                // 반복 녹화로 이어지지 않는 진짜 종료 시점에만 service 정리 신호를 보낸다.
                if (!willRepeat) {
                    onFinalizeEnded?.invoke()
                }
            }
        }
    }

    fun resetRepeatCount() {
        currentRepeatCount = 0
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    fun stopRecording() {
        // recording = null과 wake lock 해제는 Recording.Finalize callback에서 처리한다.
        // 여기서 즉시 정리하면 finalize 콜백과 race가 발생해 파일이 손상될 수 있다.
        recording?.stop()
        mainHandler.removeCallbacks(autoStopRunnable)
    }

    fun isRecording(): Boolean = recording != null

    fun release() {
        recording?.stop()
        recording = null
        mainHandler.removeCallbacks(autoStopRunnable)
        releaseWakeLock()
        cameraProvider?.unbindAll()
        camera = null
        videoCapture = null
        imageCapture = null
    }
}
