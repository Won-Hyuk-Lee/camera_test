package com.example.camera2study

import com.example.camera2study.util.VideoTimeline
import org.junit.Assert.*
import org.junit.Test

class VideoTimelineTest {
    @Test fun reorderedPresentationTimestampsDoNotCreateFalseGaps() {
        val result = VideoTimeline.analyze(longArrayOf(0, 99_999, 33_333, 33_333, 66_666, 133_332), 166_665, true)
        assertEquals(33_333L, result.maxGapUs)
        assertEquals(0L, result.maxGapStartUs)
        assertEquals(33_333L, result.maxGapEndUs)
        assertFalse(result.needsReview)
    }
    @Test fun audioLengthBeyondLastVideoTimestampNeedsReview() {
        val result = VideoTimeline.analyze(longArrayOf(0, 33_333, 66_666), 40_000_000, true)
        assertTrue(result.missingTailUs > 39_000_000)
        assertTrue(result.needsReview)
    }
    @Test fun partialScanNeverTreatsUnexaminedTailAsMissingVideo() {
        val result = VideoTimeline.analyze(longArrayOf(0, 33_333, 66_666), 40_000_000, false)
        assertEquals(0L, result.missingTailUs)
        assertFalse(result.complete)
    }
    @Test fun regularTimestampsAreAcceptedRegardlessOfSceneMotion() {
        val result = VideoTimeline.analyze(LongArray(300) { it * 33_333L }, 10_000_000, true)
        assertFalse(result.needsReview)
    }
    @Test fun internalVideoGapIsReportedEvenWhenTailReachesDuration() {
        val result = VideoTimeline.analyze(longArrayOf(0, 33_333, 3_000_000, 3_000_000, 3_033_333), 3_066_666, true)
        assertEquals(2_966_667L, result.maxGapUs)
        assertEquals(33_333L, result.maxGapStartUs)
        assertEquals(3_000_000L, result.maxGapEndUs)
        assertEquals(0L, result.missingTailUs)
        assertTrue(result.needsReview)
    }
}
