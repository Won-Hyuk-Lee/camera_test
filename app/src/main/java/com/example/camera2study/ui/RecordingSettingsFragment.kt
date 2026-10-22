package com.example.camera2study.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.camera2study.CameraForegroundService
import com.example.camera2study.R
import com.example.camera2study.util.CameraSettingsStore
import com.example.camera2study.util.CameraSettingsStore.CameraSettings

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class RecordingSettingsFragment : Fragment() {
    private val quick get() = arguments?.getBoolean("quick", false) == true
    private var draft = CameraSettings()
    private var list: LinearLayout? = null
    private var provider: ProcessCameraProvider? = null
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draft = CameraSettingsStore.load(requireContext(), quick)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 32)
        }
        list = content
        return ScrollView(ctx).apply { isFillViewport = true; fitsSystemWindows = true; addView(content) }
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        render()
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            if (list == null) return@addListener
            runCatching { provider = future.get(); ready = true }.onFailure { toast("카메라 정보 확인 실패: ${it.message}") }
            render()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun label(text: String) = TextView(requireContext()).also {
        it.text = text; it.textSize = 16f; it.setPadding(0, 20, 0, 8); list?.addView(it)
    }
    private fun button(text: String, action: () -> Unit) = Button(requireContext()).also {
        it.text = text; it.setOnClickListener { action() }; list?.addView(it)
    }
    private fun <T> choice(title: String, options: List<Pair<String, T>>, selected: T, change: (T) -> Unit) {
        label(title)
        val spinner = Spinner(requireContext())
        spinner.contentDescription = title
        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, options.map { it.first })
        spinner.setSelection(options.indexOfFirst { it.second == selected }.coerceAtLeast(0), false)
        list?.addView(spinner)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = options[position].second
                if (value != selected) change(value)
            }
        }
    }
    private fun toggle(title: String, checked: Boolean, change: (Boolean) -> Unit) {
        val check = CheckBox(requireContext()).apply { text = title; isChecked = checked }
        check.setOnCheckedChangeListener { _, value -> change(value) }
        list?.addView(check)
    }

    private fun selectedInfo(): CameraInfo? {
        val facing = if (draft.lensFacingBack) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        val infos = provider?.availableCameraInfos.orEmpty().filter { it.lensFacing == facing }
        return infos.firstOrNull { Camera2CameraInfo.from(it).cameraId == draft.cameraId } ?: infos.firstOrNull()
    }

    private fun render() {
        val container = list ?: return
        container.removeAllViews()
        button("뒤로") { parentFragmentManager.popBackStack() }
        label(if (quick) "빠른 녹화 전용 설정" else "동영상 촬영 설정")
        label("저장한 설정은 다음 촬영부터 적용됩니다. 초점은 연속 자동초점을 사용합니다.")
        if (quick) button("현재 촬영 설정 가져오기") {
            draft = CameraSettingsStore.load(requireContext()); render()
        }
        choice("카메라", listOf("후면" to true, "전면" to false), draft.lensFacingBack) {
            draft = draft.copy(lensFacingBack = it, cameraId = null, zoomRatio = 1f, zoomModeRatio = 1f, exposureEv = 0f); render()
        }
        val info = selectedInfo()
        if (!ready || info == null) { label("카메라 정보를 확인 중입니다. 권한을 허용해 주세요."); return }
        val facing = if (draft.lensFacingBack) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        val lensOptions = listOf("기본 카메라 · 자동 렌즈 전환" to null) + provider!!.availableCameraInfos.filter { it.lensFacing == facing }.map {
            val id = Camera2CameraInfo.from(it).cameraId
            "카메라 $id" to id
        }
        choice("렌즈", lensOptions, draft.cameraId) {
            draft = draft.copy(cameraId = it, zoomRatio = 1f, zoomModeRatio = 1f); render()
        }
        val zoom = info.zoomState.value
        val min = zoom?.minZoomRatio ?: 1f
        val max = zoom?.maxZoomRatio ?: 1f
        val zoomValues = (listOf(min, 1f, 2f, 3f, 5f, 10f, max, draft.zoomRatio).filter { it in min..max }).distinct().sorted()
        choice("배율 · 선택한 렌즈 기준", zoomValues.map { "%.2f×".format(it) to it }, draft.zoomRatio.coerceIn(min, max)) {
            draft = draft.copy(zoomRatio = it, zoomModeRatio = it)
        }
        val caps = Recorder.getVideoCapabilities(info)
        val hdrRange = caps.supportedDynamicRanges.firstOrNull { it != DynamicRange.SDR && it.bitDepth == DynamicRange.BIT_DEPTH_10_BIT }
        if (hdrRange == null && draft.hdrEnabled) draft = draft.copy(hdrEnabled = false)
        toggle("HDR (지원 시)", draft.hdrEnabled) {
            if (it && hdrRange == null) toast("이 카메라는 HDR 녹화를 지원하지 않습니다")
            else draft = draft.copy(hdrEnabled = it)
            render()
        }
        val qualities = caps.getSupportedQualities(if (draft.hdrEnabled) hdrRange!! else DynamicRange.SDR)
        if (draft.quality !in qualities && qualities.isNotEmpty()) {
            draft = draft.copy(quality = qualities.first())
            label("이 카메라에서 지원하는 화질로 선택을 조정했습니다. 저장 후 적용됩니다.")
        }
        choice("영상 품질", qualities.map { qualityName(it) to it }, draft.quality) { draft = draft.copy(quality = it) }
        val rates = listOf(30, 60).filter { fps -> info.supportedFrameRateRanges.any { it.contains(fps) } }.ifEmpty { listOf(30) }
        if (draft.fps !in rates) draft = draft.copy(fps = rates.first())
        choice("프레임 속도", rates.map { "$it FPS" to it }, draft.fps) { draft = draft.copy(fps = it) }
        val exposure = info.exposureState
        val step = exposure.exposureCompensationStep.toFloat()
        val evs = if (exposure.isExposureCompensationSupported && step > 0) (exposure.exposureCompensationRange.lower..exposure.exposureCompensationRange.upper).map { it * step } else listOf(0f)
        val selectedEv = evs.minByOrNull { kotlin.math.abs(it - draft.exposureEv) } ?: 0f
        draft = draft.copy(exposureEv = selectedEv)
        choice("밝기 · 노출 보정", evs.map { "%+.2f EV".format(it) to it }, selectedEv) { draft = draft.copy(exposureEv = it) }
        toggle("마이크 녹음", draft.audioEnabled) { draft = draft.copy(audioEnabled = it) }
        toggle("비공개 보관함에 저장", draft.privateSave) { draft = draft.copy(privateSave = it) }
        toggle("전면 저장 영상 좌우 반전", draft.mirrorFront) { draft = draft.copy(mirrorFront = it) }
        choice("저장 영상 방향", listOf("시작 시 기기 방향" to "AUTO", "가로" to "LANDSCAPE", "세로" to "PORTRAIT"), draft.orientation) { draft = draft.copy(orientation = it) }
        choice("파일당 길이", listOf("10초 · 시험용" to 10_000L, "5분" to 300_000L, "10분" to 600_000L, "30분" to 1_800_000L), draft.maxDurationMs) { draft = draft.copy(maxDurationMs = it) }
        val ending = if (draft.totalDurationMs > 0) "TIME" else if (draft.maxRepeatCount > 0) "FILES" else "MANUAL"
        choice("전체 종료 조건", listOf("직접 중지" to "MANUAL", "총 파일 수" to "FILES", "총 녹화 시간" to "TIME"), ending) {
            draft = when (it) { "FILES" -> draft.copy(maxRepeatCount = 2, totalDurationMs = 0); "TIME" -> draft.copy(maxRepeatCount = 0, totalDurationMs = 3_600_000); else -> draft.copy(maxRepeatCount = 0, totalDurationMs = 0) }
            render()
        }
        if (ending == "FILES") choice("총 파일 수", listOf(1, 2, 4, 8).map { "$it 개" to it }, draft.maxRepeatCount) { draft = draft.copy(maxRepeatCount = it) }
        if (ending == "TIME") choice("총 녹화 시간", listOf("20초 · 시험용" to 20_000L, "30분" to 1_800_000L, "60분" to 3_600_000L, "120분" to 7_200_000L), draft.totalDurationMs) { draft = draft.copy(totalDurationMs = it) }
        button("설정 저장") {
            CameraSettingsStore.save(requireContext(), draft, quick)
            toast("설정을 저장했습니다. 다음 촬영부터 적용됩니다")
        }
        if (quick) button("저장 후 10초 시험 촬영") {
            CameraSettingsStore.save(requireContext(), draft, true)
            CameraForegroundService.requestQuickRecording(requireContext(), test = true)
            toast("10초 시험 촬영을 요청했습니다. 촬영 결과에서 확인하세요")
        }
    }

    private fun toast(text: String) { context?.let { Toast.makeText(it, text, Toast.LENGTH_LONG).show() } }
    private fun qualityName(quality: Quality): String = when (quality) { Quality.UHD -> "UHD · 4K"; Quality.FHD -> "FHD · 1080p"; Quality.HD -> "HD · 720p"; else -> "SD" }
    override fun onDestroyView() { list = null; super.onDestroyView() }
    companion object { fun create(quick: Boolean) = RecordingSettingsFragment().apply { arguments = Bundle().apply { putBoolean("quick", quick) } } }
}
