package com.example.camera2study

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService

class CameraForegroundService : LifecycleService() {

    private val binder = LocalBinder()
    lateinit var controller: CameraController
        private set

    inner class LocalBinder : Binder() {
        fun service(): CameraForegroundService = this@CameraForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        
        // 서비스 수명 주기를 갖는 CameraController 생성 및 바인딩
        controller = CameraController(applicationContext)
        controller.init(this)
        
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTI_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTI_ID, notification)
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        // 알림창에서 강제종료 버튼 수신 처리
        if (intent?.action == ACTION_STOP_RECORDING) {
            stopBackgroundRecording()
            stopSelf()
        }
        
        return START_STICKY
    }

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }

    fun startBackgroundRecording() {
        controller.startRecording()
    }

    fun stopBackgroundRecording() {
        controller.stopRecording()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "카메라 녹화",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "백그라운드 녹화 진행 알림" }
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
            .setContentTitle("백그라운드 녹화")
            .setContentText("Camera2Study 가 카메라를 사용 중입니다")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "녹화 종료",
                stopPendingIntent
            )
            .build()
    }

    companion object {
        const val NOTI_ID = 1001
        const val CHANNEL_ID = "camera_recording_channel"
        const val ACTION_STOP_RECORDING = "com.example.camera2study.ACTION_STOP_RECORDING"

        fun start(context: Context) {
            val intent = Intent(context, CameraForegroundService::class.java)
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
