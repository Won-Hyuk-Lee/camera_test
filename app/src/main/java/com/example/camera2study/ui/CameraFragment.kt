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
import com.example.camera2study.util.CameraSettingsStore
import com.google.android.material.button.MaterialButton
import java.io.File

class CameraFragment : Fragment() {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private var service: CameraForegroundService? = null
    private lateinit var controller: CameraController
    private var isBound = false
    private var manualColorTempK: Int = CameraSettingsStore.DEFAULT_COLOR_TEMP_K


    private var recordStartMs: Long = 0L
    private var pauseStartMs: Long = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            val binding = _binding ?: return
            if (!::controller.isInitialized) return
            val elapsed = controller.recordingState.durationMs
            binding.txtTimer.text = formatElapsed(elapsed) + " · " + controller.recordingState.message
            binding.txtTimer.postDelayed(this, 200L)
        }
    }

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
            if (_binding == null) return
            val localBinder = binder as CameraForegroundService.LocalBinder
            service = localBinder.service()
            controller = service!!.controller
            isBound = true

            // 컨트롤러 이벤트 바인딩
            controller.onControlsApplied = { if (_binding != null && !controller.isRecording()) saveCameraSettings() }
            controller.onCameraChanged = { id -> updateLensIndicator(id); binding.previewView.post { fitPreview() } }
            controller.onRecordingEvent = { event -> handleRecordEvent(event) }
            com.example.camera2study.RecordingRuntime.state.removeObservers(viewLifecycleOwner)
            com.example.camera2study.RecordingRuntime.state.observe(viewLifecycleOwner) { state ->
                if (_binding != null) {
                    binding.btnSettings.isEnabled = !state.active
                    binding.btnSwitchFacing.isEnabled = !state.active
                    binding.txtTimer.text = formatElapsed(state.durationMs) + " · " + state.message
                }
            }
            controller.onZoomChanged = { current, max -> updateZoomUi(current, max) }
            controller.onError = { message ->
                context?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() }
            }

            // 프리뷰 뷰 부착
            if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                controller.attachPreview(binding.previewView)
            }

            // UI 및 리스너 설정
            setupZoomListeners()
            binding.previewView.scaleType = androidx.camera.view.PreviewView.ScaleType.FIT_CENTER
            binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreview() }
            binding.previewView.post { fitPreview() }
            setupQuickControls()
            updateThumbnail()

            // 마지막 카메라 세팅 복원
            restoreCameraSettings()

            // 자동 촬영 시작 (빠른 녹화 유입 시)
            val autoStart = arguments?.getBoolean("EXTRA_AUTO_START", false) ?: false
            if (autoStart) {
                setVideoModeUi()
                arguments?.putBoolean("EXTRA_AUTO_START", false)
                service?.startFromUi()
            } else {
                // 서비스의 동영상 상태 복원
                if (controller.isRecording()) {
                    setVideoModeUi()
                    syncRecordingUi(true)
                } else {
                    setVideoModeUi()
                }
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

        // 비녹화 상태에서는 service를 명시적으로 시작하지 않는다.
        // bindService(BIND_AUTO_CREATE)로 service를 만들기만 하고, foreground 격상은 녹화 시작 시점에 한다.
        // 이렇게 하면 카메라 화면을 닫을 때 (unbind) service가 자동으로 정리된다.
        val ctx = requireContext()
        val intent = android.content.Intent(ctx, CameraForegroundService::class.java)
        isBound = ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

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

        binding.btnPauseResume.setOnClickListener {
            if (::controller.isInitialized) {
                if (controller.isPaused()) {
                    controller.resumeRecording()
                } else {
                    controller.pauseRecording()
                }
            }
        }

    }

    private fun handleShutterClick() {
        if (!::controller.isInitialized) return
        if (controller.isRecording()) {
            controller.stopRecording()
        } else {
            service?.startFromUi()
        }
    }

    private fun setVideoModeUi() {
        binding.txtModeVideo.setTextColor(Color.parseColor("#FFC107"))
        binding.shutterCenter.setBackgroundResource(R.drawable.shutter_center_video)
        binding.txtTimer.visibility = View.VISIBLE
    }

    private fun updateLensIndicator(cameraId: String?) {
        val binding = _binding ?: return
        if (cameraId == null || !::controller.isInitialized) {
            binding.txtLensInfo.text = ""
            return
        }
        val lensFacingLabel = if (controller.isFacingBack()) "후면" else "전면"
        val zoomModeLabel = "%.2f× · 카메라 %s".format(controller.displayZoomRatio(), cameraId)
        binding.txtLensInfo.text = "$lensFacingLabel $zoomModeLabel"
    }

    private data class ZoomPreset(val button: MaterialButton, val ratio: Float)

    private val zoomPresets: List<ZoomPreset>
        get() = listOf(
            ZoomPreset(binding.btnZoom06, 0.6f),
            ZoomPreset(binding.btnZoom10, 1.0f),
            ZoomPreset(binding.btnZoom20, 2f),
            ZoomPreset(binding.btnZoom30, 3f),
            ZoomPreset(binding.btnZoom50, 5f),
            ZoomPreset(binding.btnZoom100, 10f)
        )

    private fun setupZoomListeners() {
        if (!::controller.isInitialized) return

        val max = controller.getMaxZoomRatio()

        zoomPresets.forEach { preset ->
            preset.button.setOnClickListener { controller.selectZoomPreset(preset.ratio) }
        }
        binding.sliderZoom.clearOnChangeListeners()
        binding.sliderZoom.addOnChangeListener { _, value, fromUser ->
            if (fromUser) controller.setDisplayZoomRatio(value)
        }
        updateZoomUi(controller.displayZoomRatio(), max)
    }

    private fun updateZoomUi(current: Float, max: Float) {
        val binding = _binding ?: return
        if (!isAdded) return
        val min = controller.getMinZoomRatio()
        val upper = max.coerceAtLeast(min + 0.01f)
        binding.sliderZoom.valueFrom = min
        binding.sliderZoom.valueTo = upper
        binding.sliderZoom.value = current.coerceIn(min, upper)
        binding.sliderZoom.visibility = if (max > min) View.VISIBLE else View.GONE
        zoomPresets.forEach { preset ->
            preset.button.visibility = View.VISIBLE
            preset.button.isEnabled = controller.supportsZoomPreset(preset.ratio)
            preset.button.alpha = if (preset.button.isEnabled) 1f else 0.35f
            preset.button.setTextColor(if (kotlin.math.abs(preset.ratio - current) < 0.08f) Color.parseColor("#FFC107") else Color.WHITE)
        }
        updateLensIndicator(controller.activeCameraId())
    }

    private val exposureFadeRunnable = Runnable {
        _binding?.sliderExposure?.visibility = View.GONE
    }
    private val handler = Handler(Looper.getMainLooper())

    private fun setupExposureSlider() {
        if (!::controller.isInitialized) return
        val min = controller.getMinExposureIndex()
        val max = controller.getMaxExposureIndex()
        val current = controller.getCurrentExposureIndex()

        if (min == 0 && max == 0) return

        binding.sliderExposure.max = max - min
        binding.sliderExposure.progress = current - min

        binding.sliderExposure.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && ::controller.isInitialized) {
                    val targetIndex = progress + min
                    controller.setExposureIndex(targetIndex)
                    
                    handler.removeCallbacks(exposureFadeRunnable)
                    handler.postDelayed(exposureFadeRunnable, 3000L)
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                handler.removeCallbacks(exposureFadeRunnable)
            }

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                handler.postDelayed(exposureFadeRunnable, 3000L)
            }
        })
    }

    private fun fitPreview() {
        val binding = _binding ?: return
        if (!::controller.isInitialized) return
        val parent = binding.previewView.parent as? View ?: return
        if (parent.width <= 0 || parent.height <= 0) return
        val ratio = controller.previewFrameRatio()
        val width = minOf(parent.width, (parent.height * ratio).toInt())
        val height = (width / ratio).toInt()
        val params = binding.previewView.layoutParams
        if (params.width != width || params.height != height) {
            params.width = width; params.height = height
            binding.previewView.layoutParams = params
        }
    }

    private fun setupQuickControls() {
        binding.btnBrightness.setOnClickListener {
            setupExposureSlider()
            binding.sliderExposure.visibility = if (binding.sliderExposure.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            handler.removeCallbacks(exposureFadeRunnable)
        }
        // 1. 격자 토글
        binding.btnGridToggle.setOnClickListener {
            val visible = binding.gridOverlayView.visibility == View.VISIBLE
            binding.gridOverlayView.visibility = if (visible) View.GONE else View.VISIBLE
            binding.btnGridToggle.text = if (visible) "📐 격자 OFF" else "📐 격자 ON"
            binding.btnGridToggle.setTextColor(if (visible) Color.WHITE else Color.parseColor("#FFC107"))
        }

    }


    private fun updateThumbnail() {
        val ctx = context ?: return
        val privateDir = File(ctx.getExternalFilesDir(null), "private_vault")
        if (!privateDir.exists()) return

        val files = privateDir.listFiles()?.filter { it.isFile && it.extension == "mp4" && it.length() > 0 && it.absolutePath !in com.example.camera2study.util.RecordingJournal.activePaths }
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
        if (!::controller.isInitialized || controller.isRecording()) return
        saveCameraSettings()
        navigateTo(RecordingSettingsFragment.create(false))
    }

    private fun syncRecordingUi(isRecording: Boolean) {
        val binding = _binding ?: return
        if (isRecording) {
            binding.txtTimer.removeCallbacks(timerRunnable)
            binding.txtTimer.post(timerRunnable)
            binding.btnPauseResume.visibility = View.VISIBLE
            binding.btnPauseResume.text = if (controller.isPaused()) "▶ 재개" else "⏸ 일시정지"

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
            binding.txtTimer.text = formatElapsed(controller.recordingState.durationMs) + " · " + controller.recordingState.message
            binding.btnPauseResume.visibility = View.GONE
            binding.shutterCenter.clearAnimation()

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
        val binding = _binding ?: return
        when (event) {
            is VideoRecordEvent.Start -> {
                syncRecordingUi(true)
            }
            is VideoRecordEvent.Pause -> {
                pauseStartMs = SystemClock.elapsedRealtime()
                binding.txtTimer.removeCallbacks(timerRunnable)
                binding.btnPauseResume.text = "▶ 재개"
            }
            is VideoRecordEvent.Resume -> {
                // pause 구간만큼 recordStartMs를 앞으로 당겨 타이머에 포함시키지 않는다.
                val pausedDuration = SystemClock.elapsedRealtime() - pauseStartMs
                recordStartMs += pausedDuration
                binding.txtTimer.post(timerRunnable)
                binding.btnPauseResume.text = if (controller.isPaused()) "▶ 재개" else "⏸ 일시정지"
            }
            is VideoRecordEvent.Finalize -> {
                if (!controller.isRecording()) {
                    service?.restoreNormalProfile()
                    syncRecordingUi(false)
                } else {
                    recordStartMs = SystemClock.elapsedRealtime()
                }

                // 완료 Toast는 띄우지 않는다. 오류 발생 시에만 사용자에게 노출한다.
                if (event.hasError()) {
                    Toast.makeText(
                        requireContext(),
                        "녹화 오류: ${event.error}",
                        Toast.LENGTH_LONG
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

    private fun saveCameraSettings() {
        if (!::controller.isInitialized || _binding == null || controller.isRecording() || !controller.areControlsReady()) return
        val settings = CameraSettingsStore.snapshot(
            requireContext(),
            controller,
            manualColorTempK
        )
        CameraSettingsStore.save(requireContext(), settings)
    }

    private fun restoreCameraSettings() {
        if (!::controller.isInitialized || controller.isRecording()) return
        val settings = CameraSettingsStore.load(requireContext())
        manualColorTempK = settings.colorTempK
        CameraSettingsStore.applyToController(controller, settings)
        binding.previewView.post { fitPreview() }
    }

    override fun onStart() {
        super.onStart()
        if (::controller.isInitialized && _binding != null) controller.attachPreview(binding.previewView)
    }

    override fun onStop() {
        if (::controller.isInitialized) controller.detachPreview()
        super.onStop()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(exposureFadeRunnable)
        binding.txtTimer.removeCallbacks(timerRunnable)

        val ctx = context
        val notRecording = !::controller.isInitialized || !controller.isRecording()

        if (::controller.isInitialized) {
            controller.onRecordingEvent = null
            controller.onCameraChanged = null
            controller.onZoomChanged = null
            controller.onError = null
            controller.onControlsApplied = null
            controller.detachPreview()
        }

        if (isBound) {
            requireContext().unbindService(serviceConnection)
            isBound = false
        }

        // 녹화 중이 아니면 foreground service가 절대 남지 않게 명시적으로 종료한다.
        if (notRecording && ctx != null) {
            CameraForegroundService.stop(ctx)
        }

        _binding = null
        super.onDestroyView()
    }
}

