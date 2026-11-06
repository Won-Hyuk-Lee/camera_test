package com.example.camera2study.util

import android.content.Context
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
    private val state: (String) -> Unit
) {
    private var controller: CameraController? = null
    private var bound: CameraSettings? = null

    fun show(settings: CameraSettings, original: Boolean) {
        if (RecordingRuntime.state.value?.active == true) {
            close()
            state("녹화 중에는 미리보기를 사용하지 않습니다. 저장한 설정은 다음 촬영부터 적용됩니다.")
            return
        }
        // Only capture geometry/exposure changes require binding. Color sliders update the
        // same effect processor, while output/segment settings do not touch the camera.
        val key = settings.copy(look = VideoLook(), audioEnabled = false, privateSave = true,
            maxDurationMs = 1_800_000L, maxRepeatCount = 0, totalDurationMs = 0)
        val look = if (original) VideoLook() else settings.look
        var camera = controller
        if (camera == null) {
            camera = CameraController(context)
            controller = camera
            camera.keepPreviewEffect = true
            camera.onError = { state("미리보기 확인 필요 · $it") }
            camera.onControlsApplied = { state("저장 전 미리보기 · AF 자동 · AWB 자동") }
            CameraSettingsStore.applyToController(camera, key.copy(look = look))
            camera.attachPreview(view)
            bound = key
            state("미리보기 준비 중")
            camera.init(owner)
        } else if (bound != key) {
            bound = key
            state("미리보기 설정 적용 중")
            CameraSettingsStore.applyToController(camera, key.copy(look = look))
        } else {
            camera.updatePreviewLook(look)
        }
    }

    fun close() {
        controller?.release()
        controller = null
        bound = null
    }
}
