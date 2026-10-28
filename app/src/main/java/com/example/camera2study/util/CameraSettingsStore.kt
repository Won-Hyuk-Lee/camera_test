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
        val ratioMode: Int = CameraController.RATIO_16_9,
        val cameraId: String? = null,
        val exposureEv: Float = 0f,
        val audioEnabled: Boolean = true,
        val privateSave: Boolean = true,
        val orientation: String = "AUTO",
        val mirrorFront: Boolean = false,
        val quality: Quality = Quality.HD,
        val maxDurationMs: Long = 1_800_000L,
        val maxRepeatCount: Int = 0,
        val totalDurationMs: Long = 0L,
        val fps: Int = 30,
        val hdrEnabled: Boolean = false,
        val proMode: Boolean = false,
        val awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
        val colorTempK: Int = DEFAULT_COLOR_TEMP_K,
        val exposureTimeNs: Long? = null,
        val manualIso: Int = 400,
        val look: VideoLook = VideoLook(),
        val aperture: Float? = null
    )

    fun load(context: Context, quick: Boolean = false): CameraSettings {
        val pref = context.getSharedPreferences(if (quick) "quick_recording_pref" else PREF_NAME, Context.MODE_PRIVATE)
        val exposureTime = pref.getLong(KEY_EXPOSURE_TIME_NS, NO_EXPOSURE_TIME)
        val aperture = pref.getFloat(KEY_APERTURE, NO_APERTURE)
        return CameraSettings(
            look = VideoLook(pref.getString("look_preset", "NATURAL") ?: "NATURAL", pref.getFloat("look_strength", .65f),
                pref.getFloat("look_temperature", 0f), pref.getFloat("look_tint", 0f), pref.getFloat("look_contrast", 0f), pref.getFloat("look_saturation", 0f)).normalized(),
            manualIso = pref.getInt("manual_iso", 400).coerceAtLeast(1),
            cameraId = pref.getString("camera_id", null),
            exposureEv = pref.getFloat("exposure_ev", 0f),
            audioEnabled = pref.getBoolean("audio_enabled", true),
            privateSave = pref.getBoolean("private_save", true),
            orientation = pref.getString("orientation", "AUTO") ?: "AUTO",
            mirrorFront = pref.getBoolean("mirror_front", false),
            lensFacingBack = pref.getBoolean(KEY_LENS_FACING_BACK, true),
            zoomRatio = pref.getFloat(KEY_ZOOM_RATIO, 1.0f),
            zoomModeRatio = pref.getFloat(KEY_ZOOM_MODE, 1.0f),
            ratioMode = CameraController.RATIO_16_9,
            quality = qualityFromName(pref.getString(KEY_QUALITY, null)),
            maxDurationMs = pref.getLong(KEY_MAX_DURATION_MS, 0L).takeIf { it > 0 } ?: 1_800_000L,
            maxRepeatCount = if (pref.getLong(KEY_MAX_DURATION_MS, 0L) == 0L) 0 else pref.getInt(KEY_MAX_REPEAT_COUNT, 0).takeIf { it in 0..998 } ?: 0,
            totalDurationMs = pref.getLong("total_duration_ms", 0L),
            fps = pref.getInt(KEY_FPS, 30),
            hdrEnabled = pref.getBoolean(KEY_HDR, false),
            proMode = pref.getBoolean(KEY_PRO_MODE, false),
            awbMode = pref.getInt(KEY_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO),
            colorTempK = pref.getInt(KEY_COLOR_TEMP_K, DEFAULT_COLOR_TEMP_K),
            exposureTimeNs = exposureTime.takeIf { it != NO_EXPOSURE_TIME },
            aperture = aperture.takeIf { it != NO_APERTURE }
        )
    }

    fun save(context: Context, settings: CameraSettings, quick: Boolean = false) {
        val json = org.json.JSONObject()
        val bundle = toBundle(settings)
        bundle.keySet().forEach { json.put(it, bundle.get(it) ?: org.json.JSONObject.NULL) }
        context.getSharedPreferences(if (quick) "quick_recording_pref" else PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("schema_version", 2)
            .putString((if (settings.lensFacingBack) "back" else "front") + "_profile", json.toString())
            .putString("${if (settings.lensFacingBack) "back" else "front"}_camera_id", settings.cameraId)
            .putFloat("${if (settings.lensFacingBack) "back" else "front"}_zoom", settings.zoomRatio)
            .putFloat("${if (settings.lensFacingBack) "back" else "front"}_ev", settings.exposureEv)
            .putString("look_preset", settings.look.normalized().preset)
            .putFloat("look_strength", settings.look.normalized().strength)
            .putFloat("look_temperature", settings.look.normalized().temperature)
            .putFloat("look_tint", settings.look.normalized().tint)
            .putFloat("look_contrast", settings.look.normalized().contrast)
            .putFloat("look_saturation", settings.look.normalized().saturation)
            .putInt("manual_iso", settings.manualIso)
            .putString("camera_id", settings.cameraId)
            .putFloat("exposure_ev", settings.exposureEv)
            .putBoolean("audio_enabled", settings.audioEnabled)
            .putBoolean("private_save", settings.privateSave)
            .putString("orientation", settings.orientation)
            .putBoolean("mirror_front", settings.mirrorFront)
            .putBoolean(KEY_LENS_FACING_BACK, settings.lensFacingBack)
            .putFloat(KEY_ZOOM_RATIO, settings.zoomRatio)
            .putFloat(KEY_ZOOM_MODE, settings.zoomModeRatio)
            .putInt(KEY_RATIO_MODE, CameraController.RATIO_16_9)
            .putString(KEY_QUALITY, qualityToName(settings.quality))
            .putLong(KEY_MAX_DURATION_MS, settings.maxDurationMs)
            .putInt(KEY_MAX_REPEAT_COUNT, settings.maxRepeatCount)
            .putLong("total_duration_ms", settings.totalDurationMs)
            .putInt(KEY_FPS, settings.fps)
            .putBoolean(KEY_HDR, settings.hdrEnabled)
            .putBoolean(KEY_PRO_MODE, settings.proMode)
            .putInt(KEY_AWB_MODE, settings.awbMode)
            .putInt(KEY_COLOR_TEMP_K, settings.colorTempK)
            .putLong(KEY_EXPOSURE_TIME_NS, settings.exposureTimeNs ?: NO_EXPOSURE_TIME)
            .putFloat(KEY_APERTURE, settings.aperture ?: NO_APERTURE)
            .apply()
    }

    fun loadForFacing(context: Context, back: Boolean, quick: Boolean = false): CameraSettings {
        val pref = context.getSharedPreferences(if (quick) "quick_recording_pref" else PREF_NAME, Context.MODE_PRIVATE)
        val prefix = if (back) "back" else "front"
        pref.getString(prefix + "_profile", null)?.let { encoded ->
            runCatching {
                val json = org.json.JSONObject(encoded)
                val bundle = android.os.Bundle()
                val defaults = toBundle(CameraSettings())
                json.keys().forEach { key ->
                    when (defaults.get(key)) {
                        is Boolean -> bundle.putBoolean(key, json.getBoolean(key))
                        is Int -> bundle.putInt(key, json.getInt(key))
                        is Long -> bundle.putLong(key, json.getLong(key))
                        is Float -> bundle.putFloat(key, json.getDouble(key).toFloat())
                        else -> if (!json.isNull(key)) bundle.putString(key, json.getString(key))
                    }
                }
                return fromBundle(bundle).copy(lensFacingBack = back)
            }
        }
        return load(context, quick).copy(lensFacingBack = back,
            cameraId = pref.getString("${prefix}_camera_id", null),
            zoomRatio = pref.getFloat("${prefix}_zoom", 1f),
            exposureEv = pref.getFloat("${prefix}_ev", 0f))
    }

    fun toBundle(s: CameraSettings) = androidx.core.os.bundleOf(
        "look_preset" to s.look.preset, "look_strength" to s.look.strength, "look_temperature" to s.look.temperature,
        "look_tint" to s.look.tint, "look_contrast" to s.look.contrast, "look_saturation" to s.look.saturation,
        "iso" to s.manualIso, "back" to s.lensFacingBack, "camera" to s.cameraId, "zoom" to s.zoomRatio, "zoomMode" to s.zoomModeRatio,
        "ratio" to s.ratioMode, "ev" to s.exposureEv, "audio" to s.audioEnabled, "private" to s.privateSave,
        "orientation" to s.orientation, "mirror" to s.mirrorFront, "quality" to qualityToName(s.quality),
        "segment" to s.maxDurationMs, "count" to s.maxRepeatCount, "total" to s.totalDurationMs,
        "fps" to s.fps, "hdr" to s.hdrEnabled, "pro" to s.proMode, "awb" to s.awbMode,
        "kelvin" to s.colorTempK, "exposure" to (s.exposureTimeNs ?: NO_EXPOSURE_TIME), "aperture" to (s.aperture ?: NO_APERTURE))

    fun fromBundle(b: android.os.Bundle) = CameraSettings(
        look = VideoLook(b.getString("look_preset") ?: "NATURAL", b.getFloat("look_strength", .65f), b.getFloat("look_temperature"),
            b.getFloat("look_tint"), b.getFloat("look_contrast"), b.getFloat("look_saturation")).normalized(),
        manualIso = b.getInt("iso", 400), lensFacingBack = b.getBoolean("back"), cameraId = b.getString("camera"), zoomRatio = b.getFloat("zoom"),
        zoomModeRatio = b.getFloat("zoomMode"), ratioMode = CameraController.RATIO_16_9, exposureEv = b.getFloat("ev"),
        audioEnabled = b.getBoolean("audio"), privateSave = b.getBoolean("private"), orientation = b.getString("orientation") ?: "AUTO",
        mirrorFront = b.getBoolean("mirror"), quality = qualityFromName(b.getString("quality")),
        maxDurationMs = b.getLong("segment"), maxRepeatCount = b.getInt("count"), totalDurationMs = b.getLong("total"),
        fps = b.getInt("fps"), hdrEnabled = b.getBoolean("hdr"), proMode = b.getBoolean("pro"), awbMode = b.getInt("awb"),
        colorTempK = b.getInt("kelvin"), exposureTimeNs = b.getLong("exposure").takeIf { it != NO_EXPOSURE_TIME },
        aperture = b.getFloat("aperture").takeIf { it != NO_APERTURE })

    fun snapshot(context: Context, controller: CameraController, colorTempK: Int): CameraSettings {
        val existing = load(context)
        return CameraSettings(
            look = controller.videoLook,
            manualIso = controller.manualIso,
            cameraId = controller.selectedCameraId,
            exposureEv = controller.exposureEv,
            audioEnabled = controller.audioEnabled,
            privateSave = controller.isPrivateSave,
            orientation = controller.orientationMode,
            mirrorFront = controller.mirrorFront,
            lensFacingBack = controller.isFacingBack(),
            zoomRatio = controller.zoomRatio,
            zoomModeRatio = controller.selectedZoomModeRatio,
            ratioMode = controller.currentRatioMode,
            quality = controller.videoQuality,
            maxDurationMs = controller.maxDurationMs,
            maxRepeatCount = controller.maxRepeatCount,
            totalDurationMs = controller.totalDurationMs,
            fps = controller.targetFps,
            hdrEnabled = controller.isHdrEnabled,
            proMode = controller.isProMode,
            awbMode = controller.getAwbMode(),
            colorTempK = colorTempK.coerceIn(3000, 8000),
            exposureTimeNs = controller.getExposureTime(),
            aperture = controller.getAperture()
        ).copy(colorTempK = colorTempK.takeIf { it in 3000..8000 } ?: existing.colorTempK)
    }

    fun applyToController(controller: CameraController, settings: CameraSettings) = controller.configure {
        controller.videoLook = settings.look.normalized()
        controller.manualIso = settings.manualIso
        controller.audioEnabled = settings.audioEnabled
        controller.isPrivateSave = settings.privateSave
        controller.orientationMode = settings.orientation
        controller.mirrorFront = settings.mirrorFront
        controller.exposureEv = settings.exposureEv
        controller.videoQuality = settings.quality
        controller.maxDurationMs = settings.maxDurationMs
        controller.maxRepeatCount = settings.maxRepeatCount
        controller.totalDurationMs = settings.totalDurationMs
        controller.targetFps = settings.fps
        controller.isHdrEnabled = settings.hdrEnabled
        controller.isProMode = settings.proMode
        controller.setAwbMode(if (settings.proMode) settings.awbMode else CameraMetadata.CONTROL_AWB_MODE_AUTO)
        controller.setManualWbGains(
            if (settings.proMode && settings.awbMode == CameraMetadata.CONTROL_AWB_MODE_OFF) {
                kelvinToGains(settings.colorTempK)
            } else {
                null
            }
        )
        controller.setExposureTime(settings.exposureTimeNs.takeIf { settings.proMode })
        controller.setAperture(settings.aperture.takeIf { settings.proMode })

        controller.restoreCameraSelection(settings.lensFacingBack, settings.cameraId, settings.zoomRatio)
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
