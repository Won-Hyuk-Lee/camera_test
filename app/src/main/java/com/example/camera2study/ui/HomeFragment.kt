package com.example.camera2study.ui

import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.camera2study.R
import com.example.camera2study.databinding.FragmentHomeBinding
import com.example.camera2study.util.PermissionHelper

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.cardStartCamera.setOnClickListener {
            if (PermissionHelper.allGranted(requireContext())) {
                navigateTo(CameraFragment())
            } else {
                Toast.makeText(
                    requireContext(),
                    "필수 권한이 누락되었습니다. 권한 및 설정 센터에서 허용해주세요.",
                    Toast.LENGTH_LONG
                ).show()
                navigateTo(SettingsFragment())
            }
        }

        binding.cardFastRecord.setOnClickListener {
            if (PermissionHelper.allGranted(requireContext())) {
                val fragment = CameraFragment().apply {
                    arguments = Bundle().apply {
                        putBoolean("EXTRA_AUTO_START", true)
                    }
                }
                navigateTo(fragment)
            } else {
                Toast.makeText(
                    requireContext(),
                    "필수 권한이 누락되었습니다. 권한 및 설정 센터에서 허용해주세요.",
                    Toast.LENGTH_LONG
                ).show()
                navigateTo(SettingsFragment())
            }
        }

        binding.cardPrivateVault.setOnClickListener {
            navigateTo(PrivateVaultFragment())
        }

        binding.cardSettings.setOnClickListener {
            navigateTo(SettingsFragment())
        }

        binding.btnFixBattery.setOnClickListener {
            navigateTo(SettingsFragment())
        }

        binding.btnFixPermissions.setOnClickListener {
            navigateTo(SettingsFragment())
        }
    }

    override fun onResume() {
        super.onResume()
        checkDeviceState()
    }

    private fun checkDeviceState() {
        val ctx = requireContext()
        
        // 1. 배터리 최적화 확인
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoringBattery = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        binding.cardBatteryWarning.visibility = if (isIgnoringBattery) View.GONE else View.VISIBLE

        // 2. 권한 획득 확인
        val hasPermissions = PermissionHelper.allGranted(ctx)
        binding.cardPermissionWarning.visibility = if (hasPermissions) View.GONE else View.VISIBLE
    }

    private fun navigateTo(fragment: Fragment) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
