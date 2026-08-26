package com.personal.selfcontrol.managers

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.personal.selfcontrol.data.PrefsManager
import java.io.File

class SoundManager(private val context: Context, private val prefs: PrefsManager) {

    private val soundsDir = File(context.filesDir, "sounds").also { it.mkdirs() }
    private var mediaPlayer: MediaPlayer? = null

    fun importSound(uri: Uri): Result<String> = runCatching {
        val filename = queryFilename(uri)
            ?: throw IllegalArgumentException("No se pudo leer el nombre del archivo")
        val dest = File(soundsDir, filename)
        if (!dest.exists()) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("No se pudo abrir el archivo")
        }
        prefs.addSoundFileName(filename)
        filename
    }

    private fun queryFilename(uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }

    fun getSoundFiles(): List<File> = soundsDir.listFiles()?.toList() ?: emptyList()

    fun deleteSound(filename: String) {
        File(soundsDir, filename).delete()
        prefs.removeSoundFileName(filename)
    }

    fun playRandomSound() {
        val sounds = getSoundFiles().ifEmpty {
            Log.w(TAG, "No hay sonidos en la biblioteca — agregá sonidos desde la app")
            return
        }
        val file = sounds.random()
        Log.i(TAG, "Reproduciendo castigo: ${file.name}")

        // Solo subimos el volumen del stream de alarma (el que usamos para reproducir).
        // El usuario puede bajarlo con los botones de volumen físicos durante la reproducción.
        val am = context.getSystemService(AudioManager::class.java)
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)

        mediaPlayer?.release()
        mediaPlayer = null

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        // USAGE_ALARM: suena aunque el celular esté en vibración o silencio,
                        // y los botones de volumen físicos lo controlan durante la reproducción.
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener { mp ->
                    mp.release()
                    mediaPlayer = null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reproduciendo ${file.name}: ${e.message}")
            mediaPlayer = null
        }
    }

    companion object {
        private const val TAG = "SoundManager"
    }
}
