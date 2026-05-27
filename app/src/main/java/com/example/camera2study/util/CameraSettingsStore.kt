package com.example.camera2study.util

import android.content.Context
import android.hardware.camera2.CameraMetadata
import androidx.camera.video.Quality
import com.example.camera2study.CameraController

object CameraSettingsStore {
    private const val PREF_NAME = "camera_pref"

    private const val KEY_LENS_FACING_BACK = "last_lens_facing_back"
    private const val KEY_ZOOM_RATIO = "last_zoom_ratio"
    private const val KEY_ZOOM_MODE = "last_zoom_mode"
    private const val KEY_RATIO_MODE = "last_ratio_mode"
    private const val KEY_QUALITY = "last_quality"
    private const val KEY_MAX_DURATION_MS = "last_max_duration_ms"
    private const val KEY_MAX_REPEAT_COUNT = "last_max_repeat_count"
    private const val KEY_FPS = "last_fps"
    private const val KEY_HDR = "last_hdr"
    private const val KEY_PRO_MODE = "last_pro_mode"
    private const val KEY_AWB_MODE = "last_awb_mode"
    private const val KEY_COLOR_TEMP_K = "last_color_temp_k"
    private const val KEY_EXPOSURE_TIME_NS = "last_exposure_time_ns"
    private const val KEY_APERTURE = "last_aperture"

    const val DEFAULT_COLOR_TEMP_K = 5500
    private const val NO_EXPOSURE_TIME = -1L
    private const val NO_APERTURE = -1f

    data class CameraSettings(
        val lensFacingBack: Boolean = true,
        val zoomRatio: Float = 1.0f,
        val zoomModeRatio: Float = 1.0f,
        val ratioMode: Int = CameraController.RATIO_3_4,
        val quality: Quality = Quality.HD,
        val maxDurationMs: Long = 0L,
        val maxRepeatCount: Int = 1,
        val fps: Int = 30,
        val hdrEnabled: Boolean = false,
        val proMode: Boolean = false,
        val awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
        val colorTempK: Int = DEFAULT_COLOR_TEMP_K,
        val exposureTimeNs: Long? = null,
        val aperture: Float? = null
    )

    fun load(context: Context): CameraSettings {
        val pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val exposureTime = pref.getLong(KEY_EXPOSURE_TIME_NS, NO_EXPOSURE_TIME)
        val aperture = pref.getFloat(KEY_APERTURE, NO_APERTURE)
        return CameraSettings(
            lensFacingBack = pref.getBoolean(KEY_LENS_FACING_BACK, true),
            zoomRatio = pref.getFloat(KEY_ZOOM_RATIO, 1.0f),
            zoomModeRatio = pref.getFloat(KEY_ZOOM_MODE, 1.0f),
            ratioMode = pref.getInt(KEY_RATIO_MODE, CameraController.RATIO_3_4),
            quality = qualityFromName(pref.getString(KEY_QUALITY, null)),
            maxDurationMs = pref.getLong(KEY_MAX_DURATION_MS, 0L),
            maxRepeatCount = pref.getInt(KEY_MAX_REPEAT_COUNT, 1),
            fps = pref.getInt(KEY_FPS, 30),
            hdrEnabled = pref.getBoolean(KEY_HDR, false),
            proMode = pref.getBoolean(KEY_PRO_MODE, false),
            awbMode = pref.getInt(KEY_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO),
            colorTempK = pref.getInt(KEY_COLOR_TEMP_K, DEFAULT_COLOR_TEMP_K),
            exposureTimeNs = exposureTime.takeIf { it != NO_EXPOSURE_TIME },
            aperture = aperture.takeIf { it != NO_APERTURE }
        )
    }

    fun save(context: Context, settings: CameraSettings) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LENS_FACING_BACK, settings.lensFacingBack)
            .putFloat(KEY_ZOOM_RATIO, settings.zoomRatio)
            .putFloat(KEY_ZOOM_MODE, settings.zoomModeRatio)
            .putInt(KEY_RATIO_MODE, settings.ratioMode)
            .putString(KEY_QUALITY, qualityToName(settings.quality))
            .putLong(KEY_MAX_DURATION_MS, settings.maxDurationMs)
            .putInt(KEY_MAX_REPEAT_COUNT, settings.maxRepeatCount)
            .putInt(KEY_FPS, settings.fps)
            .putBoolean(KEY_HDR, settings.hdrEnabled)
            .putBoolean(KEY_PRO_MODE, settings.proMode)
            .putInt(KEY_AWB_MODE, settings.awbMode)
            .putInt(KEY_COLOR_TEMP_K, settings.colorTempK)
            .putLong(KEY_EXPOSURE_TIME_NS, settings.exposureTimeNs ?: NO_EXPOSURE_TIME)
            .putFloat(KEY_APERTURE, settings.aperture ?: NO_APERTURE)
            .apply()
    }

    fun snapshot(context: Context, controller: CameraController, colorTempK: Int): CameraSettings {
        val existing = load(context)
        return CameraSettings(
            lensFacingBack = controller.isFacingBack(),
            zoomRatio = controller.zoomRatio,
            zoomModeRatio = controller.selectedZoomModeRatio,
            ratioMode = controller.currentRatioMode,
            quality = controller.videoQuality,
            maxDurationMs = controller.maxDurationMs,
            maxRepeatCount = controller.maxRepeatCount,
            fps = controller.targetFps,
            hdrEnabled = controller.isHdrEnabled,
            proMode = controller.isProMode,
            awbMode = controller.getAwbMode(),
            colorTempK = colorTempK.coerceIn(3000, 8000),
            exposureTimeNs = controller.getExposureTime(),
            aperture = controller.getAperture()
        ).copy(colorTempK = colorTempK.takeIf { it in 3000..8000 } ?: existing.colorTempK)
    }

    fun applyToController(controller: CameraController, settings: CameraSettings) {
        controller.videoQuality = settings.quality
        controller.maxDurationMs = settings.maxDurationMs
        controller.maxRepeatCount = settings.maxRepeatCount
        controller.targetFps = settings.fps
        controller.isHdrEnabled = settings.hdrEnabled
        controller.isProMode = settings.proMode
        controller.setAwbMode(settings.awbMode)
        controller.setManualWbGains(
            if (settings.proMode && settings.awbMode == CameraMetadata.CONTROL_AWB_MODE_OFF) {
                kelvinToGains(settings.colorTempK)
            } else {
                null
            }
        )
        controller.setExposureTime(settings.exposureTimeNs.takeIf { settings.proMode })
        controller.setAperture(settings.aperture.takeIf { settings.proMode })

        if (!settings.lensFacingBack && controller.isFacingBack()) {
            controller.switchFacing()
        } else if (settings.lensFacingBack && !controller.isFacingBack()) {
            controller.switchFacing()
        }

        if (controller.isFacingBack()) {
            controller.selectBackZoomMode(settings.zoomModeRatio)
        } else {
            controller.setZoomRatio(settings.zoomRatio)
        }

        controller.currentRatioMode = settings.ratioMode
    }

    fun kelvinToGains(kelvin: Int): FloatArray {
        val k = kelvin.coerceIn(3000, 8000)
        return when {
            k <= DEFAULT_COLOR_TEMP_K -> {
                val t = (k - 3000) / 2500f
                val r = lerp(1.8f, 1.0f, t)
                val b = lerp(0.6f, 1.0f, t)
                floatArrayOf(r, 1.0f, 1.0f, b)
            }
            else -> {
                val t = (k - DEFAULT_COLOR_TEMP_K) / 2500f
                val r = lerp(1.0f, 0.7f, t)
                val b = lerp(1.0f, 1.6f, t)
                floatArrayOf(r, 1.0f, 1.0f, b)
            }
        }
    }

    private fun qualityToName(quality: Quality): String =
        when (quality) {
            Quality.UHD -> "UHD"
            Quality.FHD -> "FHD"
            Quality.HD -> "HD"
            Quality.SD -> "SD"
            else -> "HD"
        }

    private fun qualityFromName(name: String?): Quality =
        when (name) {
            "UHD" -> Quality.UHD
            "FHD" -> Quality.FHD
            "SD" -> Quality.SD
            else -> Quality.HD
        }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
