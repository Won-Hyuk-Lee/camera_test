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
    private var controller: CameraController? = null

    inner class LocalBinder : Binder() {
        fun service(): CameraForegroundService = this@CameraForegroundService
    }

    override fun onCreate() {
        super.onCreate()
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
        return START_STICKY
    }

    override fun onDestroy() {
        controller?.stopRecording()
        controller?.release()
        controller = null
        super.onDestroy()
    }

    fun attachController(c: CameraController) {
        controller = c
    }

    fun detachController() {
        controller = null
    }

    fun startBackgroundRecording() {
        controller?.startRecording()
    }

    fun stopBackgroundRecording() {
        controller?.stopRecording()
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("백그라운드 녹화")
            .setContentText("Camera2Study 가 카메라를 사용 중입니다")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        const val NOTI_ID = 1001
        const val CHANNEL_ID = "camera_recording_channel"

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
