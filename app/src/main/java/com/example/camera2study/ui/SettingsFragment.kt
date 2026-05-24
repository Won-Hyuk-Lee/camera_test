package com.example.camera2study.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.camera2study.databinding.FragmentSettingsBinding
import com.example.camera2study.util.PermissionHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkPermissionsState()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnGoBatterySettings.setOnClickListener {
            showBatteryGuideAndGo()
        }

        binding.btnRequestPermissions.setOnClickListener {
            permissionLauncher.launch(PermissionHelper.REQUIRED_PERMISSIONS)
        }

        binding.btnDisableNotification.setOnClickListener {
            try {
                val intent = Intent().apply {
                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent().apply {
                        action = "android.settings.APP_NOTIFICATION_SETTINGS"
                        putExtra("app_package", requireContext().packageName)
                        putExtra("app_uid", requireContext().applicationInfo.uid)
                    }
                    startActivity(intent)
                } catch (ex: Exception) {
                    Toast.makeText(requireContext(), "설정 화면으로 이동할 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkBatteryState()
        checkPermissionsState()
    }

    private fun checkBatteryState() {
        val ctx = requireContext()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)

        if (isIgnoring) {
            binding.txtBatteryState.text = "최적화 안 함 (제한 없음 - 안전)"
            binding.txtBatteryState.setTextColor(Color.parseColor("#4CAF50"))
            binding.btnGoBatterySettings.isEnabled = false
            binding.btnGoBatterySettings.text = "🔋 배터리 최적화 정상 해제됨"
        } else {
            binding.txtBatteryState.text = "최적화 중 (제한됨 - 불안정)"
            binding.txtBatteryState.setTextColor(Color.parseColor("#FF5252"))
            binding.btnGoBatterySettings.isEnabled = true
            binding.btnGoBatterySettings.text = "🔋 배터리 설정 화면으로 이동"
        }
    }

    private fun checkPermissionsState() {
        val ctx = requireContext()

        // 1. 카메라
        val cameraGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        updatePermissionText(binding.txtPermCamera, cameraGranted)

        // 2. 마이크
        val audioGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        updatePermissionText(binding.txtPermAudio, audioGranted)

        // 3. 알림 (Android 13 이상만 실질적 체크)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notiGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            updatePermissionText(binding.txtPermNotification, notiGranted)
        } else {
            binding.txtPermNotification.text = "허용 완료 (불필요)"
            binding.txtPermNotification.setTextColor(Color.parseColor("#4CAF50"))
        }

        // 전체 획득 상태이면 버튼 비활성화
        if (PermissionHelper.allGranted(ctx)) {
            binding.btnRequestPermissions.isEnabled = false
            binding.btnRequestPermissions.text = "🔑 모든 필수 권한 획득 완료"
        } else {
            binding.btnRequestPermissions.isEnabled = true
            binding.btnRequestPermissions.text = "🔑 누락된 필수 권한 승인하기"
        }
    }

    private fun updatePermissionText(view: android.widget.TextView, granted: Boolean) {
        if (granted) {
            view.text = "허용 완료"
            view.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            view.text = "미허용"
            view.setTextColor(Color.parseColor("#FF5252"))
        }
    }

    private fun showBatteryGuideAndGo() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("🔋 배터리 최적화 예외 가이드")
            .setMessage("백그라운드에서 화면이 꺼졌을 때 중단 없이 무중단 녹화를 유지하려면 배터리 최적화 해제(제한 없음)가 필수적입니다.\n\n[이동 후 가이드]\n1. 목록 상단의 필터를 '최적화하지 않은 앱'에서 '전체'로 변경합니다.\n2. 'test' 앱을 검색해 찾습니다.\n3. 해당 앱을 선택하고 '최적화하지 않음' 또는 '제한 없음'으로 설정해 주세요.")
            .setPositiveButton("이동하기") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "설정 화면으로 이동할 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
