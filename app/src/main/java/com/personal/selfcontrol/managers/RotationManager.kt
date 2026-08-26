package com.personal.selfcontrol.managers

import android.content.Context
import android.provider.Settings
import android.util.Log

class RotationManager(private val context: Context) {

    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("rotation_state", Context.MODE_PRIVATE)
    private var isForced = false

    fun resetToAutoRotate() {
        if (!Settings.System.canWrite(context)) return
        try {
            if (prefs.getBoolean("forced", false)) {
                Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION,
                    prefs.getInt("saved_accel", 1))
                Settings.System.putInt(resolver, Settings.System.USER_ROTATION,
                    prefs.getInt("saved_rot", 0))
                prefs.edit().putBoolean("forced", false).apply()
                Log.d(TAG, "Rotación restaurada desde estado persistido")
            }
            isForced = false
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo resetear la rotación: ${e.message}")
        }
    }

    fun forceLandscape() {
        if (isForced) return
        if (!Settings.System.canWrite(context)) {
            Log.w(TAG, "WRITE_SETTINGS no otorgado — rotación forzada no disponible")
            return
        }
        val accel = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1)
        val rot = Settings.System.getInt(resolver, Settings.System.USER_ROTATION, 0)
        prefs.edit()
            .putInt("saved_accel", accel)
            .putInt("saved_rot", rot)
            .putBoolean("forced", true)
            .apply()
        Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        Settings.System.putInt(resolver, Settings.System.USER_ROTATION, 1)
        isForced = true
        Log.d(TAG, "Landscape forzado (guardado: accel=$accel, rot=$rot)")
    }

    fun restore() {
        if (!isForced && !prefs.getBoolean("forced", false)) return
        if (!Settings.System.canWrite(context)) return
        try {
            Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION,
                prefs.getInt("saved_accel", 1))
            Settings.System.putInt(resolver, Settings.System.USER_ROTATION,
                prefs.getInt("saved_rot", 0))
            prefs.edit().putBoolean("forced", false).apply()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo restaurar la rotación: ${e.message}")
        }
        isForced = false
        Log.d(TAG, "Rotación restaurada")
    }

    companion object {
        private const val TAG = "RotationManager"
    }
}
