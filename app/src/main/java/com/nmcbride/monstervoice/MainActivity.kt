package com.nmcbride.monstervoice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private class Preset(val deep: Int, val growl: Int, val echo: Int)

    private val voice get() = VoiceService.voice
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private lateinit var bank: SoundBank

    private lateinit var micButton: MicButton
    private lateinit var statusText: TextView
    private lateinit var deepBar: SeekBar
    private lateinit var growlBar: SeekBar
    private lateinit var echoBar: SeekBar
    private lateinit var volumeBar: SeekBar
    private lateinit var micBar: SeekBar
    private lateinit var mineChip: TextView
    private lateinit var outputChip: TextView
    private val chips = mutableListOf<TextView>()
    private val pads = mutableListOf<View>()

    private val recorder = Recorder()
    private var recordingSlot = -1
    private var resumeLiveAfterRecording = false
    private var roarSeed = 1
    private var shownRoute: String? = null

    private val presets = mapOf(
        R.id.preset_grumbly to Preset(deep = 8, growl = 15, echo = 0),
        R.id.preset_beast to Preset(deep = 14, growl = 40, echo = 0),
        R.id.preset_giant to Preset(deep = 20, growl = 20, echo = 35),
        R.id.preset_demon to Preset(deep = 24, growl = 80, echo = 25),
    )

    private val meter = object : Runnable {
        override fun run() {
            val on = VoiceService.isRunning
            if (micButton.isOn != on) updateLiveState()
            micButton.level = if (on) voice.level else 0f
            updateRouteStatus()
            micButton.postDelayed(this, 33)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bank = SoundBank(this)

        micButton = findViewById(R.id.mic_button)
        statusText = findViewById(R.id.status_text)
        micButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (VoiceService.isRunning) stopVoice() else startVoiceWithPermission()
        }

        setUpSliders()
        setUpPresets()
        setUpSounds()

        val shake = findViewById<Switch>(R.id.shake_switch)
        shake.isChecked = prefs.getBoolean(KEY_SHAKE, true)
        VoiceService.shakeEnabled = shake.isChecked
        shake.setOnCheckedChangeListener { _, checked ->
            VoiceService.shakeEnabled = checked
            prefs.edit().putBoolean(KEY_SHAKE, checked).apply()
        }

        outputChip = findViewById(R.id.output_chip)
        outputChip.setOnClickListener { pickOutput() }

        applySettings()
    }

    override fun onResume() {
        super.onResume()
        OutputRouter.refresh(this)
        outputChip.text = OutputRouter.currentLabel(this)
        VoiceService.reroute()
        updateLiveState()
        micButton.post(meter)
    }

    override fun onPause() {
        super.onPause()
        micButton.removeCallbacks(meter)
        if (recordingSlot >= 0) finishRecording(pads[recordingSlot])
    }

    // --- Sliders -------------------------------------------------------------

    private fun setUpSliders() {
        deepBar = findViewById(R.id.deep_bar)
        growlBar = findViewById(R.id.growl_bar)
        echoBar = findViewById(R.id.echo_bar)
        volumeBar = findViewById(R.id.volume_bar)
        micBar = findViewById(R.id.mic_bar)

        // Deepness is in half-semitone steps, 0..12 semitones.
        deepBar.max = 24
        growlBar.max = 100
        echoBar.max = 100
        volumeBar.max = 200
        micBar.max = 400

        deepBar.progress = prefs.getInt(KEY_DEEP, 14)
        growlBar.progress = prefs.getInt(KEY_GROWL, 40)
        echoBar.progress = prefs.getInt(KEY_ECHO, 0)
        volumeBar.progress = prefs.getInt(KEY_VOLUME, 100)
        micBar.progress = prefs.getInt(KEY_MIC, 100)

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                applySettings()
                if (fromUser && bar !== volumeBar && bar !== micBar) selectChip(null)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) = saveSettings()
        }
        listOf(deepBar, growlBar, echoBar, volumeBar, micBar).forEach { it.setOnSeekBarChangeListener(listener) }
    }

    private fun applySettings() {
        val semis = deepBar.progress / 2f
        voice.semitones = semis
        voice.growl = growlBar.progress / 100f
        voice.echo = echoBar.progress / 100f
        voice.volume = volumeBar.progress / 100f
        voice.micBoost = micBar.progress / 100f
        findViewById<TextView>(R.id.deep_value).text = getString(R.string.semitones_value, semis)
        findViewById<TextView>(R.id.growl_value).text = getString(R.string.percent_value, growlBar.progress)
        findViewById<TextView>(R.id.echo_value).text = getString(R.string.percent_value, echoBar.progress)
        findViewById<TextView>(R.id.volume_value).text = getString(R.string.percent_value, volumeBar.progress)
        findViewById<TextView>(R.id.mic_value).text = getString(R.string.percent_value, micBar.progress)
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt(KEY_DEEP, deepBar.progress)
            .putInt(KEY_GROWL, growlBar.progress)
            .putInt(KEY_ECHO, echoBar.progress)
            .putInt(KEY_VOLUME, volumeBar.progress)
            .putInt(KEY_MIC, micBar.progress)
            .apply()
    }

    // --- Monster presets -----------------------------------------------------

    private fun setUpPresets() {
        for ((id, preset) in presets) {
            val chip = findViewById<TextView>(id)
            chips += chip
            chip.setOnClickListener { loadPreset(preset, chip) }
        }

        mineChip = findViewById(R.id.preset_mine)
        chips += mineChip
        updateMineChip()
        mineChip.setOnClickListener {
            if (prefs.contains(KEY_MINE_DEEP)) {
                loadPreset(
                    Preset(prefs.getInt(KEY_MINE_DEEP, 14), prefs.getInt(KEY_MINE_GROWL, 40), prefs.getInt(KEY_MINE_ECHO, 0)),
                    mineChip,
                )
            } else {
                toast(getString(R.string.mine_empty))
            }
        }
        mineChip.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            askForName(R.string.name_monster, prefs.getString(KEY_MINE_NAME, null) ?: "", getString(R.string.preset_mine).removePrefix("★ ")) { name ->
                prefs.edit()
                    .putString(KEY_MINE_NAME, name)
                    .putInt(KEY_MINE_DEEP, deepBar.progress)
                    .putInt(KEY_MINE_GROWL, growlBar.progress)
                    .putInt(KEY_MINE_ECHO, echoBar.progress)
                    .apply()
                updateMineChip()
                selectChip(mineChip)
                toast(getString(R.string.mine_saved, name))
            }
            true
        }

        selectChip(chips.getOrNull(prefs.getInt(KEY_CHIP, -1)))
    }

    private fun loadPreset(preset: Preset, chip: TextView) {
        chip.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        deepBar.progress = preset.deep
        growlBar.progress = preset.growl
        echoBar.progress = preset.echo
        selectChip(chip)
        saveSettings()
    }

    private fun selectChip(chip: TextView?) {
        chips.forEach { it.isSelected = it === chip }
        prefs.edit().putInt(KEY_CHIP, chips.indexOf(chip)).apply()
    }

    private fun updateMineChip() {
        val name = prefs.getString(KEY_MINE_NAME, null)
        mineChip.text = if (name.isNullOrBlank()) getString(R.string.preset_mine) else getString(R.string.preset_mine_named, name)
    }

    // --- Sound pads ----------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility") // Pads are also usable via performClick.
    private fun setUpSounds() {
        findViewById<View>(R.id.pad_roar).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            SoundPlayer.play(RoarSynth.create(AudioEngine.SAMPLE_RATE, roarSeed++), volumeBar.progress / 100f)
        }

        val ids = listOf(R.id.pad_1, R.id.pad_2, R.id.pad_3)
        for ((slot, id) in ids.withIndex()) {
            val pad = findViewById<FrameLayout>(id)
            pads += pad
            refreshPad(slot)

            val startRecording = Runnable { beginRecording(slot, pad) }
            pad.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        v.postDelayed(startRecording, HOLD_TO_RECORD_MS)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        v.removeCallbacks(startRecording)
                        if (recordingSlot == slot) {
                            finishRecording(v)
                        } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                            v.performClick()
                        }
                    }
                }
                true
            }
            pad.setOnClickListener { playSlot(slot) }
            pad.findViewById<View>(R.id.pad_menu).setOnClickListener { showPadMenu(slot, it) }
        }
    }

    private fun refreshPad(slot: Int) {
        val pad = pads[slot]
        val has = bank.hasSound(slot)
        val recording = recordingSlot == slot
        pad.isActivated = recording
        pad.findViewById<TextView>(R.id.pad_icon).text = getString(if (has) R.string.icon_sound else R.string.icon_mic)
        pad.findViewById<TextView>(R.id.pad_name).text = when {
            recording -> getString(R.string.pad_recording)
            has -> bank.name(slot) ?: getString(R.string.sound_default_name, slot + 1)
            else -> getString(R.string.pad_empty)
        }
        pad.findViewById<TextView>(R.id.pad_hint).text = getString(
            when {
                recording -> R.string.pad_recording_hint
                has -> R.string.pad_ready_hint
                else -> R.string.pad_empty_hint
            }
        )
        pad.findViewById<View>(R.id.pad_menu).visibility = if (has && !recording) View.VISIBLE else View.GONE
    }

    private fun playSlot(slot: Int) {
        val sound = bank.load(slot)
        if (sound == null) {
            toast(getString(R.string.pad_empty_hint))
            return
        }
        pads[slot].performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        SoundPlayer.play(sound, volumeBar.progress / 100f)
    }

    private fun beginRecording(slot: Int, pad: View) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD)
            return
        }
        // The live voice and the recorder can't share the mic, so pause live while recording.
        resumeLiveAfterRecording = VoiceService.isRunning
        if (resumeLiveAfterRecording) stopVoice()
        SoundPlayer.stop()

        pad.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        recordingSlot = slot
        refreshPad(slot)
        // Give the live voice a moment to release the mic before recording.
        if (resumeLiveAfterRecording) pad.postDelayed({ if (recordingSlot == slot) recorder.start() }, 200) else recorder.start()
    }

    private fun finishRecording(pad: View) {
        val slot = recordingSlot
        if (slot < 0) return
        val raw = recorder.stop()
        recordingSlot = -1
        refreshPad(slot)
        pad.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        if (raw.size < AudioEngine.SAMPLE_RATE * 3 / 10) {
            toast(getString(R.string.too_short))
            resumeLiveIfNeeded()
            return
        }

        // Apply the current monster settings to the recording off the main thread.
        val fx = MonsterVoice(AudioEngine.SAMPLE_RATE).apply {
            semitones = voice.semitones
            growl = voice.growl
            echo = voice.echo
            micBoost = voice.micBoost
        }
        Thread {
            val sound = fx.render(raw)
            runOnUiThread {
                val current = bank.name(slot) ?: ""
                askForName(R.string.name_sound, current, getString(R.string.sound_name_hint), onCancel = ::resumeLiveIfNeeded) { name ->
                    bank.save(slot, sound, name)
                    refreshPad(slot)
                    resumeLiveIfNeeded()
                    SoundPlayer.play(sound, volumeBar.progress / 100f)
                }
            }
        }.start()
    }

    private fun resumeLiveIfNeeded() {
        if (resumeLiveAfterRecording) {
            resumeLiveAfterRecording = false
            startVoiceWithPermission()
        }
    }

    private fun showPadMenu(slot: Int, anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_RENAME, 0, R.string.rename)
        menu.menu.add(0, MENU_SHARE, 1, R.string.share)
        menu.menu.add(0, MENU_DELETE, 2, R.string.delete)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RENAME -> askForName(R.string.name_sound, bank.name(slot) ?: "", getString(R.string.sound_name_hint)) { name ->
                    bank.rename(slot, name)
                    refreshPad(slot)
                }
                MENU_SHARE -> shareSlot(slot)
                MENU_DELETE -> {
                    bank.delete(slot)
                    refreshPad(slot)
                }
            }
            true
        }
        menu.show()
    }

    private fun shareSlot(slot: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            toast(getString(R.string.share_unsupported))
            return
        }
        val uri = runCatching { bank.exportForSharing(slot) }.getOrNull()
        if (uri == null) {
            toast(getString(R.string.share_failed))
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/wav")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    // --- Output speaker ------------------------------------------------------

    private fun pickOutput() {
        val outputs = OutputRouter.outputs(this)
        val savedKey = OutputRouter.savedKey(this)
        val checked = outputs.indexOfFirst { it.key == savedKey }.coerceAtLeast(0)
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.output)
            .setSingleChoiceItems(outputs.map { it.label }.toTypedArray(), checked) { dialog, which ->
                OutputRouter.choose(this, outputs[which])
                outputChip.text = outputs[which].label
                VoiceService.reroute()
                updateLiveState()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Live voice ----------------------------------------------------------

    private fun startVoiceWithPermission() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startVoice() else requestPermissions(missing.toTypedArray(), REQUEST_LIVE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        when {
            !granted -> toast(getString(R.string.need_mic))
            requestCode == REQUEST_LIVE -> startVoice()
        }
    }

    private fun startVoice() {
        SoundPlayer.stop()
        startForegroundService(Intent(this, VoiceService::class.java))
        micButton.isOn = true
        statusText.setText(R.string.status_on)
    }

    private fun stopVoice() {
        stopService(Intent(this, VoiceService::class.java))
        micButton.isOn = false
        statusText.setText(R.string.status_off)
    }

    private fun updateLiveState() {
        val on = VoiceService.isRunning
        // In speakerphone mode the volume buttons need to control call volume.
        volumeControlStream = if (on) OutputRouter.route.volumeStream else AudioManager.STREAM_MUSIC
        micButton.isOn = on
        micButton.contentDescription = getString(if (on) R.string.stop else R.string.start)
        statusText.setText(if (on) R.string.status_on else R.string.status_off)
        shownRoute = null
    }

    /** Shows where the live voice is really coming out, so speaker problems are easy to spot. */
    private fun updateRouteStatus() {
        if (!VoiceService.isRunning) return
        val where = OutputRouter.describe(this, VoiceService.routedDevice) ?: return
        if (where == shownRoute) return
        shownRoute = where
        statusText.text = getString(R.string.status_on_route, where)
    }

    // --- Helpers -------------------------------------------------------------

    private fun askForName(
        titleRes: Int,
        current: String,
        hint: String,
        onCancel: () -> Unit = {},
        onName: (String) -> Unit,
    ) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(current)
            setHint(hint)
            setSingleLine()
            setSelection(text.length)
            setTextColor(getColor(R.color.text))
            setHintTextColor(getColor(R.color.muted))
        }
        val frame = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(titleRes)
            .setView(frame)
            .setPositiveButton(R.string.save) { _, _ ->
                onName(input.text.toString().trim().ifEmpty { hint })
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val PREFS = "monster_voice"
        private const val KEY_DEEP = "deep"
        private const val KEY_GROWL = "growl"
        private const val KEY_ECHO = "echo"
        private const val KEY_VOLUME = "volume"
        private const val KEY_MIC = "mic"
        private const val KEY_SHAKE = "shake"
        private const val KEY_CHIP = "chip"
        private const val KEY_MINE_NAME = "mine_name"
        private const val KEY_MINE_DEEP = "mine_deep"
        private const val KEY_MINE_GROWL = "mine_growl"
        private const val KEY_MINE_ECHO = "mine_echo"
        private const val REQUEST_LIVE = 1
        private const val REQUEST_RECORD = 2
        private const val MENU_RENAME = 1
        private const val MENU_SHARE = 2
        private const val MENU_DELETE = 3
        private const val HOLD_TO_RECORD_MS = 350L
    }
}
