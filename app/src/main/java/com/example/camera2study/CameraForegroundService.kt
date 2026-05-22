package com.example.camera2study

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService

/**
 * 카메라 백그라운드 녹화용 foreground service.
 *
 * 구조:
 *  - onCreate: controller만 만든다. startForeground는 하지 않는다.
 *  - 녹화가 시작될 때만 startForeground를 호출한다.
 *  - 녹화가 끝나면 stopForeground + stopSelf로 service를 정리한다.
 *  - 결과적으로 카메라 화면을 비녹화 상태로 닫으면 service가 남지 않는다.
 */
class CameraForegroundService : LifecycleService() {

    private val binder = LocalBinder()
    lateinit var controller: CameraController
        private set

    private var audioManager: AudioManager? = null
    private var firstVolumeEventTime = 0L
    private var lastVolumeEventTime = 0L
    private var isLongPressTriggered = false

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "android.media.VOLUME_CHANGED_ACTION") return
            if (!controller.isRecording()) {
                firstVolumeEventTime = 0L
                lastVolumeEventTime = 0L
                isLongPressTriggered = false
                return
            }

            val now = System.currentTimeMillis()
            if (firstVolumeEventTime == 0L) {
                firstVolumeEventTime = now
                lastVolumeEventTime = now
                return
            }

            // 600ms 이내로 촘촘히 들어오는 볼륨 이벤트는 길게 누르고 있는 것으로 본다.
            if (now - lastVolumeEventTime < 600L) {
                lastVolumeEventTime = now
                if (!isLongPressTriggered && now - firstVolumeEventTime >= LONG_PRESS_MS) {
                    isLongPressTriggered = true
                    triggerVibration()
                    stopBackgroundRecording()
                    // stopSelf는 호출하지 않는다. Recording.Finalize 이후 정리한다.
                }
            } else {
                firstVolumeEventTime = now
                lastVolumeEventTime = now
                isLongPressTriggered = false
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun service(): CameraForegroundService = this@CameraForegroundService
    }

    override fun onCreate() {
        super.onCreate()

        controller = CameraController(applicationContext)
        controller.init(this)

        // 녹화가 진짜로 끝났을 때 service를 안전하게 정리한다.
        controller.onFinalizeEnded = { exitForegroundAndStop() }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_RECORDING -> enterRecordingForeground()
            ACTION_STOP_RECORDING -> {
                if (controller.isRecording()) {
                    stopBackgroundRecording()
                } else {
                    exitForegroundAndStop()
                }
            }
        }

        // OS에 의해 자동 재시작되어 비녹화 상태에서 service가 부활하는 것을 막는다.
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!controller.isRecording()) {
            exitForegroundAndStop()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(volumeReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        controller.onFinalizeEnded = null
        controller.release()
        super.onDestroy()
    }

    fun startBackgroundRecording() {
        isLongPressTriggered = false
        firstVolumeEventTime = 0L
        lastVolumeEventTime = 0L
        controller.startRecording()
    }

    fun stopBackgroundRecording() {
        controller.stopRecording()
    }

    private fun enterRecordingForeground() {
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTI_ID, notification, type)
        } else {
            startForeground(NOTI_ID, notification)
        }
    }

    private fun exitForegroundAndStop() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopSelf()
    }

    private fun triggerVibration() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        200,
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(200)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "동기화",
                    NotificationManager.IMPORTANCE_MIN
                ).apply { description = "백그라운드 작업 진행 알림" }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, CameraForegroundService::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("동기화 중")
            .setContentText("백그라운드 작업이 진행 중입니다")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "작업 종료",
                stopPendingIntent
            )
            .build()
    }

    companion object {
        const val NOTI_ID = 1001
        const val CHANNEL_ID = "background_sync_channel"
        const val ACTION_START_RECORDING = "com.example.camera2study.ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.camera2study.ACTION_STOP_RECORDING"

        // 백그라운드 종료를 위한 볼륨 버튼 길게 누르기 임계값 (2초)
        private const val LONG_PRESS_MS = 2000L

        /**
         * 녹화 시작 직전에만 호출한다. service를 foreground 상태로 격상시킨다.
         */
        fun requestStartRecording(context: Context) {
            val intent = Intent(context, CameraForegroundService::class.java).apply {
                action = ACTION_START_RECORDING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraForegroundService::class.java))
        }
    }
}
