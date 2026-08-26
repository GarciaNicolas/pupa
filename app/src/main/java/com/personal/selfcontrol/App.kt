package com.personal.selfcontrol

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.personal.selfcontrol.data.PrefsManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Pupa Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitoreo de apps activo"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        clearBlocksIfNewInstall()
    }

    private fun clearBlocksIfNewInstall() {
        val prefs = PrefsManager(this)
        val installTime = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        if (installTime != prefs.getLastInstallTime()) {
            prefs.clearAllBlocks()
            prefs.saveLastInstallTime(installTime)
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "selfcontrol_channel"
    }
}
