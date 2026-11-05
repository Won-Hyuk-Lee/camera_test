package com.example.camera2study

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import com.example.camera2study.databinding.ActivityMainBinding
import com.example.camera2study.ui.CalculatorFragment
import com.example.camera2study.ui.HomeFragment

interface EntryLockListener {
    fun onEntryLocked()
    fun onEntryUnlocked() {}
}
class EntrySession : ViewModel() {
    var authenticated = false
    var resetEntry = true
}

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val entry: EntrySession by viewModels()
    private val lockedBack = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() { moveTaskToBack(true) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        refreshScreenProtection()
        onBackPressedDispatcher.addCallback(this, lockedBack)
        showProtection(!entry.authenticated)
    }
    fun refreshScreenProtection() {
        if (com.example.camera2study.util.SecurityPrefs.blockScreenCapture(this)) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }
    override fun onResumeFragments() {
        super.onResumeFragments()
        if (entry.resetEntry) {
            supportFragmentManager.beginTransaction().replace(R.id.entryGate, CalculatorFragment()).commitNow()
            entry.resetEntry = false
        }
        showProtection(!entry.authenticated)
    }
    private fun showProtection(locked: Boolean) {
        binding.container.visibility = if (locked) View.GONE else View.VISIBLE
        binding.container.importantForAccessibility = if (locked) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        binding.entryGate.visibility = if (locked) View.VISIBLE else View.GONE
        if (locked) {
            // Re-prioritize after Fragment lifecycle callbacks resume as well.
            lockedBack.remove()
            onBackPressedDispatcher.addCallback(this, lockedBack)
        }
        lockedBack.isEnabled = locked
    }
    fun isEntryAuthenticated(): Boolean = entry.authenticated
    fun unlockEntry() {
        entry.authenticated = true
        entry.resetEntry = false
        supportFragmentManager.findFragmentById(R.id.entryGate)?.let {
            supportFragmentManager.beginTransaction().remove(it).commitNow()
        }
        if (supportFragmentManager.findFragmentById(R.id.container) == null) {
            supportFragmentManager.beginTransaction().replace(R.id.container, HomeFragment()).commitNow()
        }
        showProtection(false)
        (supportFragmentManager.findFragmentById(R.id.container) as? EntryLockListener)?.onEntryUnlocked()
    }
    override fun onStop() {
        if (!isChangingConfigurations) lockEntry()
        super.onStop()
    }
    override fun onUserLeaveHint() { lockEntry(); super.onUserLeaveHint() }
    private fun lockEntry() {
        entry.authenticated = false
        entry.resetEntry = true
        showProtection(true)
        (supportFragmentManager.findFragmentById(R.id.container) as? EntryLockListener)?.onEntryLocked()
    }
}
