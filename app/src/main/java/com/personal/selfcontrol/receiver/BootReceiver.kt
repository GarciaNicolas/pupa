package com.personal.selfcontrol.receiver

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.personal.selfcontrol.App
import com.personal.selfcontrol.R
import com.personal.selfcontrol.data.PrefsManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Al reinstalar desde Android Studio se limpian los bloqueos del día
                PrefsManager(context).clearAllBlocks()
                if (!isAccessibilityServiceEnabled(context)) postReminderNotification(context)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                if (!isAccessibilityServiceEnabled(context)) postReminderNotification(context)
            }
            else -> return
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }

    private fun postReminderNotification(context: Context) {
        val notification = NotificationCompat.Builder(context, App.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Pupa desactivado")
            .setContentText("Reactivá el servicio de accesibilidad en Ajustes")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(1002, notification)
    }
}
