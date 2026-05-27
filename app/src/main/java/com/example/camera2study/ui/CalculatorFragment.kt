package com.example.camera2study.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.camera2study.R
import com.example.camera2study.databinding.FragmentCalculatorBinding
import com.example.camera2study.util.SecurityPrefs
import com.google.android.material.button.MaterialButton
import kotlin.math.abs

class CalculatorFragment : Fragment() {

    private var _binding: FragmentCalculatorBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler(Looper.getMainLooper())
    private var pendingOperator: String? = null
    private var storedValue: Double? = null
    private var resetInput = false
    private var unlockTriggered = false

    private val unlockRunnable = Runnable {
        unlockTriggered = true
        openProtectedEntry()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCalculatorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
    }

    private fun setupButtons() {
        mapOf(
            binding.btnCalc0 to "0",
            binding.btnCalc1 to "1",
            binding.btnCalc2 to "2",
            binding.btnCalc3 to "3",
            binding.btnCalc4 to "4",
            binding.btnCalc5 to "5",
            binding.btnCalc6 to "6",
            binding.btnCalc7 to "7",
            binding.btnCalc8 to "8",
            binding.btnCalc9 to "9"
        ).forEach { (button, digit) ->
            button.setOnClickListener { appendDigit(digit) }
        }

        binding.btnCalcDecimal.setOnClickListener { appendDecimal() }
        binding.btnCalcClear.setOnClickListener { clearCalculator() }
        binding.btnCalcBack.setOnClickListener { deleteLastDigit() }
        binding.btnCalcAdd.setOnClickListener { chooseOperator("+") }
        binding.btnCalcSubtract.setOnClickListener { chooseOperator("-") }
        binding.btnCalcMultiply.setOnClickListener { chooseOperator("*") }
        binding.btnCalcDivide.setOnClickListener { chooseOperator("/") }
        setupEqualsButton(binding.btnCalcEquals)
    }

    private fun setupEqualsButton(button: MaterialButton) {
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    unlockTriggered = false
                    handler.postDelayed(unlockRunnable, 2000L)
                    view.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(unlockRunnable)
                    view.isPressed = false
                    if (!unlockTriggered) {
                        view.performClick()
                        calculateResult()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(unlockRunnable)
                    view.isPressed = false
                    true
                }
                else -> true
            }
        }
    }

    private fun appendDigit(digit: String) {
        val current = binding.txtCalcDisplay.text.toString()
        binding.txtCalcDisplay.text = when {
            resetInput -> digit
            current == "0" -> digit
            current == "Error" -> digit
            current.length >= 12 -> current
            else -> current + digit
        }
        resetInput = false
    }

    private fun appendDecimal() {
        val current = binding.txtCalcDisplay.text.toString()
        if (resetInput || current == "Error") {
            binding.txtCalcDisplay.text = "0."
            resetInput = false
            return
        }
        if (!current.contains(".")) {
            binding.txtCalcDisplay.text = "$current."
        }
    }

    private fun chooseOperator(operator: String) {
        applyPendingOperation()
        storedValue = readDisplayValue()
        pendingOperator = operator
        resetInput = true
    }

    private fun calculateResult() {
        if (pendingOperator == null || storedValue == null) return
        applyPendingOperation()
        pendingOperator = null
        storedValue = null
        resetInput = true
    }

    private fun applyPendingOperation() {
        val left = storedValue ?: return
        val operator = pendingOperator ?: return
        val right = readDisplayValue()
        val result = when (operator) {
            "+" -> left + right
            "-" -> left - right
            "*" -> left * right
            "/" -> if (right == 0.0) null else left / right
            else -> right
        }

        if (result == null) {
            binding.txtCalcDisplay.text = "Error"
            resetInput = true
        } else {
            binding.txtCalcDisplay.text = formatResult(result)
        }
    }

    private fun deleteLastDigit() {
        val current = binding.txtCalcDisplay.text.toString()
        binding.txtCalcDisplay.text = when {
            resetInput || current == "Error" || current.length <= 1 -> "0"
            else -> current.dropLast(1)
        }
        resetInput = false
    }

    private fun clearCalculator() {
        binding.txtCalcDisplay.text = "0"
        pendingOperator = null
        storedValue = null
        resetInput = false
    }

    private fun readDisplayValue(): Double =
        binding.txtCalcDisplay.text.toString().toDoubleOrNull() ?: 0.0

    private fun formatResult(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "Error"
        val rounded = if (abs(value % 1.0) < 0.000000001) {
            value.toLong().toString()
        } else {
            "%.8f".format(value).trimEnd('0').trimEnd('.')
        }
        return if (rounded.length > 12) "%.6e".format(value) else rounded
    }

    private fun openProtectedEntry() {
        val fragment = if (SecurityPrefs.isPinEnabled(requireContext())) {
            PinFragment()
        } else {
            HomeFragment()
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commit()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(unlockRunnable)
        _binding = null
        super.onDestroyView()
    }
}
