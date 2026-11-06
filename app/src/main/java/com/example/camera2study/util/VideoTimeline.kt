package com.example.camera2study.util

/** Timestamp-only evidence. This deliberately makes no claim about decoded image content. */
data class VideoTimeline(
    val samples: Int,
    val firstUs: Long,
    val lastUs: Long,
    val maxGapUs: Long,
    val missingTailUs: Long,
    val complete: Boolean,
    val maxGapStartUs: Long? = null,
    val maxGapEndUs: Long? = null
) {
    val needsReview: Boolean get() = samples == 0 || maxGapUs > 500_000L ||
        (complete && (firstUs > 1_000_000L || missingTailUs > 1_000_000L))

    companion object {
        fun analyze(timestamps: LongArray, expectedDurationUs: Long, complete: Boolean): VideoTimeline {
            if (timestamps.isEmpty()) return VideoTimeline(0, 0, 0, 0, 0, complete)
            // B-frame streams can be extracted in decode order; compare presentation order.
            val sorted = timestamps.sortedArray()
            var largest = 0L
            var largestStart: Long? = null
            var largestEnd: Long? = null
            var smallest = Long.MAX_VALUE
            for (index in 1 until sorted.size) {
                val gap = sorted[index] - sorted[index - 1]
                if (gap > largest) {
                    largest = gap
                    largestStart = sorted[index - 1]
                    largestEnd = sorted[index]
                }
                if (gap > 0 && gap < smallest) smallest = gap
            }
            val finalFrameUs = if (smallest == Long.MAX_VALUE) 0L else smallest
            val missingTail = if (complete) (expectedDurationUs - sorted.last() - finalFrameUs).coerceAtLeast(0) else 0L
            return VideoTimeline(sorted.size, sorted.first(), sorted.last(), largest, missingTail, complete, largestStart, largestEnd)
        }
    }
}
