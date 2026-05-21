package com.example.camera2study

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.camera2study.databinding.ActivityMainBinding
import com.example.camera2study.ui.CameraFragment
import com.example.camera2study.util.PermissionHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = PermissionHelper.REQUIRED_PERMISSIONS.all {
            result[it] == true ||
                checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            showCameraFragment()
        } else {
            Toast.makeText(
                this,
                "카메라/오디오 권한이 필요합니다",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (PermissionHelper.allGranted(this)) {
            if (savedInstanceState == null) showCameraFragment()
        } else {
            permissionLauncher.launch(PermissionHelper.REQUIRED_PERMISSIONS)
        }
    }

    private fun showCameraFragment() {
        if (supportFragmentManager.findFragmentById(R.id.container) != null) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, CameraFragment())
            .commit()
    }
}
