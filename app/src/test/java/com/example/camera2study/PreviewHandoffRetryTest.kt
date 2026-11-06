package com.example.camera2study

import com.example.camera2study.util.PreviewHandoffRetry
import com.example.camera2study.util.isPreviewResourceBusy
import androidx.camera.core.CameraUnavailableException
import org.junit.Assert.*
import org.junit.Test

class PreviewHandoffRetryTest {
    @Test fun aBusyRecordingHandoffHasOnlyTwoAttempts() {
        val retry = PreviewHandoffRetry(2_000)
        assertEquals(250L, retry.nextDelay(180, resourceBusy = true, initialReady = false))
        assertEquals(500L, retry.nextDelay(500, resourceBusy = true, initialReady = false))
        assertNull(retry.nextDelay(1_000, resourceBusy = true, initialReady = false))
    }

    @Test fun otherFailuresAndEstablishedPreviewsAreNotRetried() {
        val retry = PreviewHandoffRetry(2_000)
        assertNull(retry.nextDelay(100, resourceBusy = false, initialReady = false))
        assertNull(retry.nextDelay(100, resourceBusy = true, initialReady = true))
        assertEquals(250L, retry.nextDelay(100, resourceBusy = true, initialReady = false))
    }

    @Test fun aRetryCannotExtendTheHandoffWindow() {
        assertNull(PreviewHandoffRetry(2_000).nextDelay(1_800, true, false))
        assertNull(PreviewHandoffRetry(0).nextDelay(0, true, false))
    }

    @Test fun onlyTypedResourceBusyErrorsQualifyForAHandoffRetry() {
        assertTrue(isPreviewResourceBusy(IllegalStateException("bind", CameraUnavailableException(CameraUnavailableException.CAMERA_IN_USE))))
        assertTrue(isPreviewResourceBusy(CameraUnavailableException(CameraUnavailableException.CAMERA_MAX_IN_USE)))
        assertFalse(isPreviewResourceBusy(CameraUnavailableException(CameraUnavailableException.CAMERA_DISABLED)))
        assertFalse(isPreviewResourceBusy(IllegalArgumentException("unsupported stream configuration")))
    }
}
