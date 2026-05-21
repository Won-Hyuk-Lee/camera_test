package com.example.camera2study

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.provider.MediaStore
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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

@OptIn(ExperimentalCamera2Interop::class)
class CameraController(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
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

    var onRecordingEvent: ((VideoRecordEvent) -> Unit)? = null
    var onCameraChanged: ((String?) -> Unit)? = null

    val backLenses: List<LensInfo> by lazy { CameraUtils.listBackLenses(context) }

    fun init(
        owner: LifecycleOwner,
        view: PreviewView,
        onReady: () -> Unit = {}
    ) {
        lifecycleOwner = owner
        previewView = view
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

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return
        val view = previewView ?: return

        provider.unbindAll()

        val previewBuilder = Preview.Builder()
        val previewUseCase = previewBuilder.build().apply {
            setSurfaceProvider(view.surfaceProvider)
        }

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()
        val videoUseCase = VideoCapture.withOutput(recorder)

        val selector = buildSelector()
        camera = provider.bindToLifecycle(owner, selector, previewUseCase, videoUseCase)
        videoCapture = videoUseCase

        applyOptionsLive()

        val activeId = camera?.let { Camera2CameraInfo.from(it.cameraInfo).cameraId }
        onCameraChanged?.invoke(activeId)
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

        aperture?.let {
            builder.setCaptureRequestOption(CaptureRequest.LENS_APERTURE, it)
        }

        control.setCaptureRequestOptions(builder.build())
    }

    fun switchFacing() {
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

        recording = vc.output
            .prepareRecording(context, output)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                onRecordingEvent?.invoke(event)
                if (event is VideoRecordEvent.Finalize) {
                    recording = null
                }
            }
    }

    fun stopRecording() {
        recording?.stop()
        recording = null
    }

    fun isRecording(): Boolean = recording != null

    fun release() {
        recording?.stop()
        recording = null
        cameraProvider?.unbindAll()
        camera = null
        videoCapture = null
    }
}
