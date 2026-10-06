package com.abhynex.jarvis

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class KeyKind { AI, VOICE, SEARCH }
enum class ConnState { NOT_CONFIGURED, UNTESTED, CONNECTED, FAILED }

data class Src(val title: String, val url: String, val snippet: String)
data class Msg(val role: String, val text: String)
data class ChatMsg(
    val id: Long,
    val role: String,
    val text: String,
    val sources: List<Src> = emptyList(),
    val isError: Boolean = false,
    val action: String? = null
)

data class Cfg(
    val aiModel: String = "claude-sonnet-5-5",
    val inworldVoice: String = "Ashley",
    val inworldModel: String = "inworld-tts-1.5-max",
    val voiceOn: Boolean = true,
    val autoSpeak: Boolean = true,
    val searchOn: Boolean = true,
    val language: String = "AUTO",
    val memoryOn: Boolean = true,
    val ownerProtection: Boolean = false,
    val voiceVerify: Boolean = false,
    val bioConfirm: Boolean = true,
    val alertOn: Boolean = true,
    val alarmOn: Boolean = false,
    val notifications: Boolean = false,
    val accent: String = "CYAN"
)

class ApiException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { AUTH, OFFLINE, HTTP, PARSE }
}

/** AES-256-GCM using a non-exportable key kept in the Android Keystore. */
object Crypto {
    private const val ALIAS = "abhynex_master_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey()
    }

    fun enc(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun dec(s: String): String? = try {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("abhynex_prefs", Context.MODE_PRIVATE)

    fun getKey(k: KeyKind): String? = sp.getString("sec_${k.name}", null)?.let { Crypto.dec(it) }
    fun putKey(k: KeyKind, v: String) {
        sp.edit().putString("sec_${k.name}", Crypto.enc(v.trim())).putString("st_${k.name}", "UNTESTED").apply()
    }
    fun deleteKey(k: KeyKind) {
        sp.edit().remove("sec_${k.name}").remove("st_${k.name}").apply()
    }
    fun setStatus(k: KeyKind, s: String) = sp.edit().putString("st_${k.name}", s).apply()
    fun connState(k: KeyKind): ConnState {
        if (getKey(k) == null) return ConnState.NOT_CONFIGURED
        return when (sp.getString("st_${k.name}", "UNTESTED")) {
            "OK" -> ConnState.CONNECTED
            "FAILED" -> ConnState.FAILED
            else -> ConnState.UNTESTED
        }
    }

    fun getSecret(name: String): String? = sp.getString("secret_$name", null)?.let { Crypto.dec(it) }
    fun putSecret(name: String, v: String) = sp.edit().putString("secret_$name", Crypto.enc(v)).apply()

    fun loadCfg(): Cfg {
        val d = Cfg()
        return Cfg(
            aiModel = sp.getString("aiModel", d.aiModel)!!,
            inworldVoice = sp.getString("inworldVoice", d.inworldVoice)!!,
            inworldModel = sp.getString("inworldModel", d.inworldModel)!!,
            voiceOn = sp.getBoolean("voiceOn", d.voiceOn),
            autoSpeak = sp.getBoolean("autoSpeak", d.autoSpeak),
            searchOn = sp.getBoolean("searchOn", d.searchOn),
            language = sp.getString("language", d.language)!!,
            memoryOn = sp.getBoolean("memoryOn", d.memoryOn),
            ownerProtection = sp.getBoolean("ownerProtection", d.ownerProtection),
            voiceVerify = sp.getBoolean("voiceVerify", d.voiceVerify),
            bioConfirm = sp.getBoolean("bioConfirm", d.bioConfirm),
            alertOn = sp.getBoolean("alertOn", d.alertOn),
            alarmOn = sp.getBoolean("alarmOn", d.alarmOn),
            notifications = sp.getBoolean("notifications", d.notifications),
            accent = sp.getString("accent", d.accent)!!
        )
    }

    fun saveCfg(c: Cfg) {
        sp.edit()
            .putString("aiModel", c.aiModel).putString("inworldVoice", c.inworldVoice)
            .putString("inworldModel", c.inworldModel).putBoolean("voiceOn", c.voiceOn)
            .putBoolean("autoSpeak", c.autoSpeak).putBoolean("searchOn", c.searchOn)
            .putString("language", c.language).putBoolean("memoryOn", c.memoryOn)
            .putBoolean("ownerProtection", c.ownerProtection).putBoolean("voiceVerify", c.voiceVerify)
            .putBoolean("bioConfirm", c.bioConfirm).putBoolean("alertOn", c.alertOn)
            .putBoolean("alarmOn", c.alarmOn).putBoolean("notifications", c.notifications)
            .putString("accent", c.accent).apply()
    }
}

/** Encrypted local chat memory. Swap this object for SQLite/Supabase later. */
object MemoryStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "memory.enc")

    fun load(ctx: Context): List<ChatMsg> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val s = Crypto.dec(f.readText()) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                ChatMsg(o.getLong("id"), o.getString("role"), o.getString("text"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, msgs: List<ChatMsg>) {
        val a = JSONArray()
        msgs.filter { !it.isError }.takeLast(100).forEach {
            a.put(JSONObject().put("id", it.id).put("role", it.role).put("text", it.text))
        }
        file(ctx).writeText(Crypto.enc(a.toString()))
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}

object Http {
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 60000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return code to (stream?.use { it.readBytes() } ?: ByteArray(0))
        } catch (e: IOException) {
            throw ApiException(ApiException.Kind.OFFLINE, "Internet connection unavailable.")
        } finally {
            c.disconnect()
        }
    }

    fun errorMessage(body: String): String? = try {
        val o = JSONObject(body)
        o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: o.optString("message").takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
}
