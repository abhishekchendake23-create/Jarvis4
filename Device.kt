package com.abhynex.jarvis

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Fixed allowlist. The AI never produces commands; nothing outside this enum can run. */
enum class Cmd(val label: String, val example: String) {
    OPEN_CAMERA("Open camera", "open camera"),
    OPEN_PHONE("Open phone", "open phone"),
    OPEN_SETTINGS("Open settings", "open settings"),
    OPEN_WIFI_SETTINGS("Open Wi-Fi settings", "open Wi-Fi settings"),
    OPEN_BLUETOOTH_SETTINGS("Open Bluetooth settings", "open Bluetooth settings"),
    OPEN_BATTERY_SETTINGS("Open battery settings", "open battery settings"),
    OPEN_NOTIFICATION_SETTINGS("Open notification settings", "open notification settings"),
    VOLUME_UP("Increase volume", "increase volume"),
    VOLUME_DOWN("Decrease volume", "decrease volume"),
    MEDIA_PLAY("Play media", "play media"),
    MEDIA_PAUSE("Pause media", "pause media"),
    BATTERY_STATUS("Check battery", "check battery"),
    VIBRATION_ON("Turn vibration on", "turn vibration on")
}

object CommandRouter {
    private val wakeToks = setOf("hey", "abhynex", "\u0905\u092D\u093F\u0928\u0947\u0915\u094D\u0938", "\u0905\u092D\u093F\u0928\u0947\u0915\u094D\u0937")
    private val openW = listOf("open", "launch", "start", "kar", "karo", "khol", "kholo", "dikhao", "\u0909\u0918\u0921", "\u0916\u094B\u0932", "\u091A\u093E\u0932\u0942", "\u0926\u093E\u0916\u0935")
    private val settingsW = listOf("settings", "setting", "\u0938\u0947\u091F\u093F\u0902\u0917", "\u0938\u0947\u091F\u093F\u0902\u0917\u094D\u091C", "\u0938\u0947\u091F\u093F\u0902\u0917\u094D\u0938")
    private val wifiW = listOf("wifi", "wi-fi", "\u0935\u093E\u092F\u092B\u093E\u092F", "\u0935\u093E\u0908\u092B\u093E\u0908", "\u0935\u093E\u092F-\u092B\u093E\u092F")
    private val btW = listOf("bluetooth", "\u092C\u094D\u0932\u0942\u091F\u0942\u0925", "\u092C\u094D\u0932\u0941\u091F\u0942\u0925")
    private val batW = listOf("battery", "\u092C\u0945\u091F\u0930\u0940", "\u092C\u0948\u091F\u0930\u0940")
    private val checkW = listOf("check", "status", "level", "percent", "percentage", "kitni", "kiti", "\u0915\u093F\u0924\u0940", "\u0915\u093F\u0924\u0928\u0940", "\u091F\u0915\u094D\u0915\u0947", "charge", "\u091A\u093E\u0930\u094D\u091C")
    private val notifW = listOf("notification", "notifications", "\u0928\u094B\u091F\u093F\u092B\u093F\u0915\u0947\u0936\u0928")
    private val volW = listOf("volume", "awaz", "avaz", "\u0906\u0935\u093E\u091C", "\u0906\u0935\u093E\u095B", "\u0935\u094D\u0939\u0949\u0932\u094D\u092F\u0942\u092E", "\u0935\u0949\u0932\u094D\u092F\u0942\u092E")
    private val upW = listOf("increase", "up", "louder", "raise", "badha", "vadhav", "zyada", "\u0935\u093E\u0922\u0935", "\u092C\u0922\u093C\u093E", "\u092C\u095D\u093E")
    private val downW = listOf("decrease", "down", "lower", "reduce", "kam", "ghata", "ghatav", "\u0915\u092E\u0940", "\u0915\u092E", "\u0918\u091F\u0935", "\u0918\u091F\u093E")
    private val vibW = listOf("vibration", "vibrate", "\u0935\u094D\u0939\u093E\u092F\u092C\u094D\u0930\u0947\u0936\u0928", "\u0935\u093E\u0907\u092C\u094D\u0930\u0947\u0936\u0928")
    private val onW = listOf("on", "start", "chalu", "\u091A\u093E\u0932\u0942", "\u0938\u0941\u0930\u0942")
    private val mediaW = listOf("media", "music", "song", "gana", "gaana", "\u0917\u093E\u0923\u0947", "\u0917\u093E\u0923\u0902", "\u0917\u093E\u0928\u093E", "\u0938\u0902\u0917\u0940\u0924", "\u092E\u0940\u0921\u093F\u092F\u093E", "\u092E\u094D\u092F\u0941\u091D\u093F\u0915", "\u092E\u094D\u092F\u0942\u091C\u093C\u093F\u0915")
    private val playW = listOf("play", "resume", "chala", "baja", "\u091A\u093E\u0932\u0935", "\u091A\u0932\u093E", "\u092C\u091C\u093E\u0913", "\u0932\u093E\u0935")
    private val pauseW = listOf("pause", "stop", "band", "thamb", "\u0925\u093E\u0902\u092C\u0935", "\u0930\u094B\u0915", "\u092C\u0902\u0926")
    private val camW = listOf("camera", "\u0915\u0945\u092E\u0947\u0930\u093E", "\u0915\u0948\u092E\u0930\u093E", "\u0915\u0948\u092E\u0947\u0930\u093E")
    private val phoneW = listOf("phone", "dialer", "dial", "\u092B\u094B\u0928", "\u092B\u093C\u094B\u0928")

    private fun tokens(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.isNotEmpty() }
    private fun has(t: List<String>, kws: List<String>) =
        t.any { tok -> kws.any { k -> if (k.length <= 2) tok == k else tok.startsWith(k) } }

    fun stripWake(s: String): String =
        s.replace(Regex("^\\s*(hey\\s+)?(abhynex|\u0905\u092D\u093F\u0928\u0947\u0915\u094D\u0938|\u0905\u092D\u093F\u0928\u0947\u0915\u094D\u0937)[,\\s]*", RegexOption.IGNORE_CASE), "").ifBlank { s }

    /** Returns an allowlisted command or null (null = normal question for the AI). Short phrases only. */
    fun match(text: String): Cmd? {
        val t = tokens(text).filter { it !in wakeToks }
        if (t.isEmpty() || t.size > 6) return null
        val open = has(t, openW)
        val sett = has(t, settingsW)
        return when {
            has(t, wifiW) && (open || sett) -> Cmd.OPEN_WIFI_SETTINGS
            has(t, btW) && (open || sett) -> Cmd.OPEN_BLUETOOTH_SETTINGS
            has(t, batW) && sett -> Cmd.OPEN_BATTERY_SETTINGS
            has(t, batW) && (has(t, checkW) || t.size <= 3) -> Cmd.BATTERY_STATUS
            has(t, notifW) && sett -> Cmd.OPEN_NOTIFICATION_SETTINGS
            has(t, volW) && has(t, upW) -> Cmd.VOLUME_UP
            has(t, volW) && has(t, downW) -> Cmd.VOLUME_DOWN
            has(t, vibW) && (has(t, onW) || open) -> Cmd.VIBRATION_ON
            has(t, mediaW) && has(t, pauseW) -> Cmd.MEDIA_PAUSE
            has(t, mediaW) && has(t, playW) -> Cmd.MEDIA_PLAY
            has(t, camW) && open -> Cmd.OPEN_CAMERA
            has(t, phoneW) && open -> Cmd.OPEN_PHONE
            sett && open -> Cmd.OPEN_SETTINGS
            else -> null
        }
    }
}

class DeviceControl(private val app: Context) {
    private fun go(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(i)
    }

    fun run(cmd: Cmd): String = try {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (cmd) {
            Cmd.OPEN_CAMERA -> { go(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)); "Opening camera." }
            Cmd.OPEN_PHONE -> { go(Intent(Intent.ACTION_DIAL)); "Opening phone." }
            Cmd.OPEN_SETTINGS -> { go(Intent(Settings.ACTION_SETTINGS)); "Opening settings." }
            Cmd.OPEN_WIFI_SETTINGS -> { go(Intent(Settings.ACTION_WIFI_SETTINGS)); "Opening Wi-Fi settings." }
            Cmd.OPEN_BLUETOOTH_SETTINGS -> { go(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); "Opening Bluetooth settings." }
            Cmd.OPEN_BATTERY_SETTINGS -> { go(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)); "Opening battery settings." }
            Cmd.OPEN_NOTIFICATION_SETTINGS -> {
                go(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName))
                "Opening notification settings."
            }
            Cmd.VOLUME_UP -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); "Volume increased." }
            Cmd.VOLUME_DOWN -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); "Volume decreased." }
            Cmd.MEDIA_PLAY -> { mediaKey(am, KeyEvent.KEYCODE_MEDIA_PLAY); "Play command sent to the active media app." }
            Cmd.MEDIA_PAUSE -> { mediaKey(am, KeyEvent.KEYCODE_MEDIA_PAUSE); "Pause command sent to the active media app." }
            Cmd.BATTERY_STATUS -> battery()
            Cmd.VIBRATION_ON -> {
                try {
                    am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    "Vibration mode is on."
                } catch (e: SecurityException) {
                    go(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    "Requires permission: allow Do Not Disturb access for ABHYNEX JARVIS, then try again."
                }
            }
        }
    } catch (e: ActivityNotFoundException) {
        "This feature is not supported on this device."
    } catch (e: SecurityException) {
        "This action requires a permission that has not been granted."
    }

    private fun mediaKey(am: AudioManager, code: Int) {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun battery(): String {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "This feature is not supported on this device."
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        return "Battery is at ${level * 100 / scale}%" + if (charging) " and charging." else "."
    }
}

class Alerts(private val app: Context) {
    private var alarm: MediaPlayer? = null

    fun notify(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("security", "Security alerts", NotificationManager.IMPORTANCE_HIGH))
        val n = NotificationCompat.Builder(app, "security")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title).setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
        nm.notify(1001, n)
    }

    fun startAlarm() {
        if (alarm != null) return
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            alarm = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                setDataSource(app, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            alarm = null
        }
    }

    fun stopAlarm() {
        alarm?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        alarm = null
    }
}

object BiometricGate {
    private const val AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun available(a: FragmentActivity) =
        BiometricManager.from(a).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    /** onFail(suspicious, message): suspicious = repeated failures or lockout (not a plain user cancel). */
    fun prompt(a: FragmentActivity, title: String, onOk: () -> Unit, onFail: (Boolean, String) -> Unit) {
        var fails = 0
        val p = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            override fun onAuthenticationFailed() {
                fails++
                if (fails >= 3) onFail(true, "Too many failed attempts.")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val lock = errorCode == BiometricPrompt.ERROR_LOCKOUT || errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT
                onFail(lock || fails >= 3, errString.toString())
            }
        })
        p.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(title)
                .setSubtitle("ABHYNEX owner confirmation").setAllowedAuthenticators(AUTH).build()
        )
    }
}
