package com.example.camera2study.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.video.Quality
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.camera2study.CameraController
import com.example.camera2study.CameraForegroundService
import com.example.camera2study.R
import com.example.camera2study.databinding.FragmentCameraBinding
import com.example.camera2study.util.CameraUtils
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class CameraFragment : Fragment() {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    // 서비스 바인딩을 통해 획득할 컨트롤러
    private lateinit var controller: CameraController
    private var service: CameraForegroundService? = null
    private var isBound = false

    private var recordStartMs: Long = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!::controller.isInitialized) return
            val elapsed = SystemClock.elapsedRealtime() - recordStartMs
            
            // 반복 녹화 상황 시 타이머 표시 갱신 지원을 위해 현재 라운드 표시 추가
            val repeatStr = if (controller.maxRepeatCount > 1) {
                " [${controller.currentRepeatCount + 1}/${controller.maxRepeatCount}]"
            } else ""
            
            binding.txtTimer.text = formatElapsed(elapsed) + repeatStr
            binding.txtTimer.postDelayed(this, 200L)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as CameraForegroundService.LocalBinder
            service = localBinder.service()
            controller = service!!.controller
            isBound = true

            // 컨트롤러 이벤트 바인딩
            controller.onCameraChanged = { id -> updateLensIndicator(id) }
            controller.onRecordingEvent = { event -> handleRecordEvent(event) }
            controller.onZoomChanged = { current, max -> updateZoomUi(current, max) }

            // 프리뷰 뷰 부착
            controller.attachPreview(binding.previewView)

            // UI 셋업
            populateLensChips()
            setupZoomListeners()
            setupTouchToFocus()
            checkBatteryOptimizationStatus()

            // 이미 서비스에서 녹화 중인 상태라면 UI 동기화
            if (controller.isRecording()) {
                syncRecordingUi(true)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
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

        // 백그라운드 서비스를 먼저 기동하고 바인딩 수행
        val ctx = requireContext()
        CameraForegroundService.start(ctx)
        
        val intent = Intent(ctx, CameraForegroundService::class.java)
        ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        binding.btnSwitchFacing.setOnClickListener { 
            if (::controller.isInitialized) controller.switchFacing() 
        }
        binding.btnSettings.setOnClickListener { openSettings() }
        binding.btnRecord.setOnClickListener { toggleRecording() }
        
        binding.btnBackground.text = "BG 팁"
        binding.btnBackground.setOnClickListener {
            Toast.makeText(
                requireContext(),
                "녹화를 시작한 후 홈 버튼을 눌러도 백그라운드에서 녹화가 중단 없이 이어집니다.",
                Toast.LENGTH_LONG
            ).show()
        }

        binding.btnIgnoreBattery.setOnClickListener { requestIgnoreBatteryOptimization() }
    }

    private fun populateLensChips() {
        if (!::controller.isInitialized) return
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
        if (cameraId == null || !::controller.isInitialized) {
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

    private fun setupZoomListeners() {
        if (!::controller.isInitialized) return
        
        binding.sliderZoom.valueFrom = controller.getMinZoomRatio()
        binding.sliderZoom.valueTo = controller.getMaxZoomRatio()
        binding.sliderZoom.value = controller.zoomRatio.coerceIn(controller.getMinZoomRatio(), controller.getMaxZoomRatio())

        binding.sliderZoom.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                controller.setZoomRatio(value)
            }
        }

        binding.btnZoom05.setOnClickListener { controller.setZoomRatio(0.5f) }
        binding.btnZoom10.setOnClickListener { controller.setZoomRatio(1.0f) }
        binding.btnZoom20.setOnClickListener { controller.setZoomRatio(2.0f) }
    }

    private fun updateZoomUi(current: Float, max: Float) {
        if (!isAdded) return
        binding.sliderZoom.valueTo = max
        binding.sliderZoom.value = current.coerceIn(binding.sliderZoom.valueFrom, max)
        
        binding.btnZoom05.alpha = if (current <= 0.6f) 1.0f else 0.6f
        binding.btnZoom10.alpha = if (current > 0.9f && current < 1.2f) 1.0f else 0.6f
        binding.btnZoom20.alpha = if (current >= 1.9f && current < 2.2f) 1.0f else 0.6f
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchToFocus() {
        binding.previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                if (::controller.isInitialized) {
                    controller.tapToFocus(event.x, event.y)
                    Toast.makeText(requireContext(), "초점과 노출을 재정렬합니다", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
    }

    private fun checkBatteryOptimizationStatus() {
        val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(requireContext().packageName)
        if (ignoring) {
            binding.btnIgnoreBattery.visibility = View.GONE
        } else {
            binding.btnIgnoreBattery.visibility = View.VISIBLE
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("배터리 최적화 해제 필요")
            .setMessage("백그라운드에서 장시간 안정적으로 녹화를 계속 수행하려면 배터리 최적화 예외 설정이 필수적입니다. 설정 화면으로 이동하시겠습니까?")
            .setPositiveButton("이동") { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                startActivity(intent)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun openSettings() {
        if (!::controller.isInitialized) return
        val activeId = controller.activeCameraId() ?: return
        val ctx = requireContext()
        val exposureRange = CameraUtils.getExposureTimeRangeNs(ctx, activeId)
        val apertures = CameraUtils.getApertures(ctx, activeId)
        val variableAperture = CameraUtils.hasVariableAperture(ctx, activeId)

        val sheet = SettingsBottomSheet.create(
            exposureRangeNs = exposureRange,
            apertures = apertures,
            variableAperture = variableAperture,
            currentQuality = controller.videoQuality,
            currentMaxDurationMs = controller.maxDurationMs,
            currentMaxRepeatCount = controller.maxRepeatCount,
            currentFileSizeOpt = controller.isFileSizeOptimizationEnabled,
            callbacks = object : SettingsBottomSheet.Callbacks {
                override fun onAwbMode(mode: Int) = controller.setAwbMode(mode)
                override fun onWbGains(gains: FloatArray?) = controller.setManualWbGains(gains)
                override fun onExposureTime(ns: Long?) = controller.setExposureTime(ns)
                override fun onAperture(v: Float?) = controller.setAperture(v)
                
                override fun onQuality(quality: Quality) {
                    controller.videoQuality = quality
                }

                override fun onMaxDuration(ms: Long) {
                    controller.maxDurationMs = ms
                }

                override fun onMaxRepeatCount(count: Int) {
                    controller.maxRepeatCount = count
                }

                override fun onFileSizeOptimization(enabled: Boolean) {
                    controller.isFileSizeOptimizationEnabled = enabled
                }
            }
        )
        sheet.show(parentFragmentManager, "settings")
    }

    private fun toggleRecording() {
        if (!::controller.isInitialized) return
        if (controller.isRecording()) {
            controller.stopRecording()
        } else {
            controller.resetRepeatCount()
            controller.startRecording()
        }
    }

    private fun syncRecordingUi(isRecording: Boolean) {
        if (isRecording) {
            recordStartMs = SystemClock.elapsedRealtime()
            binding.txtTimer.post(timerRunnable)
            binding.btnRecord.text = "■"
            binding.btnRecord.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.recording_red)
            )
        } else {
            binding.txtTimer.removeCallbacks(timerRunnable)
            binding.txtTimer.text = "00:00"
            binding.btnRecord.text = "●"
            binding.btnRecord.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.idle_gray)
            )
        }
    }

    private fun handleRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                syncRecordingUi(true)
            }
            is VideoRecordEvent.Finalize -> {
                if (!controller.isRecording()) {
                    syncRecordingUi(false)
                } else {
                    recordStartMs = SystemClock.elapsedRealtime()
                }

                if (event.hasError()) {
                    Toast.makeText(
                        requireContext(),
                        "녹화 완료 (오류: ${event.error})",
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

    override fun onResume() {
        super.onResume()
        checkBatteryOptimizationStatus()
    }

    override fun onDestroyView() {
        binding.txtTimer.removeCallbacks(timerRunnable)
        
        if (::controller.isInitialized) {
            controller.detachPreview()
        }
        
        if (isBound) {
            requireContext().unbindService(serviceConnection)
            isBound = false
        }
        _binding = null
        super.onDestroyView()
    }
}
