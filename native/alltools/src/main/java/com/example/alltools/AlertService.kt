package com.example.alltools

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.*
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.*

const val ACTION_SPEAK = "com.example.alltools.SPEAK"

class AlertService : Service(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var busy = false
    private var bellDone = false
    private var active = 0
    private val pending = mutableListOf<Pair<String, Int>>()
    private val handler = Handler(Looper.getMainLooper())
    private var fullSpoken = false

    // Low battery: spoken 3 times ONCE per low period. Reset only after the level is back above the set level.
    private var lowAnnounced = false
    private var dot: View? = null

    private val prefs by lazy { getSharedPreferences("s", 0) }

    private val audioAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            check(i)
        }
    }

    // If the user changes the low level or the switches, re-evaluate straight away.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "low" || key == "lowOn" || key == "full") recheck()
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("alerts", "Voice alerts", NotificationManager.IMPORTANCE_MIN))
        val n = NotificationCompat.Builder(this, "alerts")
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("Voice Battery")
            .setContentText("Watching battery, alarms and tasks")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true).build()
        ServiceCompat.startForeground(
            this, 1, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        ContextCompat.registerReceiver(
            this, batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun recheck() {
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { check(it) }
    }

    private fun check(i: Intent) {
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / scale
        if (level < 0) return
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val low = prefs.getInt("low", 50)
        val lowOn = prefs.getBoolean("lowOn", true)

        // Full charge: spoken once per charge (unchanged behaviour)
        if (plugged) {
            if (level >= 100 && !fullSpoken && prefs.getBoolean("full", true)) {
                fullSpoken = true
                say("Battery charge completed. Charge completed. Unplug your charger.")
            }
        } else {
            fullSpoken = false
        }

        // Low battery: small red circle while below the set level, voice 3 times only.
        if (lowOn && level < low) {
            showDot()
            if (!plugged && !lowAnnounced) {
                lowAnnounced = true
                say("Battery is below $low percent. Please put your phone on charge.", 3)
            }
        } else {
            hideDot()
            if (level >= low) lowAnnounced = false
        }
    }

    // ---- Small red circle ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showDot() {
        if (dot != null) return
        if (!Settings.canDrawOverlays(this)) return   // permission "Display over other apps" not given yet
        try {
            val size = dp(14)
            val v = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.RED)
                    setStroke(dp(1), Color.WHITE)
                }
            }
            val lp = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(10)
                y = dp(28)
            }
            getSystemService(WindowManager::class.java).addView(v, lp)
            dot = v
        } catch (e: Exception) {
            dot = null
        }
    }

    private fun hideDot() {
        dot?.let {
            try {
                getSystemService(WindowManager::class.java).removeView(it)
            } catch (e: Exception) {
            }
        }
        dot = null
    }

    // ---- Bells + speech ----

    /** Plays 3 bell sounds; returns how long they last in milliseconds. */
    private fun playBells(): Long {
        val sr = 22050
        val n = sr * 900 / 1000
        val data = ShortArray(n * 3)
        for (b in 0 until 3) {
            for (k in 0 until n) {
                val t = k / sr.toDouble()
                val env = Math.exp(-4.0 * t)
                val v = (Math.sin(2 * Math.PI * 880 * t) * 0.6 +
                        Math.sin(2 * Math.PI * 1760 * t) * 0.3 +
                        Math.sin(2 * Math.PI * 2640 * t) * 0.1) * env
                data[b * n + k] = (v * Short.MAX_VALUE * 0.8).toInt().toShort()
            }
        }
        val totalMs = 3L * 900
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC).build()
            track.write(data, 0, data.size)
            track.play()
            handler.postDelayed({ track.release() }, totalMs + 400)
        } catch (e: Exception) {
            return 0L
        }
        return totalMs
    }

    private fun say(text: String, times: Int = 1) {
        pending.add(text to times)
        if (!busy) {
            busy = true
            bellDone = false
            tts = TextToSpeech(this, this)
            val bells = prefs.getBoolean("bells", true)
            val ms = if (bells) playBells() + 300 else 0L
            handler.postDelayed({
                bellDone = true
                flush()
            }, ms)
        } else {
            flush()
        }
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            tts = null
            pending.clear()
            busy = false
            return
        }
        t.language = Locale.getDefault()
        t.setAudioAttributes(audioAttrs)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
            }

            override fun onDone(id: String?) {
                finished()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                finished()
            }
        })
        ready = true
        flush()
    }

    private fun flush() {
        val t = tts ?: return
        if (!ready || !bellDone) return
        val rate = prefs.getInt("rate", 100) / 100f
        t.setSpeechRate(rate)
        t.setPitch(1.0f)
        pending.forEach { (text, times) ->
            repeat(times) {
                active++
                t.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
            }
        }
        pending.clear()
    }

    private fun finished() {
        handler.post {
            active--
            if (active <= 0 && pending.isEmpty()) {
                active = 0
                tts?.shutdown()
                tts = null
                ready = false
                busy = false
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SPEAK) {
            say(intent.getStringExtra("text") ?: "", intent.getIntExtra("times", 1))
        } else {
            // Started again (app opened, permission just granted...): re-check the battery state.
            recheck()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        unregisterReceiver(batteryReceiver)
        hideDot()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
