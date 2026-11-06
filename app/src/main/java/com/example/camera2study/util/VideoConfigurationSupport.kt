package com.example.camera2study.util

import android.util.Range
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraInfo
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import java.util.WeakHashMap
import java.util.concurrent.Executor

/** Queries the actual Preview + Video configuration, without opening a camera or GL thread. */
object VideoConfigurationSupport {
    private data class Key(val quality: Quality, val hdr: Boolean, val effect: Boolean)
    private val cache = WeakHashMap<CameraInfo, MutableMap<Key, List<Int>>>()

    fun dynamicRange(info: CameraInfo, hdr: Boolean): DynamicRange? {
        if (!hdr) return DynamicRange.SDR
        val ranges = Recorder.getVideoCapabilities(info).supportedDynamicRanges
        return DynamicRange.HLG_10_BIT.takeIf { it in ranges }
            ?: ranges.filter { it.bitDepth == DynamicRange.BIT_DEPTH_10_BIT }
                .sortedBy { it.encoding }.firstOrNull()
    }

    @Synchronized
    fun supportedFps(info: CameraInfo, quality: Quality, hdr: Boolean, hasEffect: Boolean): List<Int> {
        val key = Key(quality, hdr, hasEffect)
        return cache.getOrPut(info) { mutableMapOf() }.getOrPut(key) {
            runCatching {
                if (hdr && hasEffect) return@getOrPut emptyList()
                val range = dynamicRange(info, hdr) ?: return@getOrPut emptyList()
                if (quality !in Recorder.getVideoCapabilities(info).getSupportedQualities(range)) return@getOrPut emptyList()
                val preview = Preview.Builder().setDynamicRange(range).setResolutionSelector(
                    ResolutionSelector.Builder().setAspectRatioStrategy(
                        AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build()).build()
                val video = VideoCapture.Builder(Recorder.Builder().setQualitySelector(QualitySelector.from(quality))
                    .setAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9).build()).setDynamicRange(range).build()
                val effects = if (hasEffect) listOf(queryEffect()) else emptyList()
                val supported = info.getSupportedFrameRateRanges(SessionConfig(listOf(preview, video), effects = effects))
                // Do not advertise 60 FPS when CameraX would silently fall back to a variable/lower rate.
                listOf(30, 60).filter { Range(it, it) in supported }
            }.getOrDefault(emptyList())
        }
    }

    fun problem(info: CameraInfo, quality: Quality, fps: Int, hdr: Boolean, hasEffect: Boolean): String? {
        if (hdr && hasEffect) return "색보정은 SDR 전용입니다. HDR 또는 색보정을 꺼 주세요"
        val range = dynamicRange(info, hdr) ?: return "선택한 카메라는 HDR 녹화를 지원하지 않습니다"
        if (quality !in Recorder.getVideoCapabilities(info).getSupportedQualities(range)) return "선택한 렌즈는 이 화질/HDR 조합을 지원하지 않습니다"
        if (fps !in supportedFps(info, quality, hdr, hasEffect)) return "선택한 렌즈·화질·HDR·색보정 조합에서는 ${fps} FPS를 지원하지 않습니다"
        return null
    }

    private fun queryEffect(): CameraEffect = object : CameraEffect(CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE, Executor { it.run() },
        object : SurfaceProcessor {
            override fun onInputSurface(request: SurfaceRequest) { request.willNotProvideSurface() }
            override fun onOutputSurface(output: SurfaceOutput) { output.close() }
        }, androidx.core.util.Consumer { }) {}
}
