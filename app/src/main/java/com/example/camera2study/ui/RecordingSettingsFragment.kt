package com.example.camera2study.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.camera2study.CameraForegroundService
import com.example.camera2study.EntryLockListener
import com.example.camera2study.MainActivity
import com.example.camera2study.RecordingRuntime
import com.example.camera2study.R
import com.example.camera2study.util.VideoLook
import com.example.camera2study.util.CameraSettingsStore
import com.example.camera2study.util.CameraSettingsStore.CameraSettings
import com.example.camera2study.util.VideoConfigurationSupport
import com.example.camera2study.util.SettingsPreviewSession

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class RecordingSettingsFragment : Fragment(), EntryLockListener {
    private val quick get() = arguments?.getBoolean("quick", false) == true
    private var baseline = CameraSettings()
    private var savedFacingBack = true
    private var statusLabel: TextView? = null
    private var scopeLabel: TextView? = null
    private var saveButton: Button? = null
    private var testButton: Button? = null
    private var settingsScroll: ScrollView? = null
    private var draft = CameraSettings()
        set(value) { field = value; updateSavedStatus(); schedulePreview() }
    private fun hasUnsavedChanges() = draft != baseline || draft.lensFacingBack != savedFacingBack
    private fun updateSavedStatus() {
        statusLabel?.text = if (hasUnsavedChanges()) "미저장 변경 · 저장 후 다음 촬영에 적용" else "저장된 설정"
        scopeLabel?.text = (if (quick) "빠른 녹화 설정" else "일반 촬영 설정") + " · " + if (draft.lensFacingBack) "후면" else "전면"
    }
    private var list: LinearLayout? = null
    private var provider: ProcessCameraProvider? = null
    private var ready = false
    private var details = false
    private var manualExpanded = false
    private var previewView: PreviewView? = null
    private var previewStatus: TextView? = null
    private var compareOriginal: CheckBox? = null
    private var originalPreview = false
    private var previewSession: SettingsPreviewSession? = null
    private val previewHandler = Handler(Looper.getMainLooper())
    private val previewUpdate = Runnable { updatePreview() }
    private val dialogs = mutableSetOf<android.app.Dialog>()
    private fun authenticated() = (activity as? MainActivity)?.isEntryAuthenticated() == true
    private fun showDialog(builder: com.google.android.material.dialog.MaterialAlertDialogBuilder) {
        if (!authenticated()) return
        val dialog = builder.create()
        dialogs.add(dialog)
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        dialog.show()
    }
    override fun onEntryLocked() {
        dialogs.toList().forEach { it.dismiss() }
        stopPreview()
    }
    override fun onEntryUnlocked() { schedulePreview() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = CameraSettingsStore.load(requireContext(), quick)
        savedFacingBack = savedInstanceState?.getBoolean("savedFacingBack", saved.lensFacingBack) ?: saved.lensFacingBack
        requireActivity().onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { leave() }
        })
        draft = savedInstanceState?.getBundle("draft")?.let(CameraSettingsStore::fromBundle)
            ?: saved
        baseline = savedInstanceState?.getBundle("baseline")?.let(CameraSettingsStore::fromBundle)
            ?: CameraSettingsStore.loadForFacing(requireContext(), draft.lensFacingBack, quick)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("draft", CameraSettingsStore.toBundle(draft))
        outState.putBundle("baseline", CameraSettingsStore.toBundle(baseline))
        outState.putBoolean("savedFacingBack", savedFacingBack)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; fitsSystemWindows = true
            setBackgroundColor(android.graphics.Color.rgb(18, 20, 25))
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        scopeLabel = TextView(ctx).apply { textSize = 20f; setTextColor(android.graphics.Color.WHITE); setPadding(0, dp(4), 0, dp(4)) }
        root.addView(scopeLabel)
        statusLabel = TextView(ctx).apply { textSize = 14f; setTextColor(android.graphics.Color.LTGRAY); setPadding(0, 0, 0, dp(8)) }
        root.addView(statusLabel)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        list = content
        settingsScroll = ScrollView(ctx).apply { isFillViewport = true; addView(content) }
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val body = LinearLayout(ctx).apply { orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val previewColumn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            if (landscape) setPadding(0, 0, dp(12), 0)
        }
        body.addView(previewColumn, if (landscape) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, -2))
        previewView = PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
            contentDescription = "저장 전 촬영 설정 미리보기"
        }
        val portraitPreviewHeight = (resources.configuration.screenHeightDp * .32f).toInt().coerceIn(220, 300)
        previewColumn.addView(previewView, if (landscape) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(-1, dp(portraitPreviewHeight)))
        previewStatus = TextView(ctx).apply {
            textSize = 12f; setTextColor(android.graphics.Color.LTGRAY)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            text = "저장 전 미리보기 · 설정은 저장 버튼으로 적용"
        }
        previewColumn.addView(previewStatus)
        compareOriginal = CheckBox(ctx).apply {
            text = "원본과 비교 · 저장할 필터는 유지"; textSize = 13f; setTextColor(android.graphics.Color.WHITE)
            minimumHeight = dp(48)
            setOnCheckedChangeListener { _, checked ->
                if (authenticated()) { originalPreview = checked; schedulePreview() }
            }
        }
        previewColumn.addView(compareOriginal)
        body.addView(settingsScroll, if (landscape) LinearLayout.LayoutParams(0, -1, 1.25f) else LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        fun action(text: String, click: () -> Unit) = Button(ctx).apply {
            this.text = text; textSize = 14f; minimumHeight = dp(56)
            setOnClickListener { if (authenticated()) click() }
            footer.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        action("뒤로") { leave() }
        saveButton = action("설정 저장") { if (saveDraft()) toast("설정을 저장했습니다. 다음 촬영부터 적용됩니다") }
        if (quick) testButton = action("10초 시험") {
            if (saveDraft()) {
                stopPreview()
                CameraForegroundService.requestQuickRecording(requireContext(), test = true)
                toast("저장한 설정으로 10초 시험 촬영을 요청했습니다")
            }
        }
        root.addView(footer)
        updateSavedStatus()
        return root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        render()
        RecordingRuntime.state.observe(viewLifecycleOwner) { stateValue ->
            if (stateValue.active) {
                stopPreview()
                previewStatus?.text = "녹화 중에는 미리보기를 중단합니다. 저장한 설정은 다음 촬영부터 적용됩니다."
            } else schedulePreview()
            refreshActions()
        }
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            if (list == null) return@addListener
            runCatching { provider = future.get(); ready = true }.onFailure { toast("카메라 정보 확인 실패: ${it.message}") }
            render()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun label(text: String) = TextView(requireContext()).also {
        it.text = text; it.setTextColor(android.graphics.Color.WHITE); it.textSize = 14f; it.setPadding(0, dp(12), 0, dp(4)); list?.addView(it)
    }
    private fun section(text: String) = label(text).apply {
        textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(android.graphics.Color.rgb(167, 198, 255)); setPadding(0, dp(24), 0, dp(8))
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun button(text: String, action: () -> Unit) = Button(requireContext()).also {
        it.text = text; it.setOnClickListener { if (authenticated()) action() }; list?.addView(it)
    }
    private fun <T> choice(title: String, options: List<Pair<String, T>>, selected: T, change: (T) -> Unit) {
        label(title)
        val spinner = Spinner(requireContext())
        spinner.contentDescription = title
        spinner.minimumHeight = (48 * resources.displayMetrics.density).toInt()
        spinner.adapter = object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_dropdown_item, options.map { it.first }) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also { (it as TextView).setTextColor(android.graphics.Color.WHITE) }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getDropDownView(position, convertView, parent).also {
                    (it as TextView).setTextColor(android.graphics.Color.WHITE)
                    it.setBackgroundColor(android.graphics.Color.rgb(30, 32, 38))
                    it.minimumHeight = (48 * resources.displayMetrics.density).toInt()
                }
        }
        spinner.setSelection(options.indexOfFirst { it.second == selected }.coerceAtLeast(0), false)
        list?.addView(spinner)
        var lastSelection = selected
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = options[position].second
                if (value != lastSelection && authenticated()) { lastSelection = value; change(value) }
            }
        }
    }
    private fun toggle(title: String, checked: Boolean, change: (Boolean) -> Unit) {
        val check = CheckBox(requireContext()).apply { text = title; setTextColor(android.graphics.Color.WHITE); isChecked = checked }
        check.setOnCheckedChangeListener { _, value -> if (authenticated()) change(value) }
        list?.addView(check)
    }

    private fun selectedInfo(): CameraInfo? {
        val facing = if (draft.lensFacingBack) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        val infos = provider?.availableCameraInfos.orEmpty().filter { it.lensFacing == facing }
        return infos.firstOrNull { Camera2CameraInfo.from(it).cameraId == draft.cameraId } ?: infos.firstOrNull()
    }

    private fun render() {
        val container = list ?: return
        val scroll = settingsScroll
        val scrollY = scroll?.scrollY ?: 0
        container.removeAllViews()
        updateSavedStatus()
        label(if (quick) "위젯과 빠른 녹화는 이 설정으로 시작합니다. 저장해야 다음 촬영에 적용됩니다." else "촬영 화면에서 시작할 때 사용할 설정입니다. 빠른 녹화 설정과 별도로 저장됩니다.")
        section("프로필과 렌즈")
        if (quick) button("현재 촬영 설정 가져오기") {
            draft = CameraSettingsStore.load(requireContext()); render()
        }
        choice("카메라", listOf("후면" to true, "전면" to false), draft.lensFacingBack) {
            if (draft != baseline) {
                showDialog(com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("카메라 전환")
                    .setMessage("현재 변경을 저장하고 다른 카메라 설정을 불러올까요?")
                    .setPositiveButton("저장 후 전환") { _, _ -> if (saveDraft()) switchFacing(it) }
                    .setNegativeButton("변경 취소 후 전환") { _, _ -> if (authenticated()) switchFacing(it) }
                    .setNeutralButton("돌아가기") { _, _ -> if (authenticated()) render() }.setOnCancelListener { if (authenticated()) render() })
            } else switchFacing(it)
        }
        val info = selectedInfo()
        if (!ready || info == null) { label("카메라 정보를 확인 중입니다. 권한을 허용해 주세요."); refreshActions(); return }
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
        draft = draft.copy(zoomRatio = draft.zoomRatio.coerceIn(min, max))
        choice("배율 · 선택한 렌즈 기준", zoomValues.map { "%.2f×".format(it) to it }, draft.zoomRatio.coerceIn(min, max)) {
            draft = draft.copy(zoomRatio = it, zoomModeRatio = it)
        }
        section("화질과 밝기")
        label("16:9 동영상 · 초점 자동 · 화이트밸런스 자동")
        val caps = Recorder.getVideoCapabilities(info)
        val hdrRange = caps.supportedDynamicRanges.firstOrNull { it != DynamicRange.SDR && it.bitDepth == DynamicRange.BIT_DEPTH_10_BIT }
        if (hdrRange == null && draft.hdrEnabled) draft = draft.copy(hdrEnabled = false)
        toggle("HDR (지원 시)", draft.hdrEnabled) {
            if (it && hdrRange == null) toast("이 카메라는 HDR 녹화를 지원하지 않습니다")
            else if (it && !draft.look.isIdentity()) toast("색보정은 SDR 전용입니다. 색보정을 초기화한 뒤 HDR을 켜세요")
            else draft = draft.copy(hdrEnabled = it)
            render()
        }
        val qualities = caps.getSupportedQualities(VideoConfigurationSupport.dynamicRange(info, draft.hdrEnabled) ?: DynamicRange.SDR)
        if (draft.quality !in qualities && qualities.isNotEmpty()) {
            draft = draft.copy(quality = qualities.first())
            label("이 카메라에서 지원하는 화질로 선택을 조정했습니다. 저장 후 적용됩니다.")
        }
        choice("영상 품질", qualities.map { qualityName(it) to it }, draft.quality) { draft = draft.copy(quality = it); render() }
        val rates = VideoConfigurationSupport.supportedFps(info, draft.quality, draft.hdrEnabled, !draft.look.isIdentity())
        if (rates.isEmpty()) label("현재 렌즈·화질·HDR·색보정 조합을 지원하지 않습니다. 다른 설정을 선택해 주세요.")
        else {
            if (draft.fps !in rates) { draft = draft.copy(fps = rates.first()); label("이 조합에서 지원하는 프레임 속도로 조정했습니다. 저장 후 적용됩니다.") }
            choice("프레임 속도", rates.map { "$it FPS" to it }, draft.fps) { draft = draft.copy(fps = it); render() }
        }
        val exposure = info.exposureState
        val step = exposure.exposureCompensationStep.toFloat()
        val evs = if (exposure.isExposureCompensationSupported && step > 0) (exposure.exposureCompensationRange.lower..exposure.exposureCompensationRange.upper).map { it * step } else listOf(0f)
        val selectedEv = evs.minByOrNull { kotlin.math.abs(it - draft.exposureEv) } ?: 0f
        draft = draft.copy(exposureEv = selectedEv)
        choice("밝기 · 노출 보정", evs.map { "%+.2f EV".format(it) to it }, selectedEv) { draft = draft.copy(exposureEv = it) }
        section("영상 색보정")
        lookControls()
        section("소리와 저장")
        toggle("마이크 녹음", draft.audioEnabled) { draft = draft.copy(audioEnabled = it) }
        toggle("비공개 보관함에 저장", draft.privateSave) { draft = draft.copy(privateSave = it) }
        toggle("전면 저장 영상 좌우 반전", draft.mirrorFront) { draft = draft.copy(mirrorFront = it) }
        choice("저장 영상 방향", listOf("시작 시 기기 방향" to "AUTO", "가로" to "LANDSCAPE", "세로" to "PORTRAIT"), draft.orientation) { draft = draft.copy(orientation = it) }
        section("파일 분할과 촬영 종료")
        choice("파일당 길이", listOf("10초 · 시험용" to 10_000L, "5분" to 300_000L, "10분" to 600_000L, "30분" to 1_800_000L), draft.maxDurationMs) { draft = draft.copy(maxDurationMs = it) }
        val ending = if (draft.totalDurationMs > 0) "TIME" else if (draft.maxRepeatCount > 0) "FILES" else "MANUAL"
        choice("전체 종료 조건", listOf("직접 중지" to "MANUAL", "총 파일 수" to "FILES", "총 녹화 시간" to "TIME"), ending) {
            draft = when (it) { "FILES" -> draft.copy(maxRepeatCount = 2, totalDurationMs = 0); "TIME" -> draft.copy(maxRepeatCount = 0, totalDurationMs = 3_600_000); else -> draft.copy(maxRepeatCount = 0, totalDurationMs = 0) }
            render()
        }
        if (ending == "FILES") choice("총 파일 수", listOf(1, 2, 4, 8).map { "$it 개" to it }, draft.maxRepeatCount) { draft = draft.copy(maxRepeatCount = it) }
        if (ending == "TIME") choice("총 녹화 시간", listOf("20초 · 시험용" to 20_000L, "30분" to 1_800_000L, "60분" to 3_600_000L, "120분" to 7_200_000L), draft.totalDurationMs) { draft = draft.copy(totalDurationMs = it) }
        if (ending == "TIME") label("분할 후 남은 시간이 1초 미만이면 짧은 파일을 추가하지 않고 종료합니다.")
        section("고급 노출과 초기화")
        button(if (manualExpanded) "수동 노출 접기" else "수동 노출 펼치기") { manualExpanded = !manualExpanded; render() }
        if (manualExpanded) manualControls(info)
        button("이 프로필 기본값으로 초기화") {
            showDialog(com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("설정 초기화")
                .setMessage("현재 편집 중인 전면/후면 프로필만 기본값으로 되돌립니다. 설정 저장을 눌러야 적용됩니다.")
                .setPositiveButton("초기화") { _, _ -> if (authenticated()) { draft = CameraSettings(lensFacingBack = draft.lensFacingBack); render() } }
                .setNegativeButton("취소", null))
        }
        refreshActions()
        schedulePreview()
        scroll?.post { if (list === container) scroll.scrollTo(0, scrollY) }
    }

    private fun configurationProblem(): String? {
        val info = selectedInfo() ?: return "카메라 정보를 확인한 후 저장해 주세요"
        if (!ready) return "카메라 정보를 확인한 후 저장해 주세요"
        return VideoConfigurationSupport.problem(info, draft.quality, draft.fps, draft.hdrEnabled, !draft.look.isIdentity())
    }
    private fun refreshActions() {
        val valid = ready && configurationProblem() == null
        saveButton?.isEnabled = valid
        val active = RecordingRuntime.state.value?.active == true
        testButton?.isEnabled = valid && !active
        compareOriginal?.isEnabled = valid && !active && !draft.hdrEnabled && !draft.look.isIdentity()
        if (compareOriginal?.isEnabled != true) {
            originalPreview = false
            compareOriginal?.isChecked = false
        }
    }

    private fun saveDraft(): Boolean {
        if (!authenticated()) return false
        configurationProblem()?.let { toast(it); return false }
        CameraSettingsStore.save(requireContext(), draft, quick)
        baseline = draft
        savedFacingBack = draft.lensFacingBack
        updateSavedStatus()
        return true
    }
    private fun switchFacing(back: Boolean) {
        baseline = CameraSettingsStore.loadForFacing(requireContext(), back, quick)
        draft = baseline; render()
    }
    private fun leave() {
        if (!authenticated()) return
        if (!hasUnsavedChanges()) { parentFragmentManager.popBackStack(); return }
        showDialog(com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("미저장 설정")
            .setMessage("변경한 설정을 저장할까요?")
            .setPositiveButton("저장 후 나가기") { _, _ -> if (saveDraft()) parentFragmentManager.popBackStack() }
            .setNegativeButton("저장 안 함") { _, _ -> if (authenticated()) parentFragmentManager.popBackStack() }
            .setNeutralButton("계속 편집", null))
    }
    private fun lookControls() {
        label("영상 색보정 · 미리보기와 저장 영상에 함께 적용")
        label("센서 AWB와 별개인 색보정입니다. 어두운 부분을 밝히면 잡음도 보일 수 있습니다.")
        if (draft.hdrEnabled) { label("HDR에서는 색보정을 사용할 수 없습니다."); return }
        choice("색보정 프리셋", VideoLook.presets.map { it.name to it.id }, draft.look.preset) {
            draft = draft.copy(look = VideoLook(preset = it)); render()
        }
        label(VideoLook.presets.first { it.id == draft.look.preset }.description)
        slider("필터 강도", draft.look.strength, false) { draft = draft.copy(look = draft.look.copy(strength = it)) }
        button(if (details) "상세 색보정 접기" else "상세 색보정 펼치기") { details = !details; render() }
        if (details) {
            slider("색온도 · 차갑게 ↔ 따뜻하게", draft.look.temperature) { draft = draft.copy(look = draft.look.copy(temperature = it)) }
            slider("색조 · 초록 ↔ 자홍", draft.look.tint) { draft = draft.copy(look = draft.look.copy(tint = it)) }
            slider("대비", draft.look.contrast) { draft = draft.copy(look = draft.look.copy(contrast = it)) }
            slider("채도", draft.look.saturation) { draft = draft.copy(look = draft.look.copy(saturation = it)) }
        }
        button("색보정 초기화") { draft = draft.copy(look = VideoLook()); render() }
    }
    private fun slider(title: String, value: Float, signed: Boolean = true, change: (Float) -> Unit) {
        val caption = label(title + " · " + (value * 100).toInt())
        val bar = SeekBar(requireContext()).apply {
            max = if (signed) 200 else 100
            progress = (value * 100).toInt() + if (signed) 100 else 0
            contentDescription = title
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) { render() }
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && authenticated()) { val v = progress - if (signed) 100 else 0; change(v / 100f); caption.text = title + " · " + v }
            }
        })
        list?.addView(bar)
    }

    private fun manualControls(info: CameraInfo) {
        val chars = Camera2CameraInfo.from(info)
        val capabilities = chars.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val sensor = capabilities.contains(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        toggle("수동 노출 · 셔터와 감도", draft.proMode) {
            draft = if (it) draft.copy(proMode = true) else draft.copy(proMode = false,
                awbMode = android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO, exposureTimeNs = null, aperture = null)
            render()
        }
        if (!draft.proMode) return
        label("초점과 화이트밸런스는 수동 노출에서도 자동입니다.")
        val range = chars.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        if (sensor && range != null) {
            val shutter = listOf("자동" to null) + listOf(30, 60, 120, 250, 500, 1000).map { "1/$it 초" to (1_000_000_000L / it) }
                .filter { it.second in range.lower..minOf(range.upper, 1_000_000_000L / draft.fps) }
            if (draft.exposureTimeNs !in shutter.map { it.second }) draft = draft.copy(exposureTimeNs = null)
            choice("셔터", shutter, draft.exposureTimeNs) { draft = draft.copy(exposureTimeNs = it); render() }
            if (draft.exposureTimeNs != null) {
                val isoRange = chars.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                if (isoRange != null) {
                    val values = (listOf(isoRange.lower, 100, 200, 400, 800, 1600, 3200, 6400, isoRange.upper, draft.manualIso).filter { it in isoRange.lower..isoRange.upper }).distinct().sorted()
                    draft = draft.copy(manualIso = draft.manualIso.coerceIn(isoRange.lower, isoRange.upper))
                    choice("수동 감도 · ISO", values.map { "ISO " + it to it }, draft.manualIso) { draft = draft.copy(manualIso = it) }
                } else draft = draft.copy(exposureTimeNs = null)
            }
            label("어두운 실내는 자동 노출과 30 FPS를 권장합니다. 수동 셔터에서는 ISO와 셔터로 밝기를 조절하며, ISO를 높이면 잡음이 늘어날 수 있습니다.")
        } else {
            draft = draft.copy(exposureTimeNs = null)
            label("이 렌즈는 수동 셔터를 지원하지 않습니다.")
        }
        val apertures = chars.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf()
        if (apertures.size > 1) choice("조리개", listOf("자동" to null) + apertures.map { "f/$it" to it }, draft.aperture) { draft = draft.copy(aperture = it) }
        else draft = draft.copy(aperture = null)
    }

    private fun toast(text: String) { context?.let { Toast.makeText(it, text, Toast.LENGTH_LONG).show() } }
    private fun qualityName(quality: Quality): String = when (quality) { Quality.UHD -> "UHD · 4K"; Quality.FHD -> "FHD · 1080p"; Quality.HD -> "HD · 720p"; else -> "SD" }
    private fun schedulePreview() {
        if (previewView == null) return
        previewHandler.removeCallbacks(previewUpdate)
        previewHandler.postDelayed(previewUpdate, 180)
    }
    private fun updatePreview() {
        val preview = previewView ?: return
        if (!authenticated() || !isResumed || !preview.isShown || RecordingRuntime.state.value?.active == true) {
            stopPreview()
            return
        }
        configurationProblem()?.let { problem ->
            stopPreview()
            previewStatus?.text = problem
            return
        }
        val session = previewSession ?: SettingsPreviewSession(requireContext(), viewLifecycleOwner, preview) { message ->
            previewStatus?.text = message + if (originalPreview) " · 원본 보기" else ""
        }.also { previewSession = it }
        session.show(draft, originalPreview)
        refreshActions()
    }
    private fun stopPreview() {
        previewHandler.removeCallbacks(previewUpdate)
        previewSession?.close()
        previewSession = null
    }
    override fun onResume() { super.onResume(); schedulePreview() }
    override fun onPause() { stopPreview(); super.onPause() }
    override fun onStop() { onEntryLocked(); super.onStop() }
    override fun onDestroyView() {
        onEntryLocked(); list = null; statusLabel = null; scopeLabel = null
        saveButton = null; testButton = null; settingsScroll = null
        previewView = null; previewStatus = null; compareOriginal = null; originalPreview = false
        super.onDestroyView()
    }
    companion object { fun create(quick: Boolean) = RecordingSettingsFragment().apply { arguments = Bundle().apply { putBoolean("quick", quick) } } }
}
