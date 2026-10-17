package com.nmcbride.monstervoice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * Lets the user pick which speaker the voice comes out of. The choice is saved
 * by device type and name (device ids change when things reconnect).
 */
object OutputRouter {

    class Output(val key: String, val label: String, val device: AudioDeviceInfo?)

    private const val PREFS = "monster_voice"
    private const val KEY_OUTPUT = "output"
    const val AUTO = "auto"

    @Volatile private var chosen: AudioDeviceInfo? = null

    /** All outputs worth offering, starting with Automatic. */
    fun outputs(context: Context): List<Output> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val list = mutableListOf(Output(AUTO, context.getString(R.string.output_auto), null))
        val seen = mutableSetOf<String>()
        for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            val label = labelFor(context, d) ?: continue
            val key = keyFor(d)
            if (seen.add(key)) list += Output(key, label, d)
        }
        return list
    }

    fun savedKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_OUTPUT, AUTO) ?: AUTO

    fun choose(context: Context, output: Output) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OUTPUT, output.key).apply()
        chosen = output.device
    }

    /** Re-finds the saved device, e.g. at startup or after a Bluetooth speaker reconnects. */
    fun refresh(context: Context) {
        val key = savedKey(context)
        chosen = if (key == AUTO) null else outputs(context).firstOrNull { it.key == key }?.device
    }

    /** The label for the saved choice, or Automatic if that device isn't connected. */
    fun currentLabel(context: Context): String {
        val key = savedKey(context)
        val all = outputs(context)
        return (all.firstOrNull { it.key == key } ?: all.first()).label
    }

    /**
     * True when the voice should go to the phone's own loud speaker: either it
     * was picked, or Automatic is on and no headphones or speaker are connected.
     */
    fun wantsSpeakerphone(context: Context): Boolean {
        val key = savedKey(context)
        val external = outputs(context).filter { it.device != null && it.device.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        return when {
            key == AUTO -> external.isEmpty()
            key.startsWith("${AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}|") -> true
            else -> external.none { it.key == key } // the picked device is gone
        }
    }

    fun apply(track: AudioTrack) {
        track.preferredDevice = chosen
    }

    private fun keyFor(d: AudioDeviceInfo) = "${d.type}|${d.productName}"

    private fun labelFor(context: Context, d: AudioDeviceInfo): String? {
        val name = d.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL }
        return when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> context.getString(R.string.output_speaker)
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
