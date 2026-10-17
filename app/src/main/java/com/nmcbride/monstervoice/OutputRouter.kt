package com.nmcbride.monstervoice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * Lets the user pick which speaker the voice comes out of. External devices are
 * saved by type and name (device ids change when things reconnect).
 *
 * Phones disagree about where audio goes while the mic is recording, and some
 * send it to the quiet earpiece. So the phone speaker is offered three ways
 * ([Route]); whichever one works on a given phone is the one to keep.
 */
object OutputRouter {

    /** How the audio is tagged for Android, which decides where the phone sends it. */
    enum class Route(val usage: Int, val volumeStream: Int, val callMode: Boolean) {
        MEDIA(AudioAttributes.USAGE_MEDIA, AudioManager.STREAM_MUSIC, false),
        ALARM(AudioAttributes.USAGE_ALARM, AudioManager.STREAM_ALARM, false),
        CALL(AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioManager.STREAM_VOICE_CALL, true),
    }

    class Output(val key: String, val label: String, val device: AudioDeviceInfo?, val route: Route)

    private const val PREFS = "monster_voice"
    private const val KEY_OUTPUT = "output"
    const val AUTO = "auto"
    private const val SPEAKER = "speaker:"

    @Volatile private var chosen: AudioDeviceInfo? = null
    @Volatile var route: Route = Route.MEDIA
        private set

    /** All outputs worth offering, starting with Automatic. */
    fun outputs(context: Context): List<Output> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val list = mutableListOf(
            Output(AUTO, context.getString(R.string.output_auto), null, Route.MEDIA),
            Output(SPEAKER + "media", context.getString(R.string.output_speaker), speaker, Route.MEDIA),
            Output(SPEAKER + "alarm", context.getString(R.string.output_speaker_alarm), speaker, Route.ALARM),
            Output(SPEAKER + "call", context.getString(R.string.output_speaker_call), speaker, Route.CALL),
        )
        val seen = mutableSetOf<String>()
        for (d in devices) {
            val label = externalLabel(context, d) ?: continue
            val key = "${d.type}|${d.productName}"
            if (seen.add(key)) list += Output(key, label, d, Route.MEDIA)
        }
        return list
    }

    fun savedKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_OUTPUT, AUTO) ?: AUTO

    fun choose(context: Context, output: Output) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OUTPUT, output.key).apply()
        refresh(context)
    }

    /**
     * Re-finds the saved choice, e.g. at startup or after a Bluetooth speaker
     * reconnects. Automatic uses a connected external device if there is one,
     * otherwise the phone speaker in alarm mode, which most phones keep on the
     * loudspeaker even while recording.
     */
    fun refresh(context: Context) {
        val all = outputs(context)
        val key = savedKey(context)
        val picked = all.firstOrNull { it.key == key && key != AUTO }
            ?: all.firstOrNull { it.key.contains('|') }
            ?: all.first { it.key == SPEAKER + "alarm" }
        chosen = picked.device
        route = picked.route
    }

    /** The label for the saved choice, or Automatic if that device isn't connected. */
    fun currentLabel(context: Context): String {
        val key = savedKey(context)
        val all = outputs(context)
        return (all.firstOrNull { it.key == key } ?: all.first()).label
    }

    fun apply(track: AudioTrack) {
        track.preferredDevice = chosen
    }

    /** A friendly name for where a playing track actually ended up. */
    fun describe(context: Context, device: AudioDeviceInfo?): String? = when (device?.type) {
        null -> null
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> context.getString(R.string.output_speaker)
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> context.getString(R.string.output_earpiece)
        else -> externalLabel(context, device) ?: device.productName?.toString()
    }

    private fun externalLabel(context: Context, d: AudioDeviceInfo): String? {
        val name = d.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL }
        return when (d.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> context.getString(R.string.output_wired)
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            TYPE_BLE_HEADSET,
            TYPE_BLE_SPEAKER -> context.getString(R.string.output_bluetooth, name ?: "speaker")
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> context.getString(R.string.output_usb)
            else -> null
        }
    }

    // AudioDeviceInfo.TYPE_BLE_HEADSET / TYPE_BLE_SPEAKER (API 31); inlined so older phones compile fine.
    private const val TYPE_BLE_HEADSET = 26
    private const val TYPE_BLE_SPEAKER = 27
}
