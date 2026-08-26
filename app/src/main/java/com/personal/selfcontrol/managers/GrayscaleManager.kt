package com.personal.selfcontrol.managers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

class GrayscaleManager(private val context: Context) {

    private val resolver = context.contentResolver

    fun enable() {
        try {
            Settings.Secure.putInt(resolver, "accessibility_display_daltonizer_enabled", 1)
            Settings.Secure.putInt(resolver, "accessibility_display_daltonizer", 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS no otorgado — escala de grises no disponible")
        }
    }

    fun disable() {
        try {
            Settings.Secure.putInt(resolver, "accessibility_display_daltonizer_enabled", 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS no otorgado — escala de grises no disponible")
        }
    }

    fun isPermissionGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "GrayscaleManager"
    }
}
