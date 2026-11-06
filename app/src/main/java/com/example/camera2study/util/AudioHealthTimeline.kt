package com.example.camera2study.util

/** Audio issues are recorded on the video timeline, excluding pauses and file-switch gaps. */
class AudioHealthTimeline(val enabled: Boolean, private val maxIntervals: Int = 100) {
    data class Interval(val state: String, val startMs: Long, val endMs: Long, val error: String) {
        val durationMs: Long get() = endMs - startMs
    }
    data class Snapshot(val enabled: Boolean, val currentState: String, val issueCount: Int,
                        val issueDurationMs: Long, val intervals: List<Interval>, val droppedIntervals: Int) {
        val hadIssues: Boolean get() = issueCount > 0
    }
    init { require(maxIntervals > 0) }
    private var state = if (enabled) "PENDING" else "DISABLED"
    private var issue = false
    private var error = ""
    private var startedMs = 0L
    private var lastMs = 0L
    private var count = 0
    private var closedDurationMs = 0L
    private var dropped = 0
    private val intervals = ArrayDeque<Interval>()

    fun observe(state: String, isIssue: Boolean, atMs: Long, error: String? = null): Boolean {
        val time = atMs.coerceAtLeast(lastMs)
        lastMs = time
        if (!enabled || (this.state == state && issue == isIssue && this.error == error.orEmpty())) return false
        closeInterval(time)
        this.state = state
        this.error = error.orEmpty()
        issue = isIssue
        startedMs = time
        if (isIssue) count++
        return true
    }

    fun finish(atMs: Long) {
        lastMs = atMs.coerceAtLeast(lastMs)
        closeInterval(lastMs)
    }

    fun snapshot(atMs: Long): Snapshot {
        val end = atMs.coerceAtLeast(lastMs)
        val open = if (issue) Interval(state, startedMs, end, error) else null
        val visible = intervals.toList() + listOfNotNull(open)
        return Snapshot(enabled, state, count, closedDurationMs + (open?.durationMs ?: 0L),
            visible.takeLast(maxIntervals), dropped + (visible.size - maxIntervals).coerceAtLeast(0))
    }

    private fun closeInterval(atMs: Long) {
        if (!issue) return
        val interval = Interval(state, startedMs, atMs, error)
        closedDurationMs += interval.durationMs
        if (intervals.size == maxIntervals) { intervals.removeFirst(); dropped++ }
        intervals.addLast(interval)
        issue = false
    }
}
