package com.example.camera2study.ui

import android.hardware.camera2.CameraMetadata
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.example.camera2study.databinding.BottomSheetSettingsBinding
import com.example.camera2study.util.CameraSettingsStore
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.camera.video.Quality

class SettingsBottomSheet : BottomSheetDialogFragment() {

    interface Callbacks {
        fun onAwbMode(mode: Int)
        fun onColorTemperature(kelvin: Int)
        fun onWbGains(gains: FloatArray?)
        fun onExposureTime(ns: Long?)
        fun onAperture(v: Float?)
        fun onQuality(quality: Quality)
        fun onMaxDuration(ms: Long)
        fun onMaxRepeatCount(count: Int)
        fun onFps(fps: Int)
        fun onHdr(enabled: Boolean)
        fun onProMode(enabled: Boolean)
    }

    private var _binding: BottomSheetSettingsBinding? = null
    private val binding get() = _binding!!

    private var callbacks: Callbacks? = null
    private var exposureRangeNs: LongRange? = null
    private var apertures: FloatArray? = null
    private var variableAperture: Boolean = false

    // 초기 상태 값 복원용
    private var initialQuality: Quality = Quality.HD
    private var initialMaxDurationMs: Long = 0L
    private var initialMaxRepeatCount: Int = 1
    private var initialFps: Int = 30
    private var initialHdr: Boolean = false
    private var initialProMode: Boolean = false
    private var initialAwbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO
    private var initialColorTempK: Int = CameraSettingsStore.DEFAULT_COLOR_TEMP_K
    private var initialExposureTimeNs: Long? = null
    private var initialAperture: Float? = null

    private val awbOptions = listOf(
        "AUTO" to CameraMetadata.CONTROL_AWB_MODE_AUTO,
        "DAYLIGHT" to CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
        "CLOUDY_DAYLIGHT" to CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        "FLUORESCENT" to CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
        "INCANDESCENT" to CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
        "OFF (수동)" to CameraMetadata.CONTROL_AWB_MODE_OFF
    )

    private val qualityOptions = listOf(
        "UHD (4K)" to Quality.UHD,
        "FHD (1080P)" to Quality.FHD,
        "HD (720P)" to Quality.HD,
        "SD (480P)" to Quality.SD
    )

    private val durationOptions = listOf(
        "제한 없음 (30분 단위)" to 0L,
        "10초 (테스트)" to 10_000L,
        "30분" to 1_800_000L
    )

    private val repeatOptions = listOf(
        "1회 (반복 없음)" to 1,
        "2회" to 2,
        "4회" to 4,
        "8회" to 8,
        "무한 반복" to 999
    )

    private val fpsOptions = listOf(
        "30 FPS" to 30,
        "60 FPS" to 60
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupProModeSwitch()
        setupColorTempSlider()
        setupAwbSpinner()
        setupExposureSlider()
        setupApertureSlider()

        // 신규 스피너 및 스위치 설정
        setupVideoSettings()
    }

    private fun setupProModeSwitch() {
        binding.switchProMode.isChecked = initialProMode
        binding.proModeContainer.visibility = if (initialProMode) View.VISIBLE else View.GONE
        binding.switchProMode.setOnCheckedChangeListener { _, isChecked ->
            binding.proModeContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            callbacks?.onProMode(isChecked)
            if (!isChecked) {
                // 자동 모드로 복귀할 때 누적된 수동 파라미터를 초기화한다.
                callbacks?.onAwbMode(CameraMetadata.CONTROL_AWB_MODE_AUTO)
                callbacks?.onColorTemperature(CameraSettingsStore.DEFAULT_COLOR_TEMP_K)
                callbacks?.onWbGains(null)
                callbacks?.onExposureTime(null)
                callbacks?.onAperture(null)
                binding.spinnerAwb.setSelection(0)
                binding.sliderColorTemp.value = CameraSettingsStore.DEFAULT_COLOR_TEMP_K.toFloat()
                binding.sliderExposure.value = 0f
                binding.txtExposureValue.text = "AUTO"
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 바텀시트가 열릴 때 하단 부가기능이 숨겨지지 않도록 완전히 STATE_EXPANDED 상태로 강제 확장합니다.
        val dialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog ?: return
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet)
        behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
    }

    private fun setupAwbSpinner() {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            awbOptions.map { it.first }
        )
        binding.spinnerAwb.adapter = adapter
        val selected = awbOptions.indexOfFirst { it.second == initialAwbMode }.coerceAtLeast(0)
        binding.spinnerAwb.setSelection(selected)
        updateAwbDependentUi(awbOptions[selected].second == CameraMetadata.CONTROL_AWB_MODE_OFF)
        binding.spinnerAwb.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = awbOptions[position].second
                callbacks?.onAwbMode(mode)
                val manual = mode == CameraMetadata.CONTROL_AWB_MODE_OFF
                updateAwbDependentUi(manual)
                if (!manual) callbacks?.onWbGains(null)
                else {
                    val kelvin = binding.sliderColorTemp.value.toInt()
                    callbacks?.onColorTemperature(kelvin)
                    callbacks?.onWbGains(CameraSettingsStore.kelvinToGains(kelvin))
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateAwbDependentUi(manual: Boolean) {
        binding.sliderColorTemp.isEnabled = manual
        binding.txtColorTempLabel.alpha = if (manual) 1f else 0.4f
    }

    private fun setupColorTempSlider() {
        binding.sliderColorTemp.valueFrom = 3000f
        binding.sliderColorTemp.valueTo = 8000f
        binding.sliderColorTemp.stepSize = 100f
        binding.sliderColorTemp.value = initialColorTempK.coerceIn(3000, 8000).toFloat()
        updateAwbDependentUi(initialAwbMode == CameraMetadata.CONTROL_AWB_MODE_OFF)

        binding.sliderColorTemp.addOnChangeListener { _, value, _ ->
            val kelvin = value.toInt()
            binding.txtColorTempValue.text = "${kelvin}K"
            if (binding.sliderColorTemp.isEnabled) {
                callbacks?.onColorTemperature(kelvin)
                callbacks?.onWbGains(CameraSettingsStore.kelvinToGains(kelvin))
            }
        }
        binding.txtColorTempValue.text = "${binding.sliderColorTemp.value.toInt()}K"
    }

    private fun setupExposureSlider() {
        val range = exposureRangeNs
        if (range == null) {
            binding.sliderExposure.isEnabled = false
            binding.txtExposureLabel.text = "셔터 (지원 안 함)"
            return
        }
        binding.sliderExposure.valueFrom = 0f
        binding.sliderExposure.valueTo = 100f
        val initialValue = initialExposureTimeNs?.let { ns ->
            val span = range.last - range.first
            if (span <= 0L) 0f else (((ns - range.first).toDouble() / span) * 100.0).toFloat()
        }?.coerceIn(0f, 100f) ?: 0f
        binding.sliderExposure.value = initialValue
        binding.txtExposureValue.text = initialExposureTimeNs?.let { formatShutter(it) } ?: "AUTO"

        binding.sliderExposure.addOnChangeListener { _, value, _ ->
            if (value <= 0.5f) {
                binding.txtExposureValue.text = "AUTO"
                callbacks?.onExposureTime(null)
            } else {
                val ratio = value / 100f
                val ns = (range.first + ((range.last - range.first).toDouble() * ratio).toLong())
                    .coerceIn(range.first, range.last)
                binding.txtExposureValue.text = formatShutter(ns)
                callbacks?.onExposureTime(ns)
            }
        }
    }

    private fun setupApertureSlider() {
        val list = apertures
        if (!variableAperture || list == null || list.size < 2) {
            binding.sliderAperture.isEnabled = false
            binding.txtApertureLabel.text = "조리개 (고정)"
            binding.txtApertureValue.text = list?.firstOrNull()?.let { "F${"%.1f".format(it)}" } ?: "-"
            return
        }
        binding.sliderAperture.valueFrom = 0f
        binding.sliderAperture.valueTo = (list.size - 1).toFloat()
        binding.sliderAperture.stepSize = 1f
        val selectedIndex = initialAperture?.let { current ->
            list.indices.minByOrNull { idx -> kotlin.math.abs(list[idx] - current) }
        } ?: 0
        binding.sliderAperture.value = selectedIndex.toFloat()
        binding.txtApertureValue.text = "F${"%.1f".format(list[selectedIndex])}"

        binding.sliderAperture.addOnChangeListener { _, value, _ ->
            val idx = value.toInt().coerceIn(0, list.size - 1)
            val f = list[idx]
            binding.txtApertureValue.text = "F${"%.1f".format(f)}"
            callbacks?.onAperture(f)
        }
    }

    private fun setupVideoSettings() {
        // 1. 영상 품질 스피너
        val qualityAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            qualityOptions.map { it.first }
        )
        binding.spinnerQuality.adapter = qualityAdapter
        val qIdx = qualityOptions.indexOfFirst { it.second == initialQuality }.coerceAtLeast(0)
        binding.spinnerQuality.setSelection(qIdx)
        binding.spinnerQuality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                callbacks?.onQuality(qualityOptions[position].second)
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        // 2. 최대 녹화 시간 스피너
        val durationAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            durationOptions.map { it.first }
        )
        binding.spinnerMaxDuration.adapter = durationAdapter
        val dIdx = durationOptions.indexOfFirst { it.second == initialMaxDurationMs }.coerceAtLeast(0)
        binding.spinnerMaxDuration.setSelection(dIdx)
        binding.spinnerMaxDuration.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                callbacks?.onMaxDuration(durationOptions[position].second)
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        // 3. 반복 녹화 횟수 스피너
        val repeatAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            repeatOptions.map { it.first }
        )
        binding.spinnerRepeatCount.adapter = repeatAdapter
        val rIdx = repeatOptions.indexOfFirst { it.second == initialMaxRepeatCount }.coerceAtLeast(0)
        binding.spinnerRepeatCount.setSelection(rIdx)
        binding.spinnerRepeatCount.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                callbacks?.onMaxRepeatCount(repeatOptions[position].second)
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        // 4. FPS 스피너
        val fpsAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            fpsOptions.map { it.first }
        )
        binding.spinnerFps.adapter = fpsAdapter
        val fIdx = fpsOptions.indexOfFirst { it.second == initialFps }.coerceAtLeast(0)
        binding.spinnerFps.setSelection(fIdx)
        binding.spinnerFps.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                callbacks?.onFps(fpsOptions[position].second)
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        // 5. HDR 스위치
        binding.switchHdr.isChecked = initialHdr
        binding.switchHdr.setOnCheckedChangeListener { _, isChecked ->
            callbacks?.onHdr(isChecked)
        }

    }

    private fun formatShutter(ns: Long): String {
        val seconds = ns / 1_000_000_000.0
        return if (seconds >= 1.0) "%.1fs".format(seconds)
        else "1/${(1.0 / seconds).toInt()}s"
    }

    private fun kelvinToGains(kelvin: Int): FloatArray {
        val k = kelvin.coerceIn(3000, 8000)
        return when {
            k <= 5500 -> {
                val t = (k - 3000) / 2500f
                val r = lerp(1.8f, 1.0f, t)
                val b = lerp(0.6f, 1.0f, t)
                floatArrayOf(r, 1.0f, 1.0f, b)
            }
            else -> {
                val t = (k - 5500) / 2500f
                val r = lerp(1.0f, 0.7f, t)
                val b = lerp(1.0f, 1.6f, t)
                floatArrayOf(r, 1.0f, 1.0f, b)
            }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun create(
            exposureRangeNs: LongRange?,
            apertures: FloatArray?,
            variableAperture: Boolean,
            currentQuality: Quality,
            currentMaxDurationMs: Long,
            currentMaxRepeatCount: Int,
            currentFps: Int,
            currentHdr: Boolean,
            currentProMode: Boolean,
            currentAwbMode: Int,
            currentColorTempK: Int,
            currentExposureTimeNs: Long?,
            currentAperture: Float?,
            callbacks: Callbacks
        ): SettingsBottomSheet = SettingsBottomSheet().also {
            it.exposureRangeNs = exposureRangeNs
            it.apertures = apertures
            it.variableAperture = variableAperture
            it.initialQuality = currentQuality
            it.initialMaxDurationMs = currentMaxDurationMs
            it.initialMaxRepeatCount = currentMaxRepeatCount
            it.initialFps = currentFps
            it.initialHdr = currentHdr
            it.initialProMode = currentProMode
            it.initialAwbMode = currentAwbMode
            it.initialColorTempK = currentColorTempK
            it.initialExposureTimeNs = currentExposureTimeNs
            it.initialAperture = currentAperture
            it.callbacks = callbacks
        }
    }
}
