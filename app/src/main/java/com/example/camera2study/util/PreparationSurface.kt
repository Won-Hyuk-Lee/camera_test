package com.example.camera2study.util

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import androidx.camera.core.SurfaceRequest
import java.util.concurrent.Executor

/** Drains an opaque Preview surface only while a recording starts without its original view. */
internal object PreparationSurface {
    fun provide(request: SurfaceRequest, handler: Handler, executor: Executor, onFailure: (Exception) -> Unit) {
        val size = request.resolution
        val reader = try {
            ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE, 2)
        } catch (e: Exception) {
            request.willNotProvideSurface()
            onFailure(e)
            return
        }
        var closed = false
        var drainFailed = false
        fun closeReader() {
            if (closed) return
            closed = true
            reader.setOnImageAvailableListener(null, null)
            reader.close()
        }
        try {
            reader.setOnImageAvailableListener({ source ->
                if (!closed && !drainFailed) {
                    try { source.acquireLatestImage()?.close() }
                    catch (e: Exception) { drainFailed = true; onFailure(e) }
                }
            }, handler)
            // CameraX owns the surface until this callback, even when the view returns or the service stops.
            request.provideSurface(reader.surface, executor) { closeReader() }
        } catch (e: Exception) {
            closeReader()
            request.willNotProvideSurface()
            onFailure(e)
        }
    }
}
