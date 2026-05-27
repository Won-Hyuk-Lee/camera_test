package com.example.camera2study.util

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

data class LensInfo(
    val cameraId: String,
    val focalLength: Float,
    val isWide: Boolean,
    val minZoomRatio: Float,
    val maxZoomRatio: Float
)

object CameraUtils {

    private fun manager(context: Context): CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun listBackLenses(context: Context): List<LensInfo> {
        val cm = manager(context)
        val raw = cm.cameraIdList.mapNotNull { id ->
            val chars = cm.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
                ?: return@mapNotNull null
            val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            } else {
                null
            }
            LensInfo(
                cameraId = id,
                focalLength = focal,
                isWide = false,
                minZoomRatio = zoomRange?.lower ?: 1.0f,
                maxZoomRatio = zoomRange?.upper ?: 1.0f
            )
        }.sortedBy { it.focalLength }

        if (raw.isEmpty()) return emptyList()
        val minFocal = raw.first().focalLength
        return raw.map { lens ->
            lens.copy(isWide = lens.focalLength <= minFocal + 0.01f)
        }
    }

    fun hasVariableAperture(context: Context, cameraId: String): Boolean {
        val apertures = getApertures(context, cameraId) ?: return false
        return apertures.size > 1
    }

    fun getApertures(context: Context, cameraId: String): FloatArray? =
        manager(context).getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)

    fun getExposureTimeRangeNs(context: Context, cameraId: String): LongRange? {
        val range = manager(context).getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return null
        return range.lower..range.upper
    }
}
