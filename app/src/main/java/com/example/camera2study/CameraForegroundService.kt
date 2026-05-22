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

import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Vibrator
import android.widget.Toast

class CameraForegroundService : LifecycleService(), SensorEventListener {

    private val binder = LocalBinder()
    lateinit var controller: CameraController
        private set

    // --- 백그라운드 원격 종료 변수 ---
    private var audioManager: AudioManager? = null
    private var initialVolume: Int = -1
    private var firstVolumeEventTime = 0L
    private var lastVolumeEventTime = 0L
    private var isLongPressTriggered = false

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                val now = System.currentTimeMillis()
                val am = audioManager ?: return
                val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)

                if (!controller.isRecording()) {
                    // 녹화 중이 아닐 때는 일반적인 볼륨 변경으로 처리
                    initialVolume = currentVol
                    return
                }

                if (initialVolume == -1) {
                    initialVolume = currentVol
                }

                if (firstVolumeEventTime == 0L) {
                    firstVolumeEventTime = now
                    lastVolumeEventTime = now
                } else {
                    // 이전 볼륨 이벤트와의 시간 격차가 500ms 이내로 촘촘히 들어오고 있다면 꾹 누르고 있는 중으로 판단
                    if (now - lastVolumeEventTime < 600L) {
                        lastVolumeEventTime = now
                        if (now - firstVolumeEventTime >= 1800L && !isLongPressTriggered) { // 약 1.8초~2초 연속 누름
                            isLongPressTriggered = true
                            
                            // 진동 피드백
                            triggerVibration()
                            
                            // 녹화 중단 및 서비스 종료
                            stopBackgroundRecording()
                            
                            // 볼륨 원상 복구
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, initialVolume, 0)
                            
                            Toast.makeText(applicationContext, "물리 버튼 감지: 촬영을 종료합니다", Toast.LENGTH_SHORT).show()
                            stopSelf()
                        }
                    } else {
                        // 꾹 누른 텀이 깨졌으므로 초기화
                        firstVolumeEventTime = now
                        lastVolumeEventTime = now
                        isLongPressTriggered = false
                    }
                }
            }
        }
    }

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lastShakeTime = 0L

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

        // 볼륨 키 리시버 등록
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        initialVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))

        // 가속도 센서 등록 (흔들기 종료용)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
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
        // 리시버 및 센서 해제
        try {
            unregisterReceiver(volumeReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        sensorManager?.unregisterListener(this)

        controller.release()
        super.onDestroy()
    }

    fun startBackgroundRecording() {
        isLongPressTriggered = false
        firstVolumeEventTime = 0L
        initialVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        controller.startRecording()
    }


    fun stopBackgroundRecording() {
        controller.stopRecording()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val acceleration = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            // 중력가속도(약 9.8) 제외하고 격렬한 흔들림 감지 (임계값 19.0f)
            if (acceleration > 19.0f) {
                val now = System.currentTimeMillis()
                if (now - lastShakeTime > 1500L) {
                    lastShakeTime = now
                    if (controller.isRecording()) {
                        triggerVibration()
                        stopBackgroundRecording()
                        Toast.makeText(applicationContext, "흔들림 감지: 촬영을 종료합니다", Toast.LENGTH_SHORT).show()
                        stopSelf()
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun triggerVibration() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(300, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(300)
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
                    "카메라 녹화",
                    NotificationManager.IMPORTANCE_MIN // IMPORTANCE_MIN 으로 하여 알림바 무음 및 최소 노출 보장
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
            .setContentText("Camera2Study 가 백그라운드 촬영을 안전하게 진행 중입니다")
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
