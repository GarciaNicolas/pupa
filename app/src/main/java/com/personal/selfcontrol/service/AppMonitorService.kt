package com.personal.selfcontrol.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import java.util.Calendar
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.personal.selfcontrol.App
import com.personal.selfcontrol.R
import com.personal.selfcontrol.data.PrefsManager
import com.personal.selfcontrol.managers.GrayscaleManager
import com.personal.selfcontrol.managers.RotationManager
import com.personal.selfcontrol.managers.SoundManager

class AppMonitorService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private var currentTimerPkg: String? = null
    private var currentForegroundPkg: String? = null
    private val systemPackageCache = mutableMapOf<String, Boolean>()

    private var youtubePortraitTimerRunnable: Runnable? = null
    private var orientationReceiver: BroadcastReceiver? = null

    private val grayscaleObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (currentForegroundPkg in COLOR_ALLOWED_APPS) return
            val enabled = Settings.Secure.getInt(
                contentResolver, "accessibility_display_daltonizer_enabled", 0
            )
            if (enabled == 0) grayscaleManager.enable()
        }
    }

    private lateinit var prefs: PrefsManager
    private lateinit var soundManager: SoundManager
    private lateinit var grayscaleManager: GrayscaleManager
    private lateinit var rotationManager: RotationManager

    private val launcherPackage: String by lazy {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) },
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName ?: "com.sec.android.app.launcher"
    }

    companion object {
        private const val TAG = "AppMonitorService"
        const val TIMER_LIMIT_MS = 15 * 60 * 1000L
        const val YOUTUBE_PORTRAIT_TIMER_MS = 15 * 60 * 1000L

        val MONITORED_APPS = setOf(
            "com.instagram.android",
            "com.android.chrome",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically.go",
            "com.ss.android.ugc.trill.lite"
        )

        val COLOR_ALLOWED_APPS = setOf(
            "com.sec.android.gallery3d",
            "com.google.android.apps.photos",
            "com.sec.android.app.camera",
            "com.whatsapp",
            "com.google.android.apps.maps",
            "com.waze",
            "com.netflix.mediaclient",
            "com.google.android.youtube",
            "com.crunchyroll.crunchyroid"
        )

        const val YOUTUBE_PKG = "com.google.android.youtube"

        val ALWAYS_IGNORED = setOf(
            "com.android.systemui",
            "android",
            "com.samsung.android.app.cocktailbarservice",
            "com.sec.android.emergencymode.service",
            "com.samsung.android.server.iris",
            "com.samsung.android.biometrics.app.setting"
        )

        val HOME_PACKAGES = setOf(
            "com.sec.android.app.launcher",
            "com.samsung.android.app.launcher",
            "com.android.launcher3",
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.microsoft.launcher"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = PrefsManager(this)
        soundManager = SoundManager(this, prefs)
        grayscaleManager = GrayscaleManager(this)
        rotationManager = RotationManager(this)

        rotationManager.resetToAutoRotate()
        grayscaleManager.enable()

        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor("accessibility_display_daltonizer_enabled"),
            false,
            grayscaleObserver
        )

        postPersistentNotification()
        Log.i(TAG, "Service connected — launcher: $launcherPackage")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (isIgnorablePackage(pkg)) return
        if (pkg == currentForegroundPkg) return
        handleForegroundChange(pkg)
    }

    private fun isIgnorablePackage(pkg: String): Boolean {
        if (pkg in ALWAYS_IGNORED) return true
        if (pkg in MONITORED_APPS) return false
        if (pkg in COLOR_ALLOWED_APPS) return false
        if (pkg == launcherPackage || pkg in HOME_PACKAGES) return false
        return systemPackageCache.getOrPut(pkg) {
            try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            } catch (e: PackageManager.NameNotFoundException) { false }
        }
    }

    private fun handleForegroundChange(newPkg: String) {
        val prevPkg = currentForegroundPkg
        currentForegroundPkg = newPkg

        // Feature 1: timer de 15 min y bloqueo diario
        if (prevPkg != null && prevPkg in MONITORED_APPS && newPkg != prevPkg) stopTimer()
        if (newPkg in MONITORED_APPS) {
            if (prefs.isMorningBlockEnabled() && isMorningHours()) {
                Log.i(TAG, "$newPkg bloqueada por horario matutino (00-09)")
                soundManager.playRandomSound()
                handler.postDelayed({
                    if (currentForegroundPkg == newPkg) performGlobalAction(GLOBAL_ACTION_HOME)
                }, 200)
            } else if (prefs.isAppBlockedToday(newPkg)) {
                Log.i(TAG, "$newPkg bloqueada hoy — castigo")
                soundManager.playRandomSound()
                handler.postDelayed({
                    if (currentForegroundPkg == newPkg) performGlobalAction(GLOBAL_ACTION_HOME)
                }, 200)
            } else {
                startTimer(newPkg)
            }
        }

        // Feature 2: escala de grises
        if (newPkg in COLOR_ALLOWED_APPS) {
            grayscaleManager.disable()
        } else {
            grayscaleManager.enable()
        }

        // Feature 3: YouTube landscape + detección de Shorts
        if (newPkg == YOUTUBE_PKG) {
            startYoutubeOrientationMonitoring()
        } else {
            if (prevPkg == YOUTUBE_PKG) stopYoutubeOrientationMonitoring()
            rotationManager.restore()
        }
    }

    // --- YouTube Shorts monitoring ---

    private fun startYoutubeOrientationMonitoring() {
        stopYoutubeOrientationMonitoring()
        rotationManager.forceLandscape()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (currentForegroundPkg != YOUTUBE_PKG) return
                val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                handleYoutubeOrientation(isPortrait)
            }
        }
        // RECEIVER_EXPORTED requerido en targetSdk 34 para broadcasts del sistema
        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        orientationReceiver = receiver
        Log.d(TAG, "YouTube monitoring iniciado")
    }

    private fun stopYoutubeOrientationMonitoring() {
        orientationReceiver?.let {
            try { unregisterReceiver(it) } catch (_: IllegalArgumentException) {}
        }
        orientationReceiver = null
        pauseYoutubePortraitTimer()
    }

    private fun handleYoutubeOrientation(isPortrait: Boolean) {
        if (isPortrait) {
            Log.d(TAG, "YouTube portrait (Shorts)")
            grayscaleManager.enable()
            if (prefs.isMorningBlockEnabled() && isMorningHours()) {
                Log.i(TAG, "Shorts bloqueados por horario matutino (00-09)")
                soundManager.playRandomSound()
                handler.postDelayed({
                    if (currentForegroundPkg == YOUTUBE_PKG) performGlobalAction(GLOBAL_ACTION_HOME)
                }, 200)
                return
            }
            if (prefs.isYoutubeShortsBlockedToday()) {
                Log.i(TAG, "Shorts bloqueados hoy — castigo inmediato")
                soundManager.playRandomSound()
                handler.postDelayed({
                    if (currentForegroundPkg == YOUTUBE_PKG) performGlobalAction(GLOBAL_ACTION_HOME)
                }, 200)
                return
            }
            startYoutubePortraitTimer()
        } else {
            Log.d(TAG, "YouTube landscape — grayscale OFF, shorts timer pausado")
            grayscaleManager.disable()
            pauseYoutubePortraitTimer()
        }
    }

    private fun startYoutubePortraitTimer() {
        if (youtubePortraitTimerRunnable != null) return

        val elapsed = prefs.getYoutubeShortsElapsed()
        val remaining = YOUTUBE_PORTRAIT_TIMER_MS - elapsed

        if (remaining <= 0) {
            prefs.blockYoutubeShortsUntilMidnight()
            prefs.recordForceClose(YOUTUBE_PKG, System.currentTimeMillis())
            soundManager.playRandomSound()
            handler.postDelayed({
                if (currentForegroundPkg == YOUTUBE_PKG) performGlobalAction(GLOBAL_ACTION_HOME)
            }, 200)
            return
        }

        prefs.setYoutubePortraitStart(System.currentTimeMillis())
        val runnable = object : Runnable {
            override fun run() {
                if (currentForegroundPkg != YOUTUBE_PKG) {
                    youtubePortraitTimerRunnable = null
                    return
                }
                val pm = getSystemService(PowerManager::class.java)
                if (pm != null && !pm.isInteractive) {
                    handler.postDelayed(this, 30_000L)
                    return
                }
                Log.i(TAG, "Timer de Shorts expirado — alerta + cierre")
                val now = System.currentTimeMillis()
                val start = prefs.getYoutubePortraitStart()
                if (start > 0) prefs.addYoutubeShortsElapsed(now - start)
                prefs.clearYoutubePortraitStart()
                prefs.blockYoutubeShortsUntilMidnight()
                prefs.recordForceClose(YOUTUBE_PKG, now)
                youtubePortraitTimerRunnable = null
                soundManager.playRandomSound()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
        youtubePortraitTimerRunnable = runnable
        handler.postDelayed(runnable, remaining)
        Log.d(TAG, "YouTube Shorts timer iniciado — restante: ${remaining / 1000}s")
    }

    private fun pauseYoutubePortraitTimer() {
        youtubePortraitTimerRunnable?.let { handler.removeCallbacks(it) }
        youtubePortraitTimerRunnable = null
        val start = prefs.getYoutubePortraitStart()
        if (start > 0) {
            prefs.addYoutubeShortsElapsed(System.currentTimeMillis() - start)
            prefs.clearYoutubePortraitStart()
        }
    }

    // --- Timer de 15 min para redes sociales ---

    private fun startTimer(pkg: String) {
        stopTimer()
        currentTimerPkg = pkg

        val elapsed = prefs.getElapsedToday(pkg)
        val remaining = TIMER_LIMIT_MS - elapsed

        if (remaining <= 0) {
            prefs.blockAppUntilMidnight(pkg)
            prefs.recordForceClose(pkg, System.currentTimeMillis())
            currentTimerPkg = null
            handler.postDelayed({
                if (currentForegroundPkg == pkg) performGlobalAction(GLOBAL_ACTION_HOME)
            }, 200)
            return
        }

        prefs.setTimerStart(pkg, System.currentTimeMillis())
        val runnable = object : Runnable {
            override fun run() {
                val pm = getSystemService(PowerManager::class.java)
                if (pm != null && !pm.isInteractive) {
                    handler.postDelayed(this, 30_000L)
                    return
                }
                if (currentForegroundPkg != pkg) return
                Log.i(TAG, "Timer expirado para $pkg — bloqueando")
                val now = System.currentTimeMillis()
                val start = prefs.getTimerStart(pkg)
                if (start > 0) prefs.addElapsedToday(pkg, now - start)
                prefs.clearTimerStart(pkg)
                prefs.blockAppUntilMidnight(pkg)
                prefs.recordForceClose(pkg, now)
                currentTimerPkg = null
                timerRunnable = null
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
        timerRunnable = runnable
        handler.postDelayed(runnable, remaining)
        Log.d(TAG, "Timer iniciado para $pkg — restante: ${remaining / 1000}s")
    }

    private fun stopTimer() {
        val now = System.currentTimeMillis()
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = null
        currentTimerPkg?.let { pkg ->
            val start = prefs.getTimerStart(pkg)
            if (start > 0) prefs.addElapsedToday(pkg, now - start)
            prefs.clearTimerStart(pkg)
        }
        currentTimerPkg = null
    }

    private fun postPersistentNotification() {
        val notification = NotificationCompat.Builder(this, App.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Pupa activo")
            .setContentText("Monitoreando uso de apps")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        NotificationManagerCompat.from(this).notify(1001, notification)
    }

    private fun isMorningHours(): Boolean =
        Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 9

    override fun onInterrupt() { stopTimer() }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
        stopYoutubeOrientationMonitoring()
        rotationManager.restore()
        contentResolver.unregisterContentObserver(grayscaleObserver)
        grayscaleManager.enable()
        NotificationManagerCompat.from(this).cancel(1001)
    }
}
