package com.personal.selfcontrol

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.personal.selfcontrol.data.ForceCloseRecord
import com.personal.selfcontrol.data.PrefsManager
import com.personal.selfcontrol.databinding.ActivityMainBinding
import com.personal.selfcontrol.databinding.ItemHistoryBinding
import com.personal.selfcontrol.databinding.ItemSoundBinding
import com.personal.selfcontrol.managers.GrayscaleManager
import com.personal.selfcontrol.managers.SoundManager
import com.personal.selfcontrol.service.AppMonitorService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.accessibility.AccessibilityManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager
    private lateinit var soundManager: SoundManager
    private lateinit var grayscaleManager: GrayscaleManager

    private val soundAdapter = SoundAdapter()
    private val historyAdapter = HistoryAdapter()

    // Actualiza el tiempo restante cada segundo mientras la app está visible
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRefreshRunnable = object : Runnable {
        override fun run() {
            refreshAppStatus()
            timerHandler.postDelayed(this, 1000)
        }
    }

    private val soundPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        var imported = 0
        uris.forEach { uri ->
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) {}
            soundManager.importSound(uri).onSuccess { imported++ }
        }
        if (imported > 0) {
            Snackbar.make(binding.root, "$imported sonido(s) importado(s)", Snackbar.LENGTH_SHORT).show()
        }
        refreshSoundList()
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)
        soundManager = SoundManager(this, prefs)
        grayscaleManager = GrayscaleManager(this)

        binding.rvSounds.layoutManager = LinearLayoutManager(this)
        binding.rvSounds.adapter = soundAdapter
        binding.rvSounds.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        binding.rvSounds.isNestedScrollingEnabled = false

        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = historyAdapter
        binding.rvHistory.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        binding.rvHistory.isNestedScrollingEnabled = false

        binding.switchMorningBlock.isChecked = prefs.isMorningBlockEnabled()
        binding.switchMorningBlock.setOnCheckedChangeListener { _, isChecked ->
            prefs.setMorningBlockEnabled(isChecked)
        }

        setupButtons()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleIncomingShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShare(intent)
    }

    override fun onResume() {
        super.onResume()
        // Si el usuario está mirando Pupa, no puede estar en una app monitoreada.
        // Frenamos cualquier timer que haya quedado corriendo (seguro ante eventos perdidos de Samsung).
        val now = System.currentTimeMillis()
        for (pkg in AppMonitorService.MONITORED_APPS) {
            val start = prefs.getTimerStart(pkg)
            if (start > 0) {
                prefs.addElapsedToday(pkg, now - start)
                prefs.clearTimerStart(pkg)
            }
        }
        val ytStart = prefs.getYoutubePortraitStart()
        if (ytStart > 0) {
            prefs.addYoutubeShortsElapsed(now - ytStart)
            prefs.clearYoutubePortraitStart()
        }
        refreshStatus()
        refreshSoundList()
        refreshHistory()
        timerHandler.post(timerRefreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        timerHandler.removeCallbacks(timerRefreshRunnable)
    }

    private fun setupButtons() {
        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnWriteSettings.setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        binding.btnBattery.setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        binding.btnAddSounds.setOnClickListener {
            soundPicker.launch(arrayOf("audio/mpeg", "audio/mp4", "audio/wav", "audio/ogg", "audio/opus", "audio/aac", "audio/*"))
        }
    }

    private fun handleIncomingShare(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                listOfNotNull(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            }
            else -> return
        }
        if (uris.isNotEmpty()) importSharedAudio(uris)
    }

    private fun importSharedAudio(uris: List<Uri>) {
        var imported = 0; var failed = 0
        uris.forEach { uri ->
            soundManager.importSound(uri).onSuccess { imported++ }.onFailure { failed++ }
        }
        val msg = when {
            imported > 0 && failed == 0 -> "$imported sonido(s) agregado(s) a la biblioteca"
            imported > 0 -> "$imported agregado(s), $failed no se pudo(n) importar"
            else -> "No se pudo importar el audio"
        }
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
        refreshSoundList()
    }

    private fun refreshStatus() {
        val accessibilityOk = isAccessibilityServiceEnabled()
        binding.statusAccessibility.text = if (accessibilityOk) "✓ Activo" else "✗ Inactivo"
        binding.statusAccessibility.setTextColor(statusColor(accessibilityOk))

        val writeSettingsOk = AndroidSettings.System.canWrite(this)
        binding.statusWriteSettings.text = if (writeSettingsOk) "✓ Otorgado" else "✗ No otorgado"
        binding.statusWriteSettings.setTextColor(statusColor(writeSettingsOk))

        val secureOk = grayscaleManager.isPermissionGranted()
        binding.statusSecureSettings.text = if (secureOk) "✓ Otorgado (ADB)" else "✗ Falta — correr ADB"
        binding.statusSecureSettings.setTextColor(statusColor(secureOk))
        binding.layoutAdbCommand.visibility = if (secureOk) View.GONE else View.VISIBLE
    }

    private fun refreshAppStatus() {
        val now = System.currentTimeMillis()

        applyAppStatus(binding.tvInstagramStatus, "com.instagram.android", now)
        applyAppStatus(binding.tvChromeStatus, "com.android.chrome", now)

        val tiktokPkgs = listOf(
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically.go", "com.ss.android.ugc.trill.lite"
        )
        val tiktokBlocked = tiktokPkgs.any { prefs.isAppBlockedToday(it) }
        val tiktokElapsed = tiktokPkgs.sumOf { prefs.getElapsedToday(it) }
        val tiktokStart = tiktokPkgs.firstNotNullOfOrNull { prefs.getTimerStart(it).takeIf { t -> t > 0 } } ?: 0L
        applyTimerText(binding.tvTikTokStatus, tiktokBlocked, tiktokElapsed, tiktokStart, now, AppMonitorService.TIMER_LIMIT_MS)

        val ytBlocked = prefs.isYoutubeShortsBlockedToday()
        val ytElapsed = prefs.getYoutubeShortsElapsed()
        val ytPortraitStart = prefs.getYoutubePortraitStart()
        applyTimerText(binding.tvYoutubeStatus, ytBlocked, ytElapsed, ytPortraitStart, now,
            AppMonitorService.YOUTUBE_PORTRAIT_TIMER_MS, emptyLabel = "Normal", blockedLabel = "Shorts bloqueados hoy")
    }

    private fun applyAppStatus(view: TextView, pkg: String, now: Long) {
        applyTimerText(
            view,
            prefs.isAppBlockedToday(pkg),
            prefs.getElapsedToday(pkg),
            prefs.getTimerStart(pkg),
            now,
            AppMonitorService.TIMER_LIMIT_MS
        )
    }

    // elapsedToday = tiempo acumulado en sesiones previas de hoy
    // timerStart > 0 = hay una sesión activa ahora mismo
    private fun applyTimerText(
        view: TextView,
        blocked: Boolean,
        elapsedToday: Long,
        timerStart: Long,
        now: Long,
        limitMs: Long,
        emptyLabel: String = "Disponible",
        blockedLabel: String = "Bloqueada hoy"
    ) {
        val currentElapsed = if (timerStart > 0) (now - timerStart) else 0L
        val totalElapsed = elapsedToday + currentElapsed
        val isRunning = timerStart > 0

        when {
            blocked -> {
                view.text = blockedLabel
                view.setTextColor(getColor(android.R.color.holo_red_dark))
            }
            totalElapsed > 0 -> {
                val min = totalElapsed / 60000
                val sec = (totalElapsed % 60000) / 1000
                view.text = if (isRunning) "%d:%02d usados".format(min, sec)
                            else "%d:%02d usados hoy".format(min, sec)
                view.setTextColor(
                    getColor(if (isRunning) android.R.color.holo_orange_dark else android.R.color.holo_blue_dark)
                )
            }
            else -> {
                view.text = emptyLabel
                view.setTextColor(statusColor(true))
            }
        }
    }

    private fun refreshSoundList() {
        val files = soundManager.getSoundFiles()
        soundAdapter.setData(files)
        binding.tvSoundCount.text = "Sonidos de castigo (${files.size})"
    }

    private fun refreshHistory() {
        val history = prefs.getForceCloseHistory()
        historyAdapter.setData(history)
        binding.tvNoHistory.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java) ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun statusColor(ok: Boolean) =
        if (ok) getColor(android.R.color.holo_green_dark)
        else getColor(android.R.color.holo_red_dark)

    inner class SoundAdapter : RecyclerView.Adapter<SoundAdapter.VH>() {
        private val items = mutableListOf<File>()
        fun setData(files: List<File>) { items.clear(); items.addAll(files); notifyDataSetChanged() }
        inner class VH(val b: ItemSoundBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemSoundBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) {
            val file = items[position]
            holder.b.tvSoundName.text = file.name
            holder.b.btnDelete.setOnClickListener { soundManager.deleteSound(file.name); refreshSoundList() }
        }
        override fun getItemCount() = items.size
    }

    inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.VH>() {
        private val items = mutableListOf<ForceCloseRecord>()
        private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        private val appNames = mapOf(
            "com.instagram.android" to "Instagram",
            "com.android.chrome" to "Chrome",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.ss.android.ugc.trill" to "TikTok",
            "com.zhiliaoapp.musically.go" to "TikTok",
            "com.ss.android.ugc.trill.lite" to "TikTok",
            "com.google.android.youtube" to "YouTube Shorts"
        )
        fun setData(records: List<ForceCloseRecord>) { items.clear(); items.addAll(records); notifyDataSetChanged() }
        inner class VH(val b: ItemHistoryBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) {
            val record = items[position]
            holder.b.tvAppName.text = appNames[record.packageName] ?: record.packageName
            holder.b.tvTimestamp.text = fmt.format(Date(record.timestamp))
        }
        override fun getItemCount() = items.size
    }
}
