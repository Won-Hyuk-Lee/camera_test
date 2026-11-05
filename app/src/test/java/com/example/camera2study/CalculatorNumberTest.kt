package com.example.camera2study

import com.example.camera2study.util.CalculatorNumber
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CalculatorNumberTest {
    @Test fun commaLocaleResultRemainsUsableInTheNextCalculation() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val result = CalculatorNumber.format(1.0 / 4.0)
            assertEquals("0.25", result)
            assertEquals(1.25, result.toDouble() + 1.0, 0.0)
            assertEquals(1.0e15, CalculatorNumber.format(1.0e15).toDouble(), 1.0)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun invalidArithmeticDoesNotBecomeAStoredNumber() {
        assertEquals("Error", CalculatorNumber.format(Double.POSITIVE_INFINITY))
        assertEquals("Error", CalculatorNumber.format(Double.NaN))
    }
}
