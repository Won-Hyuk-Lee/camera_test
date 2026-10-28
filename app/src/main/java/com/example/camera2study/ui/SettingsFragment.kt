package com.example.camera2study.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import com.example.camera2study.util.SecurityPrefs
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
            requestMissingPermissions()
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

        val normalSettings = android.widget.Button(requireContext()).apply {
            text = "일반 촬영 설정 · 마이크 없이 촬영 설정 가능"
            setOnClickListener { parentFragmentManager.beginTransaction()
                .replace(com.example.camera2study.R.id.container, RecordingSettingsFragment.create(false))
                .addToBackStack(null).commit() }
        }
        val settingsParent = binding.btnQuickRecordingSettings.parent as android.view.ViewGroup
        settingsParent.addView(normalSettings, settingsParent.indexOfChild(binding.btnQuickRecordingSettings))
        settingsParent.addView(android.widget.TextView(requireContext()).apply {
            text = "보관함은 갤러리 비노출 저장이며 영상 자체를 암호화하지 않습니다. 앱 삭제·데이터 삭제 시 영상도 삭제됩니다. 중요한 영상은 먼저 내보내세요. 영상·PIN·설정·진단은 자동 백업 및 기기 이전에서 제외됩니다."
            setTextColor(Color.LTGRAY); textSize = 14f; setPadding(12, 16, 12, 16)
        })
        settingsParent.addView(android.widget.CheckBox(requireContext()).apply {
            text = "앱 화면 캡처 차단 (선택)"; setTextColor(Color.WHITE)
            isChecked = SecurityPrefs.blockScreenCapture(requireContext())
            setOnCheckedChangeListener { _, value ->
                SecurityPrefs.setBlockScreenCapture(requireContext(), value)
                (requireActivity() as com.example.camera2study.MainActivity).refreshScreenProtection()
            }
        })
        binding.btnQuickRecordingSettings.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(com.example.camera2study.R.id.container, RecordingSettingsFragment.create(true))
                .addToBackStack(null).commit()
        }
        binding.btnRecordingHistory.setOnClickListener {
            parentFragmentManager.beginTransaction().replace(com.example.camera2study.R.id.container, RecordingHistoryFragment())
                .addToBackStack(null).commit()
        }
        setupPinControls()
    }

    override fun onResume() {
        super.onResume()
        checkBatteryState()
        checkPermissionsState()
        syncPinControls()
    }

    private fun setupPinControls() {
        syncPinControls()
        binding.btnSavePin.setOnClickListener {
            val pin = binding.inputPinCode.text?.toString().orEmpty()
            val enabled = binding.switchPinLock.isChecked

            if (enabled) {
                if (!SecurityPrefs.isValidPin(pin)) {
                    Toast.makeText(requireContext(), "PIN은 숫자 4자리로 입력하세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                SecurityPrefs.savePin(requireContext(), pin)
                binding.inputPinCode.text?.clear()
                Toast.makeText(requireContext(), "PIN 잠금이 활성화되었습니다.", Toast.LENGTH_SHORT).show()
            } else {
                SecurityPrefs.setPinEnabled(requireContext(), false)
                binding.inputPinCode.text?.clear()
                Toast.makeText(requireContext(), "PIN 잠금이 꺼졌습니다.", Toast.LENGTH_SHORT).show()
            }

            syncPinControls()
        }
    }

    private fun syncPinControls() {
        if (_binding == null) return
        val enabled = SecurityPrefs.isPinEnabled(requireContext())
        binding.switchPinLock.isChecked = enabled
        binding.txtPinState.text = if (enabled) "활성화" else "꺼짐"
        binding.txtPinState.setTextColor(
            Color.parseColor(if (enabled) "#4CAF50" else "#8AFFFFFF")
        )
    }

    private fun checkBatteryState() {
        val ctx = requireContext()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)

        if (isIgnoring) {
            binding.txtBatteryState.text = "배터리 최적화 예외 적용"
            binding.txtBatteryState.setTextColor(Color.parseColor("#4CAF50"))
            binding.btnGoBatterySettings.isEnabled = false
            binding.btnGoBatterySettings.text = "🔋 배터리 최적화 정상 해제됨"
        } else {
            binding.txtBatteryState.text = "배터리 최적화 적용 중"
            binding.txtBatteryState.setTextColor(Color.parseColor("#FF5252"))
            binding.btnGoBatterySettings.isEnabled = true
            binding.btnGoBatterySettings.text = "🔋 배터리 설정 화면으로 이동"
        }
    }

    private fun requestMissingPermissions() {
        val ctx = requireContext()
        val missing = PermissionHelper.REQUIRED_PERMISSIONS.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        val prefs = ctx.getSharedPreferences("permission_requests", Context.MODE_PRIVATE)
        val fixed = missing.any { prefs.getBoolean(it, false) && !shouldShowRequestPermissionRationale(it) }
        if (fixed) {
            MaterialAlertDialogBuilder(ctx).setTitle("앱 권한에서 허용해 주세요")
                .setMessage("반복 거절한 권한은 시스템 설정에서 변경해야 합니다. 권한 → 카메라 또는 마이크에서 허용을 선택하세요. 마이크 없이 촬영하려면 일반/빠른 촬영 설정에서 마이크 녹음을 끌 수 있습니다.")
                .setPositiveButton("앱 권한 설정") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName)))
                }.setNegativeButton("취소", null).show()
        } else if (missing.isNotEmpty()) {
            prefs.edit().apply { missing.forEach { putBoolean(it, true) } }.apply()
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun checkPermissionsState() {
        if (_binding == null) return
        val ctx = requireContext()

        // 1. 카메라
        val cameraGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        updatePermissionText(binding.txtPermCamera, cameraGranted)

        // 2. 마이크
        val audioGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        updatePermissionText(binding.txtPermAudio, audioGranted)

        binding.txtPermNotification.text = "선택 사항"
        binding.txtPermNotification.setTextColor(Color.parseColor("#FFC107"))

        // 전체 획득 상태이면 버튼 비활성화
        if (PermissionHelper.REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) {
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
            .setMessage("배터리 최적화 예외는 백그라운드 녹화에 도움이 됩니다. 과열·권한·저장공간 등의 중단까지 방지하지는 않습니다.\n\n[이동 후 가이드]\n1. 목록 상단의 필터를 '최적화하지 않은 앱'에서 '전체'로 변경합니다.\n2. '계산기' 앱을 검색해 찾습니다.\n3. 해당 앱을 선택하고 '최적화하지 않음' 또는 '제한 없음'으로 설정해 주세요.")
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
