package com.nmcbride.monstervoice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val voice get() = VoiceService.voice
    private lateinit var startButton: Button
    private lateinit var deepBar: SeekBar
    private lateinit var growlBar: SeekBar
    private lateinit var volumeBar: SeekBar
    private lateinit var echoBar: SeekBar
    private lateinit var micBar: SeekBar
    private lateinit var deepLabel: TextView
    private lateinit var growlLabel: TextView
    private lateinit var volumeLabel: TextView
    private lateinit var echoLabel: TextView
    private lateinit var micLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startButton = findViewById(R.id.start_button)
        deepBar = findViewById(R.id.deep_bar)
        growlBar = findViewById(R.id.growl_bar)
        volumeBar = findViewById(R.id.volume_bar)
        deepLabel = findViewById(R.id.deep_label)
        growlLabel = findViewById(R.id.growl_label)
        volumeLabel = findViewById(R.id.volume_label)
        echoBar = findViewById(R.id.echo_bar)
        micBar = findViewById(R.id.mic_bar)
        echoLabel = findViewById(R.id.echo_label)
        micLabel = findViewById(R.id.mic_label)

        // Deepness slider is in half-semitone steps, 0..12 semitones.
        deepBar.max = 24
        growlBar.max = 100
        volumeBar.max = 200
        echoBar.max = 100
        micBar.max = 400

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        deepBar.progress = prefs.getInt(KEY_DEEP, 14)
        growlBar.progress = prefs.getInt(KEY_GROWL, 30)
        volumeBar.progress = prefs.getInt(KEY_VOLUME, 100)
        echoBar.progress = prefs.getInt(KEY_ECHO, 0)
        micBar.progress = prefs.getInt(KEY_MIC, 100)

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) = applySettings()
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) = saveSettings()
        }
        deepBar.setOnSeekBarChangeListener(listener)
        growlBar.setOnSeekBarChangeListener(listener)
        volumeBar.setOnSeekBarChangeListener(listener)
        echoBar.setOnSeekBarChangeListener(listener)
        micBar.setOnSeekBarChangeListener(listener)

        findViewById<Button>(R.id.preset_grumbly).setOnClickListener { preset(deep = 8, growl = 15, echo = 0) }
        findViewById<Button>(R.id.preset_beast).setOnClickListener { preset(deep = 14, growl = 40, echo = 0) }
        findViewById<Button>(R.id.preset_giant).setOnClickListener { preset(deep = 20, growl = 20, echo = 35) }
        findViewById<Button>(R.id.preset_demon).setOnClickListener { preset(deep = 24, growl = 80, echo = 25) }

        findViewById<Button>(R.id.roar_button).setOnClickListener {
            if (VoiceService.isRunning) voice.roar()
            else Toast.makeText(this, R.string.roar_needs_start, Toast.LENGTH_SHORT).show()
        }

        startButton.setOnClickListener {
            if (VoiceService.isRunning) stopVoice() else startVoiceWithPermission()
        }

        applySettings()
    }

    override fun onResume() {
        super.onResume()
        updateButton()
    }

    private fun preset(deep: Int, growl: Int, echo: Int) {
        deepBar.progress = deep
        growlBar.progress = growl
        echoBar.progress = echo
        saveSettings()
    }

    private fun applySettings() {
        val semis = deepBar.progress / 2f
        voice.semitones = semis
        voice.growl = growlBar.progress / 100f
        voice.volume = volumeBar.progress / 100f
        voice.echo = echoBar.progress / 100f
        voice.micBoost = micBar.progress / 100f
        deepLabel.text = getString(R.string.deepness_value, semis)
        growlLabel.text = getString(R.string.growl_value, growlBar.progress)
        volumeLabel.text = getString(R.string.volume_value, volumeBar.progress)
        echoLabel.text = getString(R.string.echo_value, echoBar.progress)
        micLabel.text = getString(R.string.mic_value, micBar.progress)
    }

    private fun saveSettings() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DEEP, deepBar.progress)
            .putInt(KEY_GROWL, growlBar.progress)
            .putInt(KEY_VOLUME, volumeBar.progress)
            .putInt(KEY_ECHO, echoBar.progress)
            .putInt(KEY_MIC, micBar.progress)
            .apply()
    }

    private fun startVoiceWithPermission() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startVoice() else requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoice()
        } else {
            Toast.makeText(this, R.string.need_mic, Toast.LENGTH_LONG).show()
        }
    }

    private fun startVoice() {
        startForegroundService(Intent(this, VoiceService::class.java))
        startButton.text = getString(R.string.stop)
        startButton.postDelayed(::updateButton, 500)
    }

    private fun stopVoice() {
        stopService(Intent(this, VoiceService::class.java))
        startButton.text = getString(R.string.start)
        startButton.postDelayed(::updateButton, 500)
    }

    private fun updateButton() {
        startButton.text = getString(if (VoiceService.isRunning) R.string.stop else R.string.start)
    }

    companion object {
        private const val PREFS = "monster_voice"
        private const val KEY_DEEP = "deep"
        private const val KEY_GROWL = "growl"
        private const val KEY_VOLUME = "volume"
        private const val KEY_ECHO = "echo"
        private const val KEY_MIC = "mic"
        private const val REQUEST_PERMISSIONS = 1
    }
}
