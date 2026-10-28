package com.example.camera2study

import com.example.camera2study.util.VideoLook
import org.junit.Assert.*
import org.junit.Test

class VideoLookTest {
    @Test fun naturalPreservesPixels() {
        val rgb = floatArrayOf(0.12f, 0.47f, 0.91f)
        assertArrayEquals(rgb, VideoLook().parameters().apply(rgb), 0.00001f)
    }
    @Test fun zeroStrengthDisablesAllCorrections() {
        assertTrue(VideoLook("DARK_ORANGE", 0f, 1f, -1f, 1f, -1f).isIdentity())
    }
    @Test fun darkOrangeRaisesShadowsAndReducesWarmCast() {
        val result = VideoLook("DARK_ORANGE", 1f).parameters().apply(floatArrayOf(0.08f, 0.04f, 0.02f))
        assertTrue(result[1] > 0.04f)
        assertTrue(result[0] / result[2] < 4f)
    }
    @Test fun invalidSettingsStayFiniteAndBounded() {
        val look = VideoLook("unknown", Float.NaN, 50f, -50f, 10f, -10f).normalized()
        assertEquals("NATURAL", look.preset)
        assertTrue(look.parameters().apply(floatArrayOf(0f, 0.5f, 1f)).all { it.isFinite() && it in 0f..1f })
    }
}
