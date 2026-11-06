package com.example.camera2study

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
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

        private const val LONG_RECORDING_SEGMENT_MS = 30 * 60 * 1000L
        private const val WAKE_LOCK_GRACE_MS = 60 * 1000L
    }

    private var sensedRotation = android.view.Surface.ROTATION_0
    private val orientationListener = object : android.view.OrientationEventListener(context) {
        override fun onOrientationChanged(angle: Int) {
            if (angle == ORIENTATION_UNKNOWN) return
            sensedRotation = when (angle) {
                in 45..134 -> android.view.Surface.ROTATION_270
                in 135..224 -> android.view.Surface.ROTATION_180
                in 225..314 -> android.view.Surface.ROTATION_90
                else -> android.view.Surface.ROTATION_0
            }
            if (!isRecording() && videoCapture != null && videoCapture?.targetRotation != outputRotation()) bindUseCases()
        }
    }
    private fun outputRotation(): Int = when (orientationMode) {
        "LANDSCAPE" -> if (sensedRotation == android.view.Surface.ROTATION_270) sensedRotation else android.view.Surface.ROTATION_90
        "PORTRAIT" -> if (sensedRotation == android.view.Surface.ROTATION_180) sensedRotation else android.view.Surface.ROTATION_0
        else -> sensedRotation
    }
    private var released = false
    private var configuring = false
    private var cameraGeneration = 0L
    private var zoomRequest = 0L
    private var exposureRequest = 0L
    private var optionsRequest = 0L
    private var zoomReady = false
    private var exposureReady = false
    private var optionsReady = false
    private var preparationError: String? = null
    private val controlErrors = mutableMapOf<String, String>()
    private var preparationCallback: ((String?) -> Unit)? = null
    fun areControlsReady(): Boolean = !released && camera != null && zoomReady && exposureReady && optionsReady && preparationError == null

    /** Waits for the current camera generation, including any rebind before it becomes ready. */
    fun awaitControlsApplied(callback: (String?) -> Unit) {
        preparationCallback = callback
        when {
            released -> completePreparation("카메라가 닫혔습니다")
            preparationError != null -> completePreparation(preparationError)
            areControlsReady() -> completePreparation(null)
        }
    }

    fun cancelPendingPreparation() { preparationCallback = null }

    private fun completePreparation(error: String?) {
        val callback = preparationCallback ?: return
        preparationCallback = null
        callback(error)
    }

    private fun retryControl(control: String) {
        controlErrors.remove(control)
        preparationError = controlErrors.values.firstOrNull()
    }

    private fun failPreparation(message: String, control: String = "configuration") {
        controlErrors[control] = message
        preparationError = controlErrors.values.firstOrNull()
        reportError(message)
        completePreparation(message)
    }

    private fun notifyControlsApplied() {
        if (!areControlsReady()) return
        lastError = null
        // Persist acknowledged live adjustments even if the recording screen is closed next.
        // Quick recording keeps its explicitly saved profile independent of the normal profile.
        if (isRecording() && session?.optString("source") == "app") {
            val store = com.example.camera2study.util.CameraSettingsStore
            store.save(context, store.load(context).copy(
                zoomRatio = zoomRatio, zoomModeRatio = selectedZoomModeRatio, exposureEv = exposureEv))
        }
        completePreparation(null)
        onControlsApplied?.invoke()
    }
    var lastError: String? = null
        private set
    var onError: ((String) -> Unit)? = null

    fun configure(block: () -> Unit) {
        if (isRecording()) return
        configuring = true
        try { block() } finally { configuring = false }
        bindUseCases()
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var observedCameraInfo: androidx.camera.core.CameraInfo? = null
    private var lastCameraState: String? = null
    private val cameraStateObserver = androidx.lifecycle.Observer<androidx.camera.core.CameraState> { state ->
        val signature = "${state.type}:${state.error?.code}"
        if (signature != lastCameraState) {
            lastCameraState = signature
            recordDiagnostic("camera_state", org.json.JSONObject().put("state", state.type.name)
                .put("errorCode", state.error?.code).put("error", state.error?.cause?.message.orEmpty().take(500)))
        }
    }
    private var wakeLock: PowerManager.WakeLock? = null
    private var previewUseCase: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null

    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var pendingZoomAfterBind: Float? = null

    private var awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO
    private var exposureTimeNs: Long? = null
    var manualIso: Int = 400
    var videoLook = com.example.camera2study.util.VideoLook()
    /** Settings preview keeps the SDR processor alive for instant original/effect comparison. */
    var keepPreviewEffect = false
    private var lookProcessor: com.example.camera2study.util.VideoLookProcessor? = null
    fun updatePreviewLook(value: com.example.camera2study.util.VideoLook): Boolean {
        if (released || isRecording()) return false
        val normalized = value.normalized()
        if (isHdrEnabled && !normalized.isIdentity()) return false
        videoLook = normalized
        val processor = lookProcessor
        if (processor != null) processor.look = normalized
        else if (!normalized.isIdentity()) return bindUseCases()
        return true
    }
    private fun usesVideoEffect(): Boolean = !videoLook.isIdentity() || (keepPreviewEffect && !isHdrEnabled)
    private var aperture: Float? = null
    private var manualWbGains: FloatArray? = null

    // --- 신규 설정값 ---
    var zoomRatio: Float = 1.0f
        private set

    var selectedZoomModeRatio: Float = 1.0f
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

    var maxDurationMs: Long = 1_800_000L // 파일당 최대 길이
    var maxRepeatCount: Int = 0 // 0이면 직접 종료
    var totalDurationMs: Long = 0
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

    // 자동 모드(false)가 기본. true로 켜면 수동 셔터/조리개/AWB 설정을 허용한다.
    var isProMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (!value) {
                    // 자동 모드 복귀 시 수동 파라미터 초기화
                    exposureTimeNs = null
                    aperture = null
                    manualWbGains = null
                    awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
                }
                applyOptionsLive()
            }
        }

    // --- 추가 기능 변수 ---
    var currentRatioMode: Int = RATIO_16_9
        set(@Suppress("UNUSED_PARAMETER") value) { field = RATIO_16_9 }
    fun previewFrameRatio(): Float {
        val rotation = videoCapture?.targetRotation ?: outputRotation()
        return if (rotation == android.view.Surface.ROTATION_0 || rotation == android.view.Surface.ROTATION_180) 9f / 16f else 16f / 9f
    }

    var audioEnabled: Boolean = true
    var orientationMode: String = "AUTO"
    var mirrorFront: Boolean = false
    var exposureEv: Float = 0f
    var selectedCameraId: String? = null

    var isPrivateSave: Boolean = true // 기본 비공개 보관함 저장

    // 타이머 핸들러 (최대 녹화 시간 제어용)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoStopRunnable = Runnable {
        isAutoStopping = true
        if (isRecording()) {
            publish(RecordingPhase.FINALIZING, "파일 저장 중")
            recording?.stop()
        }
    }
    private var isAutoStopping = false // 최대 시간에 의해 중지되었는지 여부

    // pause/resume 상태 — 녹화 시간에서 pause 구간을 제외하기 위해 사용한다.
    private var isRecordingPaused = false
    private var recordingStartElapsedMs = 0L  // Start 이벤트 수신 시각
    private var pauseStartElapsedMs = 0L       // 현재 pause 시작 시각
    private var totalPausedMs = 0L             // 누적 pause 시간

    var onRecordingEvent: ((VideoRecordEvent) -> Unit)? = null
    var onCameraChanged: ((String?) -> Unit)? = null
    var onZoomChanged: ((Float, Float) -> Unit)? = null // current, max
    var onControlsApplied: (() -> Unit)? = null
    var onFinalizeEnded: (() -> Unit)? = null

    val backLenses: List<LensInfo> by lazy { CameraUtils.listBackLenses(context) }

    fun init(
        owner: LifecycleOwner,
        onReady: () -> Unit = {}
    ) {
        lifecycleOwner = owner
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (released) return@addListener
            try {
                cameraProvider = future.get()
                bindUseCases()
                onReady()
            } catch (e: Exception) {
                reportError("카메라 준비 실패: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun attachPreview(view: PreviewView) {
        if (released || previewView === view) return
        previewView = view
        previewUseCase?.setSurfaceProvider(view.surfaceProvider)
        recordDiagnostic("preview_attached")
    }
    fun detachPreview() {
        if (previewView == null) return
        previewView = null
        // Keep the use-case group and video crop stable while the screen is closed.
        previewUseCase?.setSurfaceProvider(null)
        recordDiagnostic("preview_detached")
    }

    fun reportError(message: String) {
        lastError = message
        android.util.Log.w("CameraController", message)
        recordDiagnostic("controller_error", org.json.JSONObject().put("message", message.take(500)))
        onError?.invoke(message)
    }

    private fun unbindOwnedUseCases() {
        observedCameraInfo?.cameraState?.removeObserver(cameraStateObserver)
        observedCameraInfo = null
        val owned = listOfNotNull(previewUseCase, videoCapture)
        if (owned.isNotEmpty()) cameraProvider?.unbind(*owned.toTypedArray())
    }

    private fun bindUseCases(): Boolean {
        val provider = cameraProvider ?: return false
        val owner = lifecycleOwner ?: return false

        if (isRecording() || configuring || released) return false

        cameraGeneration++
        zoomReady = false
        exposureReady = false
        optionsReady = false
        preparationError = null
        controlErrors.clear()
        try {
            val selector = buildSelector()
            val info = selector.filter(provider.availableCameraInfos).firstOrNull()
                ?: error("선택한 카메라가 공개되지 않았습니다")
            com.example.camera2study.util.VideoConfigurationSupport.problem(info, videoQuality, targetFps,
                isHdrEnabled, usesVideoEffect())?.let { error(it) }
            val dynamicRange = checkNotNull(com.example.camera2study.util.VideoConfigurationSupport.dynamicRange(info, isHdrEnabled))
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(videoQuality))
                .setAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9).build()
            val video = VideoCapture.Builder(recorder).setTargetRotation(outputRotation())
                .setTargetFrameRate(android.util.Range(targetFps, targetFps))
                .setDynamicRange(dynamicRange)
                .setMirrorMode(if (mirrorFront) androidx.camera.core.MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else androidx.camera.core.MirrorMode.MIRROR_MODE_OFF)
                .build()
            val preview = Preview.Builder().setTargetRotation(outputRotation())
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build())
                .setMirrorMode(if (mirrorFront) androidx.camera.core.MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else androidx.camera.core.MirrorMode.MIRROR_MODE_OFF)
                .setDynamicRange(dynamicRange).build()
            previewView?.let { preview.setSurfaceProvider(it.surfaceProvider) }
            val rotation = outputRotation()
            val ratio = if (rotation == android.view.Surface.ROTATION_0 || rotation == android.view.Surface.ROTATION_180) android.util.Rational(9, 16) else android.util.Rational(16, 9)
            val group = androidx.camera.core.UseCaseGroup.Builder()
                .setViewPort(androidx.camera.core.ViewPort.Builder(ratio, rotation).setScaleType(androidx.camera.core.ViewPort.FILL_CENTER).build())
                .addUseCase(preview).addUseCase(video)
            // All capability checks happen before releasing the last working configuration.
            unbindOwnedUseCases()
            lookProcessor?.close(); lookProcessor = null
            camera = null
            videoCapture = null
            previewUseCase = null
            if (!isHdrEnabled && usesVideoEffect()) {
                val generation = cameraGeneration
                lookProcessor = com.example.camera2study.util.VideoLookProcessor(diagnostic = { type, message ->
                    mainHandler.post {
                        if (!released && generation == cameraGeneration)
                            recordDiagnostic(type, org.json.JSONObject().put("message", message.take(500)))
                    }
                }) { error ->
                    mainHandler.post {
                        if (!released && generation == cameraGeneration) {
                            val message = "색보정 처리 실패: " + error.message
                            if (isRecording()) {
                                failure = message
                                reportError(message)
                                stopRecording("effect_error")
                            } else failPreparation(message)
                        }
                    }
                }.also { it.look = videoLook.normalized(); group.addEffect(it.effect) }
            }
            camera = provider.bindToLifecycle(owner, selector, group.build())
            lastCameraState = null
            observedCameraInfo = camera?.cameraInfo
            observedCameraInfo?.cameraState?.observe(owner, cameraStateObserver)
            previewUseCase = preview
            videoCapture = video
            lastError = null

            applyOptionsLive()
            applyPendingZoomAfterBind()
            applyExposureAfterBind()
            updateZoomBounds()

            val activeId = camera?.let { Camera2CameraInfo.from(it.cameraInfo).cameraId }
            onCameraChanged?.invoke(activeId)
            return true
        } catch (e: Exception) {
            failPreparation("촬영 설정 적용 실패: ${e.message}")
            return false
        }
    }

    private fun buildSelector(): CameraSelector {
        val builder = CameraSelector.Builder().requireLensFacing(lensFacing)
        val targetId = selectedCameraId
        if (targetId != null) {
            builder.addCameraFilter { cameras ->
                cameras.filter { Camera2CameraInfo.from(it).cameraId == targetId }

            }
        }
        return builder.build()
    }

    private fun applyOptionsLive() {
        if (configuring || released) return
        val cam = camera ?: return
        val generation = cameraGeneration
        val request = ++optionsRequest
        optionsReady = false
        retryControl("options")
        val control = Camera2CameraControl.from(cam.cameraControl)
        val builder = CaptureRequestOptions.Builder()
        // CameraX owns output rotation. Disable the framework's additional crop/rotation.
        if (android.os.Build.VERSION.SDK_INT >= 31 && Camera2CameraInfo.from(cam.cameraInfo)
                .getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
                ?.contains(CaptureRequest.SCALER_ROTATE_AND_CROP_NONE) == true) {
            builder.setCaptureRequestOption(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
        }
        // Apply zoom and metering together; early zoom overrides are optional, not needed for recording.
        if (android.os.Build.VERSION.SDK_INT >= 34 && Camera2CameraInfo.from(cam.cameraInfo)
                .getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_SETTINGS_OVERRIDES) != null) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_SETTINGS_OVERRIDE, CaptureRequest.CONTROL_SETTINGS_OVERRIDE_OFF)
        }
        
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
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val manualSupported = info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val range = info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val expNs = exposureTimeNs?.takeIf { manualSupported && isProMode }?.coerceIn(range?.lower ?: 1L,
            minOf(range?.upper ?: Long.MAX_VALUE, 1_000_000_000L / targetFps))
        if (expNs != null) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_OFF
            )
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
            val sensitivity = info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            if (sensitivity != null) builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, manualIso.coerceIn(sensitivity.lower, sensitivity.upper))
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / targetFps)
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

        val afModes = info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val continuous = listOf(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE).firstOrNull { it in afModes }
        if (continuous != null) builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, continuous)

        // 노출 자동 보정 및 안티밴딩
        if (expNs == null) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
            )
        }

        val future = control.setCaptureRequestOptions(builder.build())
        future.addListener({
            if (cam !== camera || generation != cameraGeneration || request != optionsRequest || released) return@addListener
            runCatching { future.get() }.onSuccess {
                optionsReady = true
                notifyControlsApplied()
            }.onFailure { failPreparation("카메라 설정 적용 실패: ${it.message}", "options") }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun nativeMin(): Float = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
    private fun nativeMax(): Float = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f

    private fun lensScale(id: String?): Float {
        if (!isFacingBack() || id == null) return 1f
        val primary = cameraProvider?.availableCameraInfos?.let {
            runCatching { CameraSelector.DEFAULT_BACK_CAMERA.filter(it).firstOrNull() }.getOrNull()
        }?.let { Camera2CameraInfo.from(it).cameraId }
        val base = backLenses.firstOrNull { it.cameraId == primary }?.equivalentFocalLength ?: return 1f
        val focal = backLenses.firstOrNull { it.cameraId == id }?.equivalentFocalLength ?: return 1f
        return if (base > 0 && focal > 0) focal / base else 1f
    }

    fun getMinZoomRatio(): Float = nativeMin() * lensScale(activeCameraId())
    fun getMaxZoomRatio(): Float = nativeMax() * lensScale(activeCameraId())
    fun displayZoomRatio(): Float = zoomRatio * lensScale(activeCameraId())

    fun setZoomRatio(ratio: Float) = applyZoomRatio(ratio, allowRebind = true)

    private fun applyZoomRatio(ratio: Float, allowRebind: Boolean) {
        val cam = camera ?: run { pendingZoomAfterBind = ratio; return }
        val generation = cameraGeneration
        val request = ++zoomRequest
        zoomReady = false
        retryControl("zoom")
        val applied = ratio.coerceIn(nativeMin(), nativeMax())
        val future = cam.cameraControl.setZoomRatio(applied)
        future.addListener({
            if (cam !== camera || generation != cameraGeneration || request != zoomRequest || released) return@addListener
            runCatching { future.get() }.onSuccess {
                zoomRatio = cam.cameraInfo.zoomState.value?.zoomRatio ?: applied
                selectedZoomModeRatio = displayZoomRatio()
                zoomReady = true
                onZoomChanged?.invoke(displayZoomRatio(), getMaxZoomRatio())
                onCameraChanged?.invoke(activeCameraId())
                notifyControlsApplied()
            }.onFailure {
                if (allowRebind && !isRecording()) {
                    pendingZoomAfterBind = applied
                    bindUseCases()
                } else failPreparation("배율 적용 실패: ${it.message}", "zoom")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun setDisplayZoomRatio(ratio: Float) = setZoomRatio(ratio / lensScale(activeCameraId()))

    fun supportsZoomPreset(ratio: Float): Boolean {
        if (ratio in getMinZoomRatio()..getMaxZoomRatio()) return true
        if (!isFacingBack() || isRecording()) return false
        return cameraProvider?.availableCameraInfos.orEmpty().filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }.any {
            coversDisplayZoom(it, ratio) && supportsCurrentConfiguration(it)
        }
    }

    private fun coversDisplayZoom(info: androidx.camera.core.CameraInfo, ratio: Float): Boolean {
        val scale = lensScale(Camera2CameraInfo.from(info).cameraId)
        val zoom = info.zoomState.value ?: return false
        return ratio >= zoom.minZoomRatio * scale - 0.08f && ratio <= zoom.maxZoomRatio * scale
    }

    private fun supportsCurrentConfiguration(info: androidx.camera.core.CameraInfo): Boolean =
        com.example.camera2study.util.VideoConfigurationSupport.problem(info, videoQuality, targetFps,
            isHdrEnabled, usesVideoEffect()) == null

    fun selectBackZoomMode(modeRatio: Float): Boolean = selectZoomPreset(modeRatio)

    fun selectZoomPreset(ratio: Float): Boolean {
        if (!supportsZoomPreset(ratio)) { reportError("이 카메라에서 지원하지 않는 배율입니다"); return false }
        if (!isFacingBack() || isRecording()) { setDisplayZoomRatio(ratio); return true }
        val candidates = cameraProvider?.availableCameraInfos.orEmpty().filter {
            it.lensFacing == CameraSelector.LENS_FACING_BACK && coversDisplayZoom(it, ratio) && supportsCurrentConfiguration(it)
        }
        val primary = runCatching { CameraSelector.DEFAULT_BACK_CAMERA.filter(candidates).firstOrNull() }.getOrNull()
        // Prefer a publicly exposed optical lens at telephoto presets; otherwise let the logical camera crop/switch.
        val physical = candidates.filter {
            val scale = lensScale(Camera2CameraInfo.from(it).cameraId)
            val z = it.zoomState.value
            z != null && ((ratio < 1f && scale < 0.85f) || (ratio >= 2f && scale > 1.5f)) &&
                ratio >= z.minZoomRatio * scale - 0.08f && ratio <= z.maxZoomRatio * scale
        }.maxByOrNull { lensScale(Camera2CameraInfo.from(it).cameraId) }
        val desired = physical ?: primary ?: camera?.cameraInfo
        val id = desired?.let { Camera2CameraInfo.from(it).cameraId }
        if (id == null || id == activeCameraId()) { setDisplayZoomRatio(ratio); return true }
        val previousId = selectedCameraId
        val previousZoom = zoomRatio
        selectedCameraId = id
        pendingZoomAfterBind = ratio / lensScale(id)
        if (bindUseCases()) return true
        val error = lastError
        selectedCameraId = previousId
        pendingZoomAfterBind = previousZoom
        bindUseCases()
        reportError("배율 전환에 실패해 이전 렌즈로 복구합니다: ${error.orEmpty()}")
        return false
    }

    private fun applyPendingZoomAfterBind() {
        val requested = pendingZoomAfterBind ?: zoomRatio
        pendingZoomAfterBind = null
        applyZoomRatio(requested, allowRebind = false)
    }

    private fun updateZoomBounds() {
        val z = camera?.cameraInfo?.zoomState?.value ?: return
        zoomRatio = z.zoomRatio
        selectedZoomModeRatio = displayZoomRatio()
        onZoomChanged?.invoke(displayZoomRatio(), getMaxZoomRatio())
    }

    fun restoreCameraSelection(back: Boolean, id: String?, nativeZoom: Float) {
        if (isRecording()) return
        lensFacing = if (back) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        selectedCameraId = id
        pendingZoomAfterBind = nativeZoom
        zoomRatio = nativeZoom
    }

    fun switchFacing() {
        if (isRecording()) return
        val saved = com.example.camera2study.util.CameraSettingsStore.loadForFacing(context, !isFacingBack())
        com.example.camera2study.util.CameraSettingsStore.applyToController(this, saved)
    }

    fun selectBackLens(cameraId: String) {
        if (isRecording()) return
        val info = cameraProvider?.availableCameraInfos?.firstOrNull { Camera2CameraInfo.from(it).cameraId == cameraId }
        if (info == null || !supportsCurrentConfiguration(info)) {
            reportError("현재 촬영 설정을 지원하지 않는 렌즈입니다")
            return
        }
        val previousBack = isFacingBack()
        val previousId = selectedCameraId
        val previousZoom = zoomRatio
        restoreCameraSelection(true, cameraId, 1f)
        if (!bindUseCases()) {
            val error = lastError
            restoreCameraSelection(previousBack, previousId, previousZoom)
            bindUseCases()
            reportError("렌즈 전환에 실패해 이전 설정으로 복구합니다: ${error.orEmpty()}")
        }
    }

    fun activeCameraId(): String? =
        camera?.let { Camera2CameraInfo.from(it.cameraInfo).cameraId }

    fun isFacingBack(): Boolean = lensFacing == CameraSelector.LENS_FACING_BACK

    fun setAwbMode(mode: Int) {
        awbMode = mode
        applyOptionsLive()
    }

    fun getAwbMode(): Int = awbMode

    fun setManualWbGains(gains: FloatArray?) {
        manualWbGains = gains
        applyOptionsLive()
    }

    fun getManualWbGains(): FloatArray? = manualWbGains?.copyOf()

    fun setExposureTime(ns: Long?) {
        exposureTimeNs = ns
        applyOptionsLive()
    }

    fun getExposureTime(): Long? = exposureTimeNs

    fun setAperture(value: Float?) {
        aperture = value
        applyOptionsLive()
    }

    fun getAperture(): Float? = aperture

    // --- 노출 보정 (AE) 제어 API ---
    fun getMinExposureIndex(): Int =
        camera?.cameraInfo?.exposureState?.exposureCompensationRange?.lower ?: 0

    fun getMaxExposureIndex(): Int =
        camera?.cameraInfo?.exposureState?.exposureCompensationRange?.upper ?: 0

    fun getCurrentExposureIndex(): Int =
        camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0

    private fun applyExposureAfterBind() {
        val state = camera?.cameraInfo?.exposureState ?: return
        if (!state.isExposureCompensationSupported || state.exposureCompensationStep.toFloat() <= 0) {
            exposureEv = 0f
            exposureReady = true
            notifyControlsApplied()
            return
        }
        if (isProMode && exposureTimeNs != null) { exposureReady = true; notifyControlsApplied(); return }
        setExposureIndex(kotlin.math.round(exposureEv / state.exposureCompensationStep.toFloat()).toInt())
    }

    fun setExposureIndex(index: Int) {
        val cam = camera ?: return
        val generation = cameraGeneration
        val request = ++exposureRequest
        exposureReady = false
        if (exposureTimeNs != null) { exposureReady = true; reportError("수동 셔터 사용 중에는 노출 보정을 사용할 수 없습니다"); return }
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) { exposureReady = true; notifyControlsApplied(); return }
        retryControl("exposure")
        val clamped = index.coerceIn(getMinExposureIndex(), getMaxExposureIndex())
        val future = cam.cameraControl.setExposureCompensationIndex(clamped)
        future.addListener({
            if (cam !== camera || generation != cameraGeneration || request != exposureRequest || released) return@addListener
            runCatching { future.get() }.onSuccess {
                exposureEv = it * state.exposureCompensationStep.toFloat()
                exposureReady = true
                notifyControlsApplied()
            }.onFailure { failPreparation("밝기 적용 실패: ${it.message}", "exposure") }
        }, ContextCompat.getMainExecutor(context))
    }

    private var session: org.json.JSONObject? = null
    private var sessionFinished = true
    private var audioHealth = com.example.camera2study.util.AudioHealthTimeline(false)
    private var segmentEntry: org.json.JSONObject? = null
    private var sessionDurationMs = 0L
    private var sessionBytes = 0L
    private var segmentDurationMs = 0L
    private var segmentBytes = 0L
    private var failure: String? = null
    private var stopReason = "user"
    private var stopRequested = false
    private var recordingToken = 0L
    private var lastProgressAt = 0L
    private var lastJournalAt = 0L
    private var lastProgressDiagnosticAt = 0L
    private var phaseSince = 0L
    private var sessionSegmentMs = LONG_RECORDING_SEGMENT_MS
    private var sessionPlan = RecordingPlan(LONG_RECORDING_SEGMENT_MS)
    private val restartRunnable = Runnable { if (!stopRequested && !released) startSegment() }
    val recordingState: RecordingState get() = RecordingRuntime.state.value ?: RecordingState()

    private fun recordDiagnostic(type: String, details: org.json.JSONObject = org.json.JSONObject()) {
        val journal = session ?: return
        if (sessionFinished) return
        details.put("phase", recordingState.phase.name)
        com.example.camera2study.util.RecordingJournal.diagnostic(context, journal, type, currentRepeatCount + 1, details)
    }

    private fun updateAudioHealth(event: VideoRecordEvent) {
        val stats = event.recordingStats.audioStats
        val state = when (stats.audioState) {
            androidx.camera.video.AudioStats.AUDIO_STATE_ACTIVE -> "ACTIVE"
            androidx.camera.video.AudioStats.AUDIO_STATE_DISABLED -> "DISABLED"
            androidx.camera.video.AudioStats.AUDIO_STATE_SOURCE_SILENCED -> "SOURCE_SILENCED"
            androidx.camera.video.AudioStats.AUDIO_STATE_SOURCE_ERROR -> "SOURCE_ERROR"
            androidx.camera.video.AudioStats.AUDIO_STATE_ENCODER_ERROR -> "ENCODER_ERROR"
            androidx.camera.video.AudioStats.AUDIO_STATE_MUTED -> "MUTED"
            else -> "UNKNOWN_${stats.audioState}"
        }
        val affected = state in setOf("SOURCE_SILENCED", "SOURCE_ERROR", "ENCODER_ERROR", "MUTED")
        val at = sessionDurationMs + segmentDurationMs
        val changed = audioHealth.observe(state, affected, at, stats.errorCause?.message?.take(500))
        session?.let { com.example.camera2study.util.RecordingJournal.audioSummary(it, audioHealth.snapshot(at)) }
        if (changed) recordDiagnostic("audio_state", org.json.JSONObject().put("state", state)
            .put("recordedMs", at).put("error", stats.errorCause?.message.orEmpty().take(500)))
    }

    private fun progressDetails(now: Long = android.os.SystemClock.elapsedRealtime()): org.json.JSONObject {
        val result = org.json.JSONObject().put("recordedMs", sessionDurationMs + segmentDurationMs)
            .put("segmentMs", segmentDurationMs).put("encodedBytes", segmentBytes)
            .put("lastRecorderProgressAgeMs", (now - lastProgressAt).coerceAtLeast(0))
        lookProcessor?.progress()?.let { effect ->
            result.put("effectInputAgeMs", if (effect.inputAtMs == 0L) -1 else now - effect.inputAtMs)
                .put("effectVideoOutputAgeMs", if (effect.videoOutputAtMs == 0L) -1 else now - effect.videoOutputAtMs)
                .put("effectInputTimestampNs", effect.inputTimestampNs).put("effectInputFrames", effect.inputFrames)
                .put("effectVideoFrames", effect.videoFrames).put("effectOutputs", effect.outputCount)
        }
        return result
    }

    private fun publish(phase: RecordingPhase, message: String) {
        if (recordingState.phase != phase) phaseSince = android.os.SystemClock.elapsedRealtime()
        RecordingRuntime.state.value = RecordingState(phase, session?.optString("id").orEmpty(),
            if (phase == RecordingPhase.COMPLETED || phase == RecordingPhase.ERROR) session?.optJSONArray("segments")?.length() ?: 0 else currentRepeatCount + 1,
            sessionDurationMs + segmentDurationMs, sessionBytes + segmentBytes,
            message, segmentEntry?.optString("path").orEmpty())
    }

    private val healthCheck = object : Runnable {
        override fun run() {
            if (!isRecording() || released) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastProgressDiagnosticAt >= 15_000L) {
                recordDiagnostic("recording_progress", progressDetails(now))
                lastProgressDiagnosticAt = now
            }
            if (recordingState.phase != RecordingPhase.FINALIZING) {
                com.example.camera2study.util.CaptureHealth.problem(context, audioEnabled, false)?.let {
                    failure = it
                    stopRecording("resource_error")
                }
            }
            when (recordingState.phase) {
                RecordingPhase.PREPARING, RecordingPhase.RECORDING -> {
                    if (now - lastProgressAt > 15_000L) {
                        recordDiagnostic("recording_progress_timeout", progressDetails(now))
                        failure = "영상 데이터 기록이 15초 동안 진행되지 않았습니다"
                        reportError(failure!!)
                        stopRecording("progress_timeout")
                    }
                }
                RecordingPhase.FINALIZING -> if (now - phaseSince > 15_000L) {
                    recordDiagnostic("finalize_timeout")
                    failure = failure ?: "파일 저장 완료를 확인하지 못했습니다"
                    stopReason = "finalize_timeout"
                    recording?.close()
                    recording = null
                    finishSession()
                    return
                }
                else -> Unit
            }
            renewWakeLock(60_000L)
            mainHandler.postDelayed(this, 2_000L)
        }
    }

    @SuppressLint("MissingPermission", "InvalidWakeLockTag")
    fun startRecording(source: String = "app") {
        if (released || isRecording()) return
        check(areControlsReady()) { preparationError ?: "촬영 설정 적용이 완료되지 않았습니다" }
        session = com.example.camera2study.util.RecordingJournal.begin(context, source,
            "camera=${activeCameraId()}, zoom=$zoomRatio, quality=$videoQuality, fps=$targetFps, hdr=$isHdrEnabled, ev=$exposureEv, audio=$audioEnabled, orientation=$orientationMode, mirror=$mirrorFront")
        session?.let { journal ->
            val bundle = com.example.camera2study.util.CameraSettingsStore.toBundle(com.example.camera2study.util.CameraSettingsStore.snapshot(context, this, com.example.camera2study.util.CameraSettingsStore.load(context, source != "app").colorTempK))
            val config = org.json.JSONObject()
            bundle.keySet().forEach { config.put(it, bundle.get(it)) }
            journal.put("configuration", config)
        }
        sessionFinished = false
        segmentEntry = null
        segmentDurationMs = 0
        segmentBytes = 0
        failure = null
        stopReason = "user"
        stopRequested = false
        currentRepeatCount = 0
        sessionDurationMs = 0
        sessionBytes = 0
        lastProgressDiagnosticAt = android.os.SystemClock.elapsedRealtime()
        audioHealth = com.example.camera2study.util.AudioHealthTimeline(audioEnabled)
        session?.let { com.example.camera2study.util.RecordingJournal.audioSummary(it, audioHealth.snapshot(0)) }
        recordDiagnostic("configuration_ready", org.json.JSONObject().put("cameraId", activeCameraId())
            .put("zoom", zoomRatio).put("exposureEv", exposureEv).put("fps", targetFps)
            .put("previewAttached", previewView != null).put("cameraState", lastCameraState.orEmpty())
            .put("effect", usesVideoEffect()))
        sessionPlan = RecordingPlan(maxDurationMs.coerceAtLeast(1_000L), maxRepeatCount.coerceAtLeast(0), totalDurationMs.coerceAtLeast(0))
        val problem = com.example.camera2study.util.CaptureHealth.problem(context, audioEnabled, true)
        if (problem != null || (!isPrivateSave && android.os.Build.VERSION.SDK_INT < 29)) {
            failure = problem ?: "공개 갤러리 저장은 Android 10 이상에서 지원합니다"
            stopReason = "preflight_error"
            finishSession()
            return
        }
        videoCapture?.targetRotation = outputRotation()
        startSegment()
    }

    @SuppressLint("MissingPermission")
    private fun startSegment() {
        if (released || stopRequested) return
        val journal = session ?: return
        sessionSegmentMs = sessionPlan.nextDuration(sessionDurationMs)
        if (sessionSegmentMs <= 0) { finishSession(); return }
        segmentDurationMs = 0
        segmentBytes = 0
        segmentEntry = null
        lastProgressAt = android.os.SystemClock.elapsedRealtime()
        lastJournalAt = 0L
        publish(RecordingPhase.PREPARING, "녹화 준비 중")
        val vc = videoCapture
        if (vc == null) {
            failure = lastError ?: "카메라가 준비되지 않았습니다"
            stopReason = "configuration_error"
            finishSession()
            return
        }
        renewWakeLock(60_000L)
        mainHandler.removeCallbacks(healthCheck)
        mainHandler.postDelayed(healthCheck, 2_000L)
        val token = ++recordingToken
        try {
            val name = "StudyVault_${journal.getString("id")}_${currentRepeatCount + 1}.mp4"
            val prep = if (isPrivateSave) {
                val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "private_vault")
                check(dir.exists() || dir.mkdirs()) { "저장 폴더를 만들 수 없습니다" }
                File(dir, ".nomedia").createNewFile()
                val file = File(dir, name)
                segmentEntry = com.example.camera2study.util.RecordingJournal.segment(context, journal, file.absolutePath, name)
                vc.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    if (android.os.Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/StudyVault")
                }
                segmentEntry = com.example.camera2study.util.RecordingJournal.segment(context, journal, "", name)
                vc.output.prepareRecording(context, MediaStoreOutputOptions.Builder(context.contentResolver,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build())
            }
            isAutoStopping = false
            isRecordingPaused = false
            totalPausedMs = 0L
            if (audioEnabled) prep.withAudioEnabled()
            recording = prep.start(ContextCompat.getMainExecutor(context)) { event ->
                if (token != recordingToken) return@start
                val previousDuration = segmentDurationMs
                segmentDurationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                val previousBytes = segmentBytes
                segmentBytes = event.recordingStats.numBytesRecorded
                if (segmentBytes > previousBytes && segmentDurationMs > previousDuration) lastProgressAt = android.os.SystemClock.elapsedRealtime()
                updateAudioHealth(event)
                when (event) {
                    is VideoRecordEvent.Start -> {
                        recordDiagnostic("recording_started")
                        recordingStartElapsedMs = android.os.SystemClock.elapsedRealtime()
                        publish(RecordingPhase.PREPARING, "영상 데이터 확인 중")
                        mainHandler.postDelayed(autoStopRunnable, sessionSegmentMs)
                    }
                    is VideoRecordEvent.Status -> {
                        if (!stopRequested && !isAutoStopping && !isRecordingPaused && segmentBytes > 0 && segmentDurationMs > 0) {
                            publish(RecordingPhase.RECORDING, "녹화 중")
                        }
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastJournalAt > 5_000L) {
                            segmentEntry?.put("durationMs", segmentDurationMs)?.put("bytes", segmentBytes)
                                ?.put("captureProgress", progressDetails(now))
                            com.example.camera2study.util.RecordingJournal.sampleResources(context, journal)
                            com.example.camera2study.util.RecordingJournal.save(context, journal)
                            lastJournalAt = now
                        }
                    }
                    is VideoRecordEvent.Pause -> {
                        recordDiagnostic("recording_paused")
                        isRecordingPaused = true
                        pauseStartElapsedMs = android.os.SystemClock.elapsedRealtime()
                        mainHandler.removeCallbacks(autoStopRunnable)
                        publish(RecordingPhase.PAUSED, "일시정지")
                    }
                    is VideoRecordEvent.Resume -> {
                        recordDiagnostic("recording_resumed")
                        isRecordingPaused = false
                        totalPausedMs += android.os.SystemClock.elapsedRealtime() - pauseStartElapsedMs
                        lastProgressAt = android.os.SystemClock.elapsedRealtime()
                        renewWakeLock(60_000L)
                        mainHandler.postDelayed(autoStopRunnable, (sessionSegmentMs - segmentDurationMs).coerceAtLeast(1L))
                        publish(RecordingPhase.RECORDING, "녹화 중")
                    }
                    is VideoRecordEvent.Finalize -> {
                        recordDiagnostic("segment_finalized", org.json.JSONObject().put("errorCode", event.error)
                            .put("error", event.cause?.message.orEmpty().take(500))
                            .put("durationMs", segmentDurationMs).put("encodedBytes", segmentBytes))
                        mainHandler.removeCallbacks(autoStopRunnable)
                        recording = null
                        isRecordingPaused = false
                        if (event.hasError()) failure = "녹화 오류 ${event.error}: ${event.cause?.message.orEmpty()}"
                        if (segmentBytes == 0L || segmentDurationMs == 0L) failure = failure ?: "유효한 영상 데이터가 없습니다"
                        segmentEntry?.let { com.example.camera2study.util.RecordingJournal.finishSegment(context,
                            journal, it, segmentDurationMs, segmentBytes, failure, event.outputResults.outputUri.toString()) }
                        sessionDurationMs += segmentDurationMs
                        sessionBytes += segmentBytes
                        segmentDurationMs = 0
                        segmentBytes = 0
                        currentRepeatCount++
                        if (isAutoStopping && !stopRequested && failure == null && !released &&
                            sessionPlan.continues(currentRepeatCount, sessionDurationMs)) {
                            publish(RecordingPhase.BETWEEN_SEGMENTS, "다음 파일 준비 중")
                            mainHandler.postDelayed(restartRunnable, 100L)
                        } else {
                            if (failure != null && !stopRequested) stopReason = "recording_error"
                            else if (isAutoStopping && !stopRequested) stopReason =
                                if (sessionPlan.maxFiles > 0 && currentRepeatCount >= sessionPlan.maxFiles) "file_limit" else "time_limit"
                            finishSession()
                        }
                    }
                }
                onRecordingEvent?.invoke(event)
            }
        } catch (e: Exception) {
            failure = "녹화 시작 실패: ${e.message}"
            stopReason = "start_error"
            segmentEntry?.let { com.example.camera2study.util.RecordingJournal.finishSegment(context,
                journal, it, 0, 0, failure, "") }
            finishSession()
        }
    }

    private fun finishSession() {
        if (sessionFinished) return
        sessionFinished = true
        // Late callbacks from a closed/timed-out recorder must not alter a later session.
        recordingToken++
        mainHandler.removeCallbacks(autoStopRunnable)
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.removeCallbacks(healthCheck)
        stopRequested = true
        releaseWakeLock()
        audioHealth.finish(sessionDurationMs + segmentDurationMs)
        val audioSummary = audioHealth.snapshot(sessionDurationMs + segmentDurationMs)
        session?.let { journal ->
            com.example.camera2study.util.RecordingJournal.audioSummary(journal, audioSummary)
            segmentEntry?.takeIf { it.optString("status") == "RECORDING" }?.let {
                com.example.camera2study.util.RecordingJournal.finishSegment(context, journal, it,
                    segmentDurationMs, segmentBytes, failure ?: "저장 완료 확인 필요", "")
            }
            com.example.camera2study.util.RecordingJournal.finish(context, journal, failure, stopReason)
        }
        publish(if (failure == null) RecordingPhase.COMPLETED else RecordingPhase.ERROR,
            failure ?: if (audioSummary.hadIssues) "저장 완료 · 소리 확인 필요" else "저장 완료")
        onFinalizeEnded?.invoke()
    }

    fun resetRepeatCount() { if (!isRecording()) currentRepeatCount = 0 }

    @SuppressLint("InvalidWakeLockTag")
    private fun renewWakeLock(timeoutMs: Long) {
        if (wakeLock == null) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Camera2Study:recording").apply { setReferenceCounted(false) }
        }
        wakeLock?.acquire(timeoutMs)
    }

    private fun releaseWakeLock() { if (wakeLock?.isHeld == true) wakeLock?.release() }
    fun pauseRecording() { if (recordingState.phase == RecordingPhase.RECORDING) recording?.pause() }
    fun resumeRecording() { if (isRecordingPaused) recording?.resume() }
    fun isPaused(): Boolean = isRecordingPaused

    fun stopRecording(reason: String = "user") {
        if (!isRecording()) return
        recordDiagnostic("stop_requested", org.json.JSONObject().put("reason", reason))
        stopReason = reason
        stopRequested = true
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.removeCallbacks(autoStopRunnable)
        if (recording != null) {
            publish(RecordingPhase.FINALIZING, "파일 저장 중")
            recording?.stop()
        } else finishSession()
    }

    fun isRecording(): Boolean = recordingState.active && recordingState.sessionId == session?.optString("id")

    fun release() {
        if (isRecording()) stopRecording("service_destroyed")
        released = true
        cancelPendingPreparation()
        orientationListener.disable()
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.removeCallbacks(autoStopRunnable)
        mainHandler.removeCallbacks(healthCheck)
        onRecordingEvent = null
        onCameraChanged = null
        onZoomChanged = null
        onError = null
        onControlsApplied = null
        releaseWakeLock()
        unbindOwnedUseCases()
        lookProcessor?.close(); lookProcessor = null
        camera = null
        videoCapture = null
    }
}
