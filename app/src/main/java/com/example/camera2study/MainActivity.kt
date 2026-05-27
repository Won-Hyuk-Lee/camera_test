package com.example.camera2study

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.camera2study.databinding.ActivityMainBinding
import com.example.camera2study.ui.CalculatorFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, CalculatorFragment())
                .commit()
        }
    }
}
