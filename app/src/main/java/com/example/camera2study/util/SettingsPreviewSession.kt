package com.example.camera2study.util

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.CameraUnavailableException
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.example.camera2study.CameraController
import com.example.camera2study.RecordingRuntime
import com.example.camera2study.util.CameraSettingsStore.CameraSettings

/** An unsaved preview owns its use cases and never writes preferences or starts a recording. */
class SettingsPreviewSession(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val view: PreviewView,
    private val handoffDeadlineMs: Long,
    private val mayUsePreview: () -> Boolean,
    private val state: (String) -> Unit
) {
    private var controller: CameraController? = null
    private var bound: CameraSettings? = null
    private var latest: CameraSettings? = null
    private var latestOriginal = false
    private var initialReady = false
    private var closed = false
    private val handler = Handler(Looper.getMainLooper())
    private val retryBudget = PreviewHandoffRetry(handoffDeadlineMs)
    private var pendingRetry: Runnable? = null

    fun show(settings: CameraSettings, original: Boolean) {
        if (closed) return
        if (!mayUsePreview() || RecordingRuntime.state.value?.active == true) {
            close()
            return
        }
        latest = settings
        latestOriginal = original
        if (pendingRetry != null) return
        // Only capture geometry/exposure changes require binding. Color sliders update the
        // same effect processor, while output/segment settings do not touch the camera.
        val key = settings.copy(look = VideoLook(), audioEnabled = false, privateSave = true,
            maxDurationMs = 1_800_000L, maxRepeatCount = 0, totalDurationMs = 0)
        val look = if (original) VideoLook() else settings.look
        val existing = controller
        if (existing == null) {
            val camera = CameraController(context)
            controller = camera
            camera.keepPreviewEffect = true
            camera.onError = { state("미리보기 확인 필요 · $it") }
            camera.onBindingError = { error -> if (controller === camera) retryInitialBinding(error) }
            camera.onControlsApplied = {
                if (controller === camera) {
                    initialReady = true
                    cancelRetry()
                    state("저장 전 미리보기 · AF 자동 · AWB 자동")
                }
            }
            CameraSettingsStore.applyToController(camera, key.copy(look = look))
            camera.attachPreview(view)
            bound = key
            state("미리보기 준비 중")
            camera.init(owner)
        } else if (bound != key) {
            bound = key
            state("미리보기 설정 적용 중")
            CameraSettingsStore.applyToController(existing, key.copy(look = look))
        } else {
            existing.updatePreviewLook(look)
        }
    }

    private fun retryInitialBinding(error: Throwable) {
        if (closed || pendingRetry != null || !mayUsePreview() || RecordingRuntime.state.value?.active == true) return
        val delay = retryBudget.nextDelay(SystemClock.uptimeMillis(), isPreviewResourceBusy(error), initialReady) ?: return
        state("녹화 카메라 해제를 기다리는 중 · 미리보기를 다시 연결합니다")
        val retry = Runnable {
            pendingRetry = null
            if (closed || initialReady || !mayUsePreview() || RecordingRuntime.state.value?.active == true) return@Runnable
            if (SystemClock.uptimeMillis() >= handoffDeadlineMs) {
                state("카메라가 아직 사용 중이어서 설정 미리보기를 연결하지 못했습니다")
                return@Runnable
            }
            val settings = latest ?: return@Runnable
            releaseController()
            show(settings, latestOriginal)
        }
        pendingRetry = retry
        handler.postDelayed(retry, delay)
    }

    private fun cancelRetry() {
        pendingRetry?.let { handler.removeCallbacks(it) }
        pendingRetry = null
    }

    private fun releaseController() {
        controller?.release()
        controller = null
        bound = null
    }

    fun close() {
        closed = true
        cancelRetry()
        latest = null
        releaseController()
    }
}

internal fun isPreviewResourceBusy(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(8).any {
    when (it) {
        is CameraUnavailableException -> it.reason == CameraUnavailableException.CAMERA_IN_USE ||
            it.reason == CameraUnavailableException.CAMERA_MAX_IN_USE
        is CameraAccessException -> it.reason == CameraAccessException.CAMERA_IN_USE ||
            it.reason == CameraAccessException.MAX_CAMERAS_IN_USE
        else -> false
    }
}
