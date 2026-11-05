package com.example.camera2study.util

import java.util.Locale
import kotlin.math.abs

/** The keypad uses '.', so results must remain parseable with that same notation. */
object CalculatorNumber {
    fun format(value: Double): String {
        if (!value.isFinite()) return "Error"
        val rounded = if (abs(value) < 1e12 && abs(value % 1.0) < 1e-9) {
            value.toLong().toString()
        } else {
            String.format(Locale.ROOT, "%.8f", value).trimEnd('0').trimEnd('.')
        }
        return if (rounded.length > 12) String.format(Locale.ROOT, "%.6e", value) else rounded
    }
}
