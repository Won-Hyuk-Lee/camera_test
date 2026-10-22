package com.example.camera2study

/** Immutable limits for one user initiated session. Zero means no overall limit. */
data class RecordingPlan(val segmentMs: Long, val maxFiles: Int = 0, val totalMs: Long = 0) {
    init { require(segmentMs > 0 && maxFiles >= 0 && totalMs >= 0) }
    fun nextDuration(recordedMs: Long): Long = if (totalMs == 0L) segmentMs
        else minOf(segmentMs, (totalMs - recordedMs).coerceAtLeast(0L))
    fun continues(completedFiles: Int, recordedMs: Long): Boolean =
        (maxFiles == 0 || completedFiles < maxFiles) && (totalMs == 0L || totalMs - recordedMs >= 1_000L)
}
