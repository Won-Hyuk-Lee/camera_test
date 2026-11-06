package com.example.camera2study.util

/** A recording handoff gets a finite retry budget; user edits never extend its deadline. */
class PreviewHandoffRetry(private val deadlineMs: Long) {
    private var attempts = 0

    fun nextDelay(nowMs: Long, resourceBusy: Boolean, initialReady: Boolean): Long? {
        if (!resourceBusy || initialReady || nowMs >= deadlineMs || attempts >= 2) return null
        val delay = if (attempts == 0) 250L else 500L
        if (nowMs + delay >= deadlineMs) return null
        attempts++
        return delay
    }
}
