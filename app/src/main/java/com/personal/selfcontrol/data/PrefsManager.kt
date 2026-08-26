package com.personal.selfcontrol.data

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class PrefsManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("selfcontrol_prefs", Context.MODE_PRIVATE)

    // --- Bloqueo diario (se auto-expira a medianoche) ---

    fun blockAppUntilMidnight(pkg: String) {
        prefs.edit().putLong("block_until_$pkg", getMidnightTimestamp()).apply()
    }

    fun isAppBlockedToday(pkg: String): Boolean {
        val blockedUntil = prefs.getLong("block_until_$pkg", 0L)
        return System.currentTimeMillis() < blockedUntil
    }

    private fun getMidnightTimestamp(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun clearAllBlocks() {
        val edit = prefs.edit()
        prefs.all.keys.filter {
            it.startsWith("block_until_") ||
            it.startsWith("timer_start_") ||
            it.startsWith("elapsed_today_") ||
            it.startsWith("elapsed_date_") ||
            it == "youtube_portrait_start" ||
            it.startsWith("youtube_shorts_")
        }.forEach { edit.remove(it) }
        edit.apply()
    }

    // --- Tiempo acumulado hoy por app (para mostrar en la UI aunque el timer esté pausado) ---

    fun addElapsedToday(pkg: String, ms: Long) {
        val today = todayString()
        val storedDate = prefs.getString("elapsed_date_$pkg", "")
        val current = if (storedDate == today) prefs.getLong("elapsed_today_$pkg", 0L) else 0L
        prefs.edit()
            .putLong("elapsed_today_$pkg", current + ms)
            .putString("elapsed_date_$pkg", today)
            .apply()
    }

    fun getElapsedToday(pkg: String): Long {
        val today = todayString()
        if (prefs.getString("elapsed_date_$pkg", "") != today) return 0L
        return prefs.getLong("elapsed_today_$pkg", 0L)
    }

    private fun todayString(): String =
        SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

    // --- YouTube Shorts: tiempo acumulado en portrait ---

    fun addYoutubeShortsElapsed(ms: Long) {
        val today = todayString()
        val storedDate = prefs.getString("youtube_shorts_elapsed_date", "")
        val current = if (storedDate == today) prefs.getLong("youtube_shorts_elapsed_today", 0L) else 0L
        prefs.edit()
            .putLong("youtube_shorts_elapsed_today", current + ms)
            .putString("youtube_shorts_elapsed_date", today)
            .apply()
    }

    fun getYoutubeShortsElapsed(): Long {
        val today = todayString()
        if (prefs.getString("youtube_shorts_elapsed_date", "") != today) return 0L
        return prefs.getLong("youtube_shorts_elapsed_today", 0L)
    }

    fun blockYoutubeShortsUntilMidnight() {
        prefs.edit().putLong("youtube_shorts_blocked_until", getMidnightTimestamp()).apply()
    }

    fun isYoutubeShortsBlockedToday(): Boolean {
        val blockedUntil = prefs.getLong("youtube_shorts_blocked_until", 0L)
        return System.currentTimeMillis() < blockedUntil
    }

    // --- Bloqueo matutino (00:00 a 09:00) ---

    fun setMorningBlockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("morning_block_enabled", enabled).apply()
    }

    fun isMorningBlockEnabled(): Boolean =
        prefs.getBoolean("morning_block_enabled", false)

    fun getLastInstallTime(): Long = prefs.getLong("last_install_time", 0L)
    fun saveLastInstallTime(time: Long) = prefs.edit().putLong("last_install_time", time).apply()

    // --- Timer start times (para mostrar tiempo restante en la UI) ---

    fun setTimerStart(pkg: String, startMs: Long) =
        prefs.edit().putLong("timer_start_$pkg", startMs).apply()

    fun getTimerStart(pkg: String): Long = prefs.getLong("timer_start_$pkg", 0L)

    fun clearTimerStart(pkg: String) =
        prefs.edit().remove("timer_start_$pkg").apply()

    fun setYoutubePortraitStart(startMs: Long) =
        prefs.edit().putLong("youtube_portrait_start", startMs).apply()

    fun getYoutubePortraitStart(): Long = prefs.getLong("youtube_portrait_start", 0L)

    fun clearYoutubePortraitStart() =
        prefs.edit().remove("youtube_portrait_start").apply()

    // --- Historial de cierres ---

    fun recordForceClose(pkg: String, timestamp: Long) {
        val history = getHistoryStrings().toMutableSet()
        history.add(ForceCloseRecord(pkg, timestamp).toStorageString())
        val trimmed = history
            .mapNotNull { ForceCloseRecord.from(it) }
            .sortedByDescending { it.timestamp }
            .take(50)
            .map { it.toStorageString() }
            .toSet()
        prefs.edit().putStringSet(KEY_HISTORY, trimmed).apply()
    }

    fun getForceCloseHistory(): List<ForceCloseRecord> =
        getHistoryStrings()
            .mapNotNull { ForceCloseRecord.from(it) }
            .sortedByDescending { it.timestamp }

    private fun getHistoryStrings(): Set<String> =
        prefs.getStringSet(KEY_HISTORY, emptySet()) ?: emptySet()

    // --- Sonidos ---

    fun getSoundFileNames(): Set<String> =
        prefs.getStringSet(KEY_SOUNDS, emptySet()) ?: emptySet()

    fun addSoundFileName(name: String) {
        val current = getSoundFileNames().toMutableSet()
        current.add(name)
        prefs.edit().putStringSet(KEY_SOUNDS, current).apply()
    }

    fun removeSoundFileName(name: String) {
        val current = getSoundFileNames().toMutableSet()
        current.remove(name)
        prefs.edit().putStringSet(KEY_SOUNDS, current).apply()
    }

    companion object {
        private const val KEY_HISTORY = "force_close_history"
        private const val KEY_SOUNDS = "sound_files"
    }
}
