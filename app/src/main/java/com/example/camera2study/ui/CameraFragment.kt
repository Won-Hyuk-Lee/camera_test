package com.example.camera2study.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ThumbnailUtils
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.ScaleAnimation
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
import java.io.File

class CameraFragment : Fragment() {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private var service: CameraForegroundService? = null
    private lateinit var controller: CameraController
    private var isBound = false

    private var isPhotoMode: Boolean = true // 기본 사진 모드

    private var recordStartMs: Long = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!::controller.isInitialized) return
            val elapsed = SystemClock.elapsedRealtime() - recordStartMs
            val repeatStr = if (controller.maxRepeatCount > 1) {
                " [${controller.currentRepeatCount + 1}/${controller.maxRepeatCount}]"
            } else ""
            binding.txtTimer.text = formatElapsed(elapsed) + repeatStr
            binding.txtTimer.postDelayed(this, 200L)
        }
    }

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
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

            // UI 및 리스너 설정
            populateLensChips()
            setupZoomListeners()
            setupTouchToFocus()
            setupRatioControls()
            setupQuickControls()
            updateThumbnail()

            // 초기 모드 동기화 (기본 사진모드로 시작하되 녹화 중이면 비디오 동기화)
            if (controller.isRecording()) {
                setVideoModeUi()
                syncRecordingUi(true)
            } else {
                setPhotoModeUi()
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
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

        // 백그라운드 서비스 바인딩
        val ctx = requireContext()
        CameraForegroundService.start(ctx)
        val intent = android.content.Intent(ctx, CameraForegroundService::class.java)
        ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        binding.btnSwitchFacing.setOnClickListener {
            if (::controller.isInitialized) {
                val scale = ScaleAnimation(1f, 0.8f, 1f, 0.8f, ScaleAnimation.RELATIVE_TO_SELF, 0.5f, ScaleAnimation.RELATIVE_TO_SELF, 0.5f).apply {
                    duration = 150
                    repeatCount = 1
                    repeatMode = ScaleAnimation.REVERSE
                }
                binding.btnSwitchFacing.startAnimation(scale)
                controller.switchFacing()
            }
        }

        binding.btnSettings.setOnClickListener { openSettings() }

        binding.btnPrivateVault.setOnClickListener {
            navigateTo(PrivateVaultFragment())
        }

        binding.btnRecord.setOnClickListener {
            handleShutterClick()
        }

        // 모드 탭 리스너
        binding.txtModePhoto.setOnClickListener {
            if (::controller.isInitialized && controller.isRecording()) return@setOnClickListener
            setPhotoModeUi()
        }

        binding.txtModeVideo.setOnClickListener {
            if (::controller.isInitialized && controller.isRecording()) return@setOnClickListener
            setVideoModeUi()
        }
    }

    private fun handleShutterClick() {
        if (!::controller.isInitialized) return
        if (isPhotoMode) {
            // 1. 사진 촬영
            animateShutterClick()
            controller.takePicture(
                onSuccess = { file ->
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "사진 촬영 완료 및 비공개 저장", Toast.LENGTH_SHORT).show()
                        updateThumbnail()
                    }
                },
                onError = { e ->
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "촬영 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } else {
            // 2. 동영상 녹화 토글
            if (controller.isRecording()) {
                controller.stopRecording()
            } else {
                controller.resetRepeatCount()
                controller.startRecording()
            }
        }
    }

    private fun animateShutterClick() {
        val animation = ScaleAnimation(
            1.0f, 0.85f, 1.0f, 0.85f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 100
            repeatCount = 1
            repeatMode = ScaleAnimation.REVERSE
        }
        binding.shutterCenter.startAnimation(animation)
    }

    private fun setPhotoModeUi() {
        isPhotoMode = true
        binding.txtModePhoto.setTextColor(Color.parseColor("#FFC107"))
        binding.txtModeVideo.setTextColor(Color.parseColor("#8AFFFFFF"))
        
        binding.shutterCenter.setBackgroundResource(R.drawable.shutter_center_photo)
        binding.btnMuteAudio.alpha = 0.3f
        binding.btnMuteAudio.isEnabled = false

        binding.txtTimer.visibility = View.INVISIBLE
    }

    private fun setVideoModeUi() {
        isPhotoMode = false
        binding.txtModePhoto.setTextColor(Color.parseColor("#8AFFFFFF"))
        binding.txtModeVideo.setTextColor(Color.parseColor("#FFC107"))
        
        binding.shutterCenter.setBackgroundResource(R.drawable.shutter_center_video)
        binding.btnMuteAudio.alpha = 1.0f
        binding.btnMuteAudio.isEnabled = true

        binding.txtTimer.visibility = View.VISIBLE
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
        binding.txtLensInfo.text = "$facing $focalText"

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
        
        binding.btnZoom05.setTextColor(if (current <= 0.6f) Color.parseColor("#FFC107") else Color.WHITE)
        binding.btnZoom10.setTextColor(if (current > 0.9f && current < 1.2f) Color.parseColor("#FFC107") else Color.WHITE)
        binding.btnZoom20.setTextColor(if (current >= 1.9f && current < 2.2f) Color.parseColor("#FFC107") else Color.WHITE)
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

    private fun setupRatioControls() {
        binding.btnRatio34.setOnClickListener {
            updateRatioSelection(CameraController.RATIO_3_4)
        }
        binding.btnRatio169.setOnClickListener {
            updateRatioSelection(CameraController.RATIO_16_9)
        }
        binding.btnRatioFull.setOnClickListener {
            updateRatioSelection(CameraController.RATIO_FULL)
        }
        // 초기 비율 버튼 색상 셋업
        updateRatioSelection(controller.currentRatioMode)
    }

    private fun updateRatioSelection(mode: Int) {
        if (!::controller.isInitialized) return
        controller.currentRatioMode = mode
        
        binding.btnRatio34.setTextColor(if (mode == CameraController.RATIO_3_4) Color.parseColor("#FFC107") else Color.WHITE)
        binding.btnRatio169.setTextColor(if (mode == CameraController.RATIO_16_9) Color.parseColor("#FFC107") else Color.WHITE)
        binding.btnRatioFull.setTextColor(if (mode == CameraController.RATIO_FULL) Color.parseColor("#FFC107") else Color.WHITE)

        // PreviewView 종횡비 시각적 조절
        val params = binding.previewView.layoutParams as ViewGroup.MarginLayoutParams
        when (mode) {
            CameraController.RATIO_3_4 -> {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                params.height = (binding.previewView.width * 4) / 3
            }
            CameraController.RATIO_16_9 -> {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                params.height = (binding.previewView.width * 16) / 9
            }
            CameraController.RATIO_FULL -> {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        binding.previewView.layoutParams = params
    }

    private fun setupQuickControls() {
        // 1. 격자 토글
        binding.btnGridToggle.setOnClickListener {
            val visible = binding.gridOverlayView.visibility == View.VISIBLE
            binding.gridOverlayView.visibility = if (visible) View.GONE else View.VISIBLE
            binding.btnGridToggle.text = if (visible) "📐 격자 OFF" else "📐 격자 ON"
            binding.btnGridToggle.setTextColor(if (visible) Color.WHITE else Color.parseColor("#FFC107"))
        }

        // 2. 마이크 토글
        binding.btnMuteAudio.setOnClickListener {
            controller.isAudioMuted = !controller.isAudioMuted
            val muted = controller.isAudioMuted
            binding.btnMuteAudio.text = if (muted) "🔇 소리 끔" else "🎤 소리 켬"
            binding.btnMuteAudio.setTextColor(if (muted) Color.parseColor("#FF5252") else Color.WHITE)
        }

        // 3. 무음 토글
        binding.btnMuteSound.setOnClickListener {
            controller.isMuteSound = !controller.isMuteSound
            val mute = controller.isMuteSound
            binding.btnMuteSound.text = if (mute) "🔇 무음 ON" else "🔊 무음 OFF"
            binding.btnMuteSound.setTextColor(if (mute) Color.parseColor("#FFC107") else Color.WHITE)
        }
    }

    private fun updateThumbnail() {
        val ctx = context ?: return
        val privateDir = File(ctx.getExternalFilesDir(null), "private_vault")
        if (!privateDir.exists()) return

        val files = privateDir.listFiles()?.filter { it.isFile && it.name != ".nomedia" }
        if (files.isNullOrEmpty()) {
            binding.imgVaultThumbnail.setImageResource(android.R.drawable.ic_lock_lock)
            binding.imgVaultThumbnail.setPadding(8, 8, 8, 8)
            return
        }

        val latest = files.maxByOrNull { it.lastModified() } ?: return

        try {
            binding.imgVaultThumbnail.setPadding(0, 0, 0, 0)
            if (latest.name.endsWith(".jpg")) {
                val bitmap = BitmapFactory.decodeFile(latest.absolutePath)
                binding.imgVaultThumbnail.setImageBitmap(bitmap)
            } else if (latest.name.endsWith(".mp4")) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val bitmap = ThumbnailUtils.createVideoThumbnail(latest, Size(96, 96), CancellationSignal())
                    binding.imgVaultThumbnail.setImageBitmap(bitmap)
                } else {
                    @Suppress("DEPRECATION")
                    val bitmap = ThumbnailUtils.createVideoThumbnail(latest.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
                    binding.imgVaultThumbnail.setImageBitmap(bitmap)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            binding.imgVaultThumbnail.setImageResource(android.R.drawable.ic_lock_lock)
            binding.imgVaultThumbnail.setPadding(8, 8, 8, 8)
        }
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

    private fun syncRecordingUi(isRecording: Boolean) {
        if (isRecording) {
            recordStartMs = SystemClock.elapsedRealtime()
            binding.txtTimer.post(timerRunnable)
            
            // 녹화 작동 셔터 변형 애니메이션 (둥근 빨간 채우기가 살짝 사각형처럼 축소)
            val animation = ScaleAnimation(
                1.0f, 0.6f, 1.0f, 0.6f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 200
                fillAfter = true
            }
            binding.shutterCenter.startAnimation(animation)
        } else {
            binding.txtTimer.removeCallbacks(timerRunnable)
            binding.txtTimer.text = "00:00"
            binding.shutterCenter.clearAnimation()
            
            // 원래 둥근 원 복원
            val animation = ScaleAnimation(
                0.6f, 1.0f, 0.6f, 1.0f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 200
                fillAfter = true
            }
            binding.shutterCenter.startAnimation(animation)
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
                        "비공개 보관함에 비디오가 저장되었습니다.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                updateThumbnail()
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

    private fun navigateTo(fragment: Fragment) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commit()
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
