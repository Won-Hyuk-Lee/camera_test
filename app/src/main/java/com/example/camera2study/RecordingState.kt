package com.example.camera2study

import androidx.lifecycle.MutableLiveData

enum class RecordingPhase { IDLE, PREPARING, RECORDING, PAUSED, FINALIZING, BETWEEN_SEGMENTS, COMPLETED, ERROR }

data class RecordingState(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val sessionId: String = "",
    val segment: Int = 0,
    val durationMs: Long = 0,
    val bytes: Long = 0,
    val message: String = "대기",
    val file: String = ""
) {
    val active: Boolean get() = phase in setOf(RecordingPhase.PREPARING, RecordingPhase.RECORDING,
        RecordingPhase.PAUSED, RecordingPhase.FINALIZING, RecordingPhase.BETWEEN_SEGMENTS)
}

object RecordingRuntime {
    val state = MutableLiveData(RecordingState())
}
