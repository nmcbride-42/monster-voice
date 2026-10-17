package com.nmcbride.monstervoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Foreground service that keeps the voice running when the screen turns off
 * or the phone goes in a pocket. Also listens for shakes to trigger a roar.
 */
class VoiceService : Service(), SensorEventListener {

    private val engine = AudioEngine(voice)
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorManager: SensorManager? = null
    private var lastShake = 0L
    private var roarSeed = 100

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MonsterVoice:live").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L)
            }
        }

        if (sensorManager == null) {
            sensorManager = (getSystemService(Context.SENSOR_SERVICE) as SensorManager).also { sm ->
                sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                    sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
            }
        }

        engine.start()
        current = this
        isRunning = true
        return START_NOT_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!shakeEnabled) return
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val force = abs(sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH)
        val now = SystemClock.elapsedRealtime()
        if (force > SHAKE_FORCE && now - lastShake > SHAKE_COOLDOWN_MS) {
            lastShake = now
            voice.playSound(RoarSynth.create(AudioEngine.SAMPLE_RATE, roarSeed++))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        engine.stop()
        current = null
        wakeLock?.release()
        wakeLock = null
        isRunning = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VoiceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    companion object {
        /** Shared with MainActivity so the sliders change the sound instantly. */
        val voice = MonsterVoice(AudioEngine.SAMPLE_RATE)

        @Volatile var isRunning = false
            private set

        @Volatile var shakeEnabled = false

        @Volatile private var current: VoiceService? = null

        /** Moves the live voice to the newly chosen speaker. */
        fun reroute() {
            current?.engine?.reroute()
        }

        private const val SHAKE_FORCE = 14f
        private const val SHAKE_COOLDOWN_MS = 1800L

        const val ACTION_STOP = "com.nmcbride.monstervoice.STOP"
        private const val CHANNEL_ID = "monster_voice"
        private const val NOTIFICATION_ID = 1
    }
}
