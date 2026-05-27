package com.example.camera2study.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.camera2study.R
import com.example.camera2study.databinding.FragmentPinBinding
import com.example.camera2study.util.SecurityPrefs

class PinFragment : Fragment() {

    private var _binding: FragmentPinBinding? = null
    private val binding get() = _binding!!
    private val pinBuilder = StringBuilder(4)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPinBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupKeypad()
        updatePinDisplay()
    }

    private fun setupKeypad() {
        mapOf(
            binding.btnPin0 to "0",
            binding.btnPin1 to "1",
            binding.btnPin2 to "2",
            binding.btnPin3 to "3",
            binding.btnPin4 to "4",
            binding.btnPin5 to "5",
            binding.btnPin6 to "6",
            binding.btnPin7 to "7",
            binding.btnPin8 to "8",
            binding.btnPin9 to "9"
        ).forEach { (button, digit) ->
            button.setOnClickListener { appendDigit(digit) }
        }

        binding.btnPinClear.setOnClickListener {
            pinBuilder.clear()
            updatePinDisplay()
        }
        binding.btnPinBack.setOnClickListener {
            if (pinBuilder.isNotEmpty()) {
                pinBuilder.deleteAt(pinBuilder.lastIndex)
                updatePinDisplay()
            }
        }
    }

    private fun appendDigit(digit: String) {
        if (pinBuilder.length >= 4) return
        pinBuilder.append(digit)
        updatePinDisplay()
        if (pinBuilder.length == 4) {
            checkPin()
        }
    }

    private fun updatePinDisplay() {
        val text = buildString {
            repeat(pinBuilder.length) { append("*") }
            repeat(4 - pinBuilder.length) { append("  _") }
        }.trim()
        binding.txtPinDots.text = text
    }

    private fun checkPin() {
        if (SecurityPrefs.verifyPin(requireContext(), pinBuilder.toString())) {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, HomeFragment())
                .commit()
        } else {
            pinBuilder.clear()
            updatePinDisplay()
            openGallery()
        }
    }

    private fun openGallery() {
        val context = requireContext()
        val packageManager = context.packageManager
        val galleryIntent = Intent.makeMainSelectorActivity(
            Intent.ACTION_MAIN,
            Intent.CATEGORY_APP_GALLERY
        )

        try {
            startActivity(galleryIntent)
            return
        } catch (_: ActivityNotFoundException) {
            val samsungGallery = packageManager.getLaunchIntentForPackage("com.sec.android.gallery3d")
            if (samsungGallery != null) {
                startActivity(samsungGallery)
                return
            }
        }

        Toast.makeText(context, "갤러리 앱을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
