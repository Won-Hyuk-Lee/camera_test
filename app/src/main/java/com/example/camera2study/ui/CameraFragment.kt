package com.example.camera2study.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.camera2study.CameraController
import com.example.camera2study.CameraForegroundService
import com.example.camera2study.R
import com.example.camera2study.databinding.FragmentCameraBinding
import com.example.camera2study.util.CameraUtils
import com.google.android.material.chip.Chip

class CameraFragment : Fragment() {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private lateinit var controller: CameraController

    private var recordStartMs: Long = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.elapsedRealtime() - recordStartMs
            binding.txtTimer.text = formatElapsed(elapsed)
            binding.txtTimer.postDelayed(this, 200L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controller = CameraController(requireContext())
        controller.onCameraChanged = { id -> updateLensIndicator(id) }
        controller.onRecordingEvent = { event -> handleRecordEvent(event) }

        controller.init(viewLifecycleOwner, binding.previewView) {
            populateLensChips()
        }

        binding.btnSwitchFacing.setOnClickListener { controller.switchFacing() }
        binding.btnSettings.setOnClickListener { openSettings() }
        binding.btnRecord.setOnClickListener { toggleRecording() }
        binding.btnBackground.setOnClickListener { toggleBackgroundRecording() }
    }

    private fun populateLensChips() {
        binding.lensChipGroup.removeAllViews()
        val lenses = controller.backLenses
        if (lenses.size <= 1) {
            binding.lensChipGroup.visibility = View.GONE
            return
        }
        binding.lensChipGroup.visibility = View.VISIBLE
        lenses.forEach { lens ->
            val chip = Chip(requireContext()).apply {
                text = if (lens.isWide) "광각 ${"%.1f".format(lens.focalLength)}mm"
                else "${"%.1f".format(lens.focalLength)}mm"
                isCheckable = true
                isChecked = lens.cameraId == controller.activeCameraId()
                setOnClickListener { controller.selectBackLens(lens.cameraId) }
            }
            binding.lensChipGroup.addView(chip)
        }
    }

    private fun updateLensIndicator(cameraId: String?) {
        if (cameraId == null) {
            binding.txtLensInfo.text = ""
            return
        }
        val focal = controller.backLenses.firstOrNull { it.cameraId == cameraId }?.focalLength
        val focalText = focal?.let { "%.1fmm".format(it) } ?: "-"
        val facing = if (controller.isFacingBack()) "후면" else "전면"
        binding.txtLensInfo.text = "$facing  $focalText  ID:$cameraId"

        for (i in 0 until binding.lensChipGroup.childCount) {
            val chip = binding.lensChipGroup.getChildAt(i) as? Chip ?: continue
            val lens = controller.backLenses.getOrNull(i) ?: continue
            chip.isChecked = lens.cameraId == cameraId
        }
    }

    private fun openSettings() {
        val activeId = controller.activeCameraId() ?: return
        val ctx = requireContext()
        val exposureRange = CameraUtils.getExposureTimeRangeNs(ctx, activeId)
        val apertures = CameraUtils.getApertures(ctx, activeId)
        val variableAperture = CameraUtils.hasVariableAperture(ctx, activeId)

        val sheet = SettingsBottomSheet.create(
            exposureRangeNs = exposureRange,
            apertures = apertures,
            variableAperture = variableAperture,
            callbacks = object : SettingsBottomSheet.Callbacks {
                override fun onAwbMode(mode: Int) = controller.setAwbMode(mode)
                override fun onWbGains(gains: FloatArray?) = controller.setManualWbGains(gains)
                override fun onExposureTime(ns: Long?) = controller.setExposureTime(ns)
                override fun onAperture(v: Float?) = controller.setAperture(v)
            }
        )
        sheet.show(parentFragmentManager, "settings")
    }

    private fun toggleRecording() {
        if (controller.isRecording()) {
            controller.stopRecording()
        } else {
            controller.startRecording()
        }
    }

    private fun toggleBackgroundRecording() {
        val ctx = requireContext()
        if (!isServiceRunning) {
            CameraForegroundService.start(ctx)
            Toast.makeText(ctx, "백그라운드 녹화 시작", Toast.LENGTH_SHORT).show()
            isServiceRunning = true
            binding.btnBackground.text = "BG 종료"
        } else {
            CameraForegroundService.stop(ctx)
            isServiceRunning = false
            binding.btnBackground.text = "BG 시작"
        }
    }

    private fun handleRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                recordStartMs = SystemClock.elapsedRealtime()
                binding.txtTimer.post(timerRunnable)
                binding.btnRecord.text = "■"
                binding.btnRecord.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.recording_red)
                )
            }
            is VideoRecordEvent.Finalize -> {
                binding.txtTimer.removeCallbacks(timerRunnable)
                binding.txtTimer.text = "00:00"
                binding.btnRecord.text = "●"
                binding.btnRecord.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.idle_gray)
                )
                if (event.hasError()) {
                    Toast.makeText(
                        requireContext(),
                        "녹화 오류: ${event.error}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "저장 완료",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            else -> Unit
        }
    }

    private fun formatElapsed(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%02d:%02d".format(m, s)
    }

    override fun onDestroyView() {
        binding.txtTimer.removeCallbacks(timerRunnable)
        controller.release()
        _binding = null
        super.onDestroyView()
    }

    private var isServiceRunning = false
}
