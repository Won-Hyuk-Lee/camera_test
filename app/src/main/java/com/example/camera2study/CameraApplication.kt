package com.example.camera2study

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.example.camera2study.util.RecordingJournal

class CameraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        executor.execute {
            RecordingJournal.recover(this)
            Handler(Looper.getMainLooper()).post {
                if (RecordingRuntime.state.value?.active != true) {
                    if (RecordingJournal.recoveredInterruption && RecordingRuntime.state.value?.phase == RecordingPhase.IDLE) {
                        RecordingRuntime.state.value = RecordingState(RecordingPhase.ERROR,
                            message = "이전 녹화 중단 · 촬영 기록 확인")
                    }
                    RecordingStopWidgetProvider.updateAll(this)
                }
            }
            executor.shutdown()
        }
    }
}
