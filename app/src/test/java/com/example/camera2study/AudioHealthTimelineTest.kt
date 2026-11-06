package com.example.camera2study

import com.example.camera2study.util.AudioHealthTimeline
import org.junit.Assert.*
import org.junit.Test

class AudioHealthTimelineTest {
    @Test fun recoveryRetainsTheExactAffectedPartOfTheRecording() {
        val timeline = AudioHealthTimeline(true)
        timeline.observe("ACTIVE", false, 0)
        timeline.observe("SOURCE_SILENCED", true, 2_000, "system priority")
        assertEquals(3_000L, timeline.snapshot(5_000).issueDurationMs)
        timeline.observe("ACTIVE", false, 6_000)
        timeline.finish(10_000)
        val result = timeline.snapshot(10_000)
        assertTrue(result.hadIssues)
        assertEquals(1, result.issueCount)
        assertEquals(4_000L, result.issueDurationMs)
        assertEquals(2_000L, result.intervals.single().startMs)
        assertEquals(6_000L, result.intervals.single().endMs)
    }

    @Test fun repeatedStatusAndBackwardTimestampsDoNotDuplicateOrSubtractIssues() {
        val timeline = AudioHealthTimeline(true)
        timeline.observe("SOURCE_ERROR", true, 1_000)
        assertFalse(timeline.observe("SOURCE_ERROR", true, 2_000))
        timeline.observe("ACTIVE", false, 500)
        val result = timeline.snapshot(2_000)
        assertEquals(1, result.issueCount)
        assertEquals(1_000L, result.issueDurationMs)
    }

    @Test fun limitedHistoryKeepsTotalIssueCountsAndDuration() {
        val timeline = AudioHealthTimeline(true, maxIntervals = 2)
        repeat(4) { index ->
            timeline.observe("SOURCE_SILENCED", true, index * 2_000L)
            timeline.observe("ACTIVE", false, index * 2_000L + 1_000)
        }
        val result = timeline.snapshot(8_000)
        assertEquals(4, result.issueCount)
        assertEquals(4_000L, result.issueDurationMs)
        assertEquals(2, result.intervals.size)
        assertEquals(2, result.droppedIntervals)
    }

    @Test fun finishClosesAnOutstandingIssueOnlyOnce() {
        val timeline = AudioHealthTimeline(true)
        timeline.observe("ENCODER_ERROR", true, 1_000)
        timeline.finish(3_000)
        timeline.finish(4_000)
        assertEquals(2_000L, timeline.snapshot(4_000).issueDurationMs)
    }

    @Test fun intentionallyDisabledAudioIsNotAnError() {
        val timeline = AudioHealthTimeline(false)
        timeline.observe("DISABLED", true, 100)
        assertFalse(timeline.snapshot(1_000).hadIssues)
        assertEquals("DISABLED", timeline.snapshot(1_000).currentState)
    }
}
