package com.example.camera2study.ui

import android.hardware.camera2.CameraMetadata
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.example.camera2study.databinding.BottomSheetSettingsBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SettingsBottomSheet : BottomSheetDialogFragment() {

    interface Callbacks {
        fun onAwbMode(mode: Int)
        fun onWbGains(gains: FloatArray?)
        fun onExposureTime(ns: Long?)
        fun onAperture(v: Float?)
    }

    private var _binding: BottomSheetSettingsBinding? = null
    private val binding get() = _binding!!

    private var callbacks: Callbacks? = null
    private var exposureRangeNs: LongRange? = null
    private var apertures: FloatArray? = null
    private var variableAperture: Boolean = false

    private val awbOptions = listOf(
        "AUTO" to CameraMetadata.CONTROL_AWB_MODE_AUTO,
        "DAYLIGHT" to CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
        "CLOUDY_DAYLIGHT" to CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        "FLUORESCENT" to CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
        "INCANDESCENT" to CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
        "OFF (수동)" to CameraMetadata.CONTROL_AWB_MODE_OFF
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
        setupAwbSpinner()
        setupColorTempSlider()
        setupExposureSlider()
        setupApertureSlider()
    }

    private fun setupAwbSpinner() {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            awbOptions.map { it.first }
        )
        binding.spinnerAwb.adapter = adapter
        binding.spinnerAwb.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = awbOptions[position].second
                callbacks?.onAwbMode(mode)
                val manual = mode == CameraMetadata.CONTROL_AWB_MODE_OFF
                binding.sliderColorTemp.isEnabled = manual
                binding.txtColorTempLabel.alpha = if (manual) 1f else 0.4f
                if (!manual) callbacks?.onWbGains(null)
                else callbacks?.onWbGains(kelvinToGains(binding.sliderColorTemp.value.toInt()))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupColorTempSlider() {
        binding.sliderColorTemp.valueFrom = 3000f
        binding.sliderColorTemp.valueTo = 8000f
        binding.sliderColorTemp.stepSize = 100f
        binding.sliderColorTemp.value = 5500f
        binding.sliderColorTemp.isEnabled = false
        binding.txtColorTempLabel.alpha = 0.4f

        binding.sliderColorTemp.addOnChangeListener { _, value, _ ->
            binding.txtColorTempValue.text = "${value.toInt()}K"
            if (binding.sliderColorTemp.isEnabled) {
                callbacks?.onWbGains(kelvinToGains(value.toInt()))
            }
        }
        binding.txtColorTempValue.text = "5500K"
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
        binding.sliderExposure.value = 0f
        binding.txtExposureValue.text = "AUTO"

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
        binding.sliderAperture.value = 0f
        binding.txtApertureValue.text = "F${"%.1f".format(list[0])}"

        binding.sliderAperture.addOnChangeListener { _, value, _ ->
            val idx = value.toInt().coerceIn(0, list.size - 1)
            val f = list[idx]
            binding.txtApertureValue.text = "F${"%.1f".format(f)}"
            callbacks?.onAperture(f)
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
            callbacks: Callbacks
        ): SettingsBottomSheet = SettingsBottomSheet().also {
            it.exposureRangeNs = exposureRangeNs
            it.apertures = apertures
            it.variableAperture = variableAperture
            it.callbacks = callbacks
        }
    }
}
