package com.example.camera2study

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import com.example.camera2study.databinding.ActivityMainBinding
import com.example.camera2study.ui.CalculatorFragment
import com.example.camera2study.ui.HomeFragment

interface EntryLockListener { fun onEntryLocked() }

class EntrySession : ViewModel() {
    var authenticated = false
    var resetEntry = true
}

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val entry: EntrySession by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.visibility = View.INVISIBLE
    }

    override fun onResumeFragments() {
        super.onResumeFragments()
        if (entry.resetEntry) {
            supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            supportFragmentManager.beginTransaction().replace(R.id.container, CalculatorFragment()).commitNow()
            entry.resetEntry = false
        }
        binding.root.visibility = View.VISIBLE
    }

    fun unlockEntry() {
        entry.authenticated = true
        entry.resetEntry = false
        supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction().replace(R.id.container, HomeFragment()).commit()
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            lockEntry()
        }
        super.onStop()
    }

    override fun onUserLeaveHint() {
        // Lock before the Home/Recents animation, even if the user returns before onStop.
        lockEntry()
        super.onUserLeaveHint()
    }

    private fun lockEntry() {
        (supportFragmentManager.findFragmentById(R.id.container) as? EntryLockListener)?.onEntryLocked()
        entry.authenticated = false
        entry.resetEntry = true
        binding.root.visibility = View.INVISIBLE
    }
}
