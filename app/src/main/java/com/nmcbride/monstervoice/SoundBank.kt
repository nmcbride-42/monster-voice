package com.nmcbride.monstervoice

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Stores the custom recorded sounds as WAV files in the app's private storage. */
class SoundBank(private val context: Context) {

    private val dir = File(context.filesDir, "sounds").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("sounds", Context.MODE_PRIVATE)

    fun name(slot: Int): String? = prefs.getString("name_$slot", null)

    fun hasSound(slot: Int) = file(slot).exists()

    fun save(slot: Int, sound: FloatArray, name: String) {
        file(slot).writeBytes(toWav(sound))
        prefs.edit().putString("name_$slot", name).apply()
    }

    fun rename(slot: Int, name: String) {
        prefs.edit().putString("name_$slot", name).apply()
    }

    fun delete(slot: Int) {
        file(slot).delete()
        prefs.edit().remove("name_$slot").apply()
    }

    fun load(slot: Int): FloatArray? {
        val f = file(slot)
        if (!f.exists()) return null
        val bytes = f.readBytes()
        val buf = ByteBuffer.wrap(bytes, WAV_HEADER, bytes.size - WAV_HEADER).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray((bytes.size - WAV_HEADER) / 2) { buf.short / 32768f }
    }

    /**
     * Copies a sound into the phone's Music/Monster Voice folder so it can be
     * shared to other apps. Returns its content URI, or null on Android 9 and older.
     */
    fun exportForSharing(slot: Int): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val f = file(slot)
        if (!f.exists()) return null
        val title = (name(slot) ?: "Monster sound").replace(Regex("[^A-Za-z0-9 !_-]"), "").ifBlank { "Monster sound" }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$title ${System.currentTimeMillis() / 1000}.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Monster Voice")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(f.readBytes()) }
        return uri
    }

    private fun file(slot: Int) = File(dir, "sound_$slot.wav")

    private fun toWav(sound: FloatArray): ByteArray {
        val rate = AudioEngine.SAMPLE_RATE
        val dataSize = sound.size * 2
        val buf = ByteBuffer.allocate(WAV_HEADER + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(dataSize)
        for (v in sound) buf.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return buf.array()
    }

    companion object {
        const val SLOTS = 3
        private const val WAV_HEADER = 44
    }
}
