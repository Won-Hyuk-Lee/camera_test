package com.example.camera2study.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

object CaptureHealth {
    fun problem(context: Context, audio: Boolean, starting: Boolean): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return "카메라 권한이 없습니다"
        if (audio && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "마이크 권한이 없습니다"
        if (audio && context.getSystemService(android.media.AudioManager::class.java).isMicrophoneMute) return "마이크가 음소거되어 있습니다. 소리 없이 촬영하려면 마이크 녹음을 끄세요"
        val storage = context.getExternalFilesDir(null) ?: context.filesDir
        if (storage.usableSpace < (if (starting) 64L else 32L) * 1024 * 1024) return "저장공간이 부족해 녹화를 계속할 수 없습니다"
        if (Build.VERSION.SDK_INT >= 29 && context.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL) return "기기 과열로 녹화를 종료합니다"
        return null
    }
}
