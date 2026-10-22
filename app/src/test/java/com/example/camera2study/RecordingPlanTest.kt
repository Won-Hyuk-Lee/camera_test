package com.example.camera2study

import org.junit.Assert.*
import org.junit.Test

class RecordingPlanTest {
    @Test fun unlimitedDoesNotStopAtAnArbitraryFileCount() {
        val plan = RecordingPlan(1_800_000)
        assertTrue(plan.continues(1000, 1_800_000_000))
        assertEquals(1_800_000L, plan.nextDuration(1_800_000_000))
    }
    @Test fun fileLimitCountsTotalFilesNotAdditionalRepeats() {
        val plan = RecordingPlan(10_000, maxFiles = 2)
        assertTrue(plan.continues(1, 10_000))
        assertFalse(plan.continues(2, 20_000))
    }
    @Test fun totalLimitShortensFinalSegmentAndPreventsTinyTail() {
        val plan = RecordingPlan(10_000, totalMs = 25_000)
        assertEquals(5_000L, plan.nextDuration(20_000))
        assertFalse(plan.continues(3, 24_950))
        assertEquals(0L, plan.nextDuration(30_000))
    }
    @Test fun earlierOfTimeAndFileLimitsWins() {
        assertFalse(RecordingPlan(10_000, 2, 60_000).continues(2, 20_000))
        assertFalse(RecordingPlan(10_000, 8, 20_000).continues(2, 20_000))
    }
    @Test fun encoderRoundingDoesNotCreateAnUnplayableThirdFile() {
        val plan = RecordingPlan(10_000, totalMs = 20_000)
        assertTrue(plan.continues(1, 9_927))
        assertFalse(plan.continues(2, 19_866))
        assertTrue(plan.continues(2, 19_000))
    }
}
