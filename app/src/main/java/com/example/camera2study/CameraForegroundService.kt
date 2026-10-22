package com.example.camera2study

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.Observer
import com.example.camera2study.util.CameraSettingsStore
import com.example.camera2study.util.CameraSettingsStore.CameraSettings
import com.example.camera2study.util.RecordingJournal

class CameraForegroundService : LifecycleService() {
    private val binder = LocalBinder()
    lateinit var controller: CameraController
        private set
    private var ready = false
    private var initializing = false
    private var pending: CameraSettings? = null
    private var source = "app"
    private var foreground = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastUpdate = 0L
    private var lastPhase: RecordingPhase? = null
    private val timeout = Runnable { if (pending != null) fail("카메라 준비 시간이 초과됐습니다") }
    private val observer = Observer<RecordingState> { state ->
        val now = SystemClock.elapsedRealtime()
        if (state.phase != lastPhase || now - lastUpdate >= 3_000) {
            RecordingStopWidgetProvider.updateAll(this, state)
            if (foreground) getSystemService(NotificationManager::class.java).notify(NOTI_ID, notification(state))
            lastUpdate = now
            lastPhase = state.phase
        }
    }
    inner class LocalBinder : Binder() { fun service() = this@CameraForegroundService }

    override fun onCreate() {
        super.onCreate()
        RecordingJournal.recover(this)
        controller = CameraController(applicationContext)
        controller.onFinalizeEnded = {
            val state = RecordingRuntime.state.value ?: RecordingState()
            endForeground()
            if (state.phase == RecordingPhase.ERROR) runCatching {
                getSystemService(NotificationManager::class.java).notify(NOTI_ID + 1, notification(state))
            }
            stopSelf()
        }
        RecordingRuntime.state.observeForever(observer)
    }
    private fun initialize() {
        if (initializing) return
        initializing = true
        controller.init(this) { ready = true; startPending() }
    }
    override fun onBind(intent: Intent): IBinder { super.onBind(intent); initialize(); return binder }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_QUICK_START -> {
                val config = CameraSettingsStore.load(this, true)
                request(if (intent.getBooleanExtra("test", false)) config.copy(maxDurationMs = 10_000,
                    maxRepeatCount = 1, totalDurationMs = 0) else config, "widget")
            }
            ACTION_START_RECORDING -> request(CameraSettingsStore.load(this), "app")
            ACTION_STOP_RECORDING -> stopBackgroundRecording(intent.getStringExtra("source") ?: "notification")
        }
        return START_NOT_STICKY
    }
    fun startFromUi() {
        if (controller.isRecording() || pending != null) return
        request(CameraSettingsStore.snapshot(this, controller, CameraSettingsStore.load(this).colorTempK), "app")
    }
    private fun request(settings: CameraSettings, origin: String) {
        if (controller.isRecording() || pending != null) return
        source = origin
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            (settings.audioEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)) {
            fail("카메라 또는 마이크 권한이 필요합니다. 앱 설정에서 허용해 주세요")
            return
        }
        pending = settings
        RecordingRuntime.state.value = RecordingState(RecordingPhase.PREPARING, message = "녹화 준비 중")
        try {
            ensureChannel()
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                if (settings.audioEnabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTI_ID, notification(RecordingRuntime.state.value!!), type)
            else startForeground(NOTI_ID, notification(RecordingRuntime.state.value!!))
            foreground = true
            // Started ownership keeps recording alive after the Activity unbinds.
            startService(Intent(this, CameraForegroundService::class.java))
            handler.postDelayed(timeout, 12_000L)
            initialize()
            startPending()
        } catch (e: Exception) { fail("녹화를 시작할 수 없습니다: ${e.message}") }
    }
    private fun startPending() {
        val config = pending ?: return
        if (!ready) return
        pending = null
        handler.removeCallbacks(timeout)
        try {
            CameraSettingsStore.applyToController(controller, config)
            controller.startRecording(source)
        } catch (e: Exception) { fail("촬영 설정 적용 실패: ${e.message}") }
    }
    fun stopBackgroundRecording(origin: String = "app") {
        pending = null
        handler.removeCallbacks(timeout)
        if (controller.isRecording()) controller.stopRecording(origin)
        else {
            RecordingRuntime.state.value = RecordingState(message = "중지됨")
            endForeground()
            stopSelf()
        }
    }
    private fun endForeground() {
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
    }
    private fun fail(message: String) {
        pending = null
        handler.removeCallbacks(timeout)
        val journal = RecordingJournal.begin(this, source, "start_rejected")
        RecordingJournal.finish(this, journal, message, "start_rejected")
        RecordingRuntime.state.value = RecordingState(RecordingPhase.ERROR, message = message)
        ensureChannel()
        endForeground()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTI_ID + 1, notification(RecordingRuntime.state.value!!)) }
        stopSelf()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!controller.isRecording() && pending == null) stopSelf()
    }
    override fun onDestroy() {
        pending = null
        handler.removeCallbacksAndMessages(null)
        RecordingRuntime.state.removeObserver(observer)
        controller.onFinalizeEnded = null
        controller.release()
        super.onDestroy()
    }
    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "동영상 녹화 상태", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(state: RecordingState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, CameraForegroundService::class.java).apply {
            action = ACTION_STOP_RECORDING
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(state.message)
            .setContentText("%02d:%02d · 파일 %d · %.1f MB".format(state.durationMs / 60_000, state.durationMs / 1000 % 60,
                state.segment, state.bytes / 1_048_576.0))
            .setSmallIcon(android.R.drawable.ic_menu_camera).setContentIntent(open)
            .setOngoing(state.active).setOnlyAlertOnce(true).setShowWhen(false)
        if (state.active) builder.addAction(android.R.drawable.ic_media_pause, "녹화 중지", stop)
        return builder.build()
    }
    companion object {
        const val NOTI_ID = 1001
        const val CHANNEL_ID = "video_recording_status"
        const val ACTION_QUICK_START = "com.example.camera2study.ACTION_QUICK_START"
        const val ACTION_START_RECORDING = "com.example.camera2study.ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.camera2study.ACTION_STOP_RECORDING"
        fun requestQuickRecording(context: Context, test: Boolean = false) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, CameraForegroundService::class.java).apply {
                    action = ACTION_QUICK_START; putExtra("test", test)
                })
            } catch (e: Exception) {
                val state = RecordingState(RecordingPhase.ERROR, message = "빠른 녹화 요청 실패: ${e.message}")
                RecordingRuntime.state.value = state
                RecordingStopWidgetProvider.updateAll(context, state)
                android.widget.Toast.makeText(context, state.message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        fun requestStop(context: Context) {
            try {
                context.startService(Intent(context, CameraForegroundService::class.java).apply {
                    action = ACTION_STOP_RECORDING; putExtra("source", "widget")
                })
            } catch (_: Exception) {
                RecordingStopWidgetProvider.updateAll(context, RecordingState(RecordingPhase.ERROR, message = "중지 요청 실패 · 앱에서 확인"))
            }
        }
        fun stop(context: Context) { context.stopService(Intent(context, CameraForegroundService::class.java)) }
    }
}
