package com.example.camera2study.util

import kotlin.math.pow

data class VideoLook(
    val preset: String = "NATURAL", val strength: Float = 0.65f,
    val temperature: Float = 0f, val tint: Float = 0f,
    val contrast: Float = 0f, val saturation: Float = 0f
) {
    fun normalized() = copy(preset = preset.takeIf { id -> presets.any { it.id == id } } ?: "NATURAL",
        strength = strength.safe(0f, 1f), temperature = temperature.safe(-1f, 1f),
        tint = tint.safe(-1f, 1f), contrast = contrast.safe(-1f, 1f), saturation = saturation.safe(-1f, 1f))
    fun parameters(): Parameters {
        val look = normalized()
        val base = presets.first { it.id == look.preset }
        val amount = look.strength
        val warmth = (base.warmth + look.temperature * 0.22f).coerceIn(-0.35f, 0.3f) * amount
        val tintShift = (base.tint + look.tint * 0.08f).coerceIn(-0.12f, 0.12f) * amount
        return Parameters(1f + warmth, 1f - tintShift, 1f - warmth,
            1f + ((base.contrast - 1f) + look.contrast * 0.25f) * amount,
            1f + ((base.saturation - 1f) + look.saturation * 0.35f) * amount,
            1f + (base.gamma - 1f) * amount, base.shadow * amount)
    }
    fun isIdentity(): Boolean = parameters() == Parameters()
    data class Parameters(val red: Float = 1f, val green: Float = 1f, val blue: Float = 1f,
        val contrast: Float = 1f, val saturation: Float = 1f, val gamma: Float = 1f, val shadow: Float = 0f) {
        fun apply(rgb: FloatArray): FloatArray {
            val gains = floatArrayOf(red, green, blue)
            val lit = FloatArray(3) { (rgb[it].coerceIn(0f, 1f).pow(gamma) + shadow * (1f - rgb[it])) * gains[it] }
            val y = lit[0] * 0.2126f + lit[1] * 0.7152f + lit[2] * 0.0722f
            return FloatArray(3) { ((y + (lit[it] - y) * saturation - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f) }
        }
    }
    data class Preset(val id: String, val name: String, val description: String,
        val warmth: Float = 0f, val tint: Float = 0f, val contrast: Float = 1f,
        val saturation: Float = 1f, val gamma: Float = 1f, val shadow: Float = 0f)
    companion object {
        val presets = listOf(
            Preset("NATURAL", "자연스러운 기본", "원래 색과 밝기를 유지합니다"),
            Preset("DARK", "어두운 실내", "어두운 부분을 부드럽게 밝힙니다", contrast = 0.95f, saturation = 0.97f, gamma = 0.88f, shadow = 0.025f),
            Preset("WARM_NEUTRAL", "따뜻한 조명 중화", "주황빛을 완화하고 중립적인 색을 만듭니다", warmth = -0.12f, tint = 0.01f, saturation = 0.96f),
            Preset("KEEP_WARM", "따뜻한 분위기 유지", "조명 분위기를 남기면서 강한 색을 완화합니다", warmth = -0.025f, contrast = 0.98f, saturation = 0.96f, gamma = 0.98f),
            Preset("DARK_ORANGE", "매우 어두운 주황 조명", "주황빛과 강한 대비를 줄이고 어두운 부분을 밝힙니다", warmth = -0.22f, tint = 0.025f, contrast = 0.92f, saturation = 0.92f, gamma = 0.86f, shadow = 0.035f)
        )
        private fun Float.safe(min: Float, max: Float): Float = if (isFinite()) coerceIn(min, max) else 0f
    }
}
