package com.example.camera2study.util

import android.content.Context
import java.security.MessageDigest

object SecurityPrefs {
    private const val PREF_NAME = "entry_security_pref"
    private const val KEY_PIN_ENABLED = "pin_enabled"
    private const val KEY_PIN_HASH = "pin_hash"

    fun isPinEnabled(context: Context): Boolean {
        val pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return pref.getBoolean(KEY_PIN_ENABLED, false) && pref.getString(KEY_PIN_HASH, null) != null
    }

    fun savePin(context: Context, pin: String) {
        require(isValidPin(pin)) { "PIN must be exactly 4 digits." }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PIN_HASH, hashPin(pin))
            .putBoolean(KEY_PIN_ENABLED, true)
            .apply()
    }

    fun setPinEnabled(context: Context, enabled: Boolean) {
        val pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val hasPin = pref.getString(KEY_PIN_HASH, null) != null
        pref.edit()
            .putBoolean(KEY_PIN_ENABLED, enabled && hasPin)
            .apply()
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        val expected = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PIN_HASH, null)
            ?: return false
        return expected == hashPin(pin)
    }

    fun isValidPin(pin: String): Boolean =
        pin.length == 4 && pin.all { it.isDigit() }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
