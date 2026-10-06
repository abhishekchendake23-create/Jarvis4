package com.abhynex.jarvis

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abhynex.jarvis.ApiException.Kind
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Core { IDLE, LISTENING, THINKING, SEARCHING, SPEAKING, ERROR }
enum class Screen { SPLASH, MAIN, API, SETTINGS, MEMORY, SECURITY, MOBILE }

class JarvisViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    val store = Store(ctx)
    var cfg by mutableStateOf(store.loadCfg()); private set
    var screen by mutableStateOf(Screen.SPLASH)
    val messages = mutableStateListOf<ChatMsg>()
    var core by mutableStateOf(Core.IDLE)
    var coreNote by mutableStateOf("")
    var partial by mutableStateOf("")
    var locked by mutableStateOf(false)
    var alert by mutableStateOf<String?>(null)
    var banner by mutableStateOf<String?>(null)
    var forceSearch by mutableStateOf(false)
    var wake by mutableStateOf(false)
    var micOn by mutableStateOf(false)
    var online by mutableStateOf(true)
    var statusTick by mutableStateOf(0)
    val busy = mutableStateMapOf<String, Boolean>()
    val testMsg = mutableStateMapOf<String, String>()

    // Hooks set by the Activity
    var requestMic: (() -> Unit)? = null
    var requestNotif: (() -> Unit)? = null
    var authRunner: ((String, () -> Unit, (Boolean, String) -> Unit) -> Unit)? = null

    private val voice = VoiceEngine(ctx)
    private val device = DeviceControl(ctx)
    private val alerts = Alerts(ctx)
    private var job: Job? = null
    private var wakeJob: Job? = null
    private var pendingWake = false
    private var voiceWarned = false
    private var lastAlert = 0L
    private var idc = System.currentTimeMillis()
    private fun nid() = idc++

    private val speech = SpeechInput(ctx, object : SpeechInput.Callbacks {
        override fun onListening(on: Boolean) {
            micOn = on
            if (on) core = Core.LISTENING else if (core == Core.LISTENING) core = Core.IDLE
        }

        override fun onPartial(t: String) { partial = t }
        override fun onFinal(t: String) { partial = ""; handleSpeech(t) }
        override fun onError(code: Int, msg: String?) {
            partial = ""
            micOn = false
            if (core == Core.LISTENING) core = Core.IDLE
            when (code) {
                9 -> { wake = false; addError("Microphone permission is required.") }
                2, 1 -> addError("Internet connection unavailable.")
                -1 -> { wake = false; addError(msg ?: "This feature is not supported on this device.") }
                else -> {}
            }
            if (wake) restartWakeSoon()
        }
    })

    init {
        if (cfg.memoryOn) messages.addAll(MemoryStore.load(ctx))
        refreshOnline()
    }

    // ---------- helpers ----------
    fun upd(block: (Cfg) -> Cfg) { cfg = block(cfg); store.saveCfg(cfg) }
    fun note(s: String) { banner = s }
    fun state(k: KeyKind): ConnState { statusTick; return store.connState(k) }
    fun langTag(): String? = when (cfg.language) {
        "ENGLISH" -> "en-IN"; "HINDI" -> "hi-IN"; "MARATHI" -> "mr-IN"; else -> null
    }

    fun refreshOnline() {
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun persist() { if (cfg.memoryOn) MemoryStore.save(ctx, messages.toList()) }
    private fun addError(text: String, action: String? = null) {
        messages.add(ChatMsg(nid(), "assistant", text, isError = true, action = action))
        core = Core.ERROR
        viewModelScope.launch { delay(1500); if (core == Core.ERROR) core = Core.IDLE }
    }

    // ---------- API keys ----------
    fun saveKey(k: KeyKind, v: String) {
        if (v.isBlank()) { testMsg[k.name] = "Enter a key first."; return }
        store.putKey(k, v)
        statusTick++
        testMsg[k.name] = "Saved securely. Tap TEST to verify."
    }

    fun deleteKey(k: KeyKind) { store.deleteKey(k); testMsg.remove(k.name); statusTick++ }

    private fun failed(k: KeyKind, e: ApiException) {
        if (e.kind == Kind.AUTH || e.kind == Kind.HTTP) store.setStatus(k, "FAILED")
        testMsg[k.name] = e.message ?: "Test failed."
    }

    fun testAi() {
        val k = store.getKey(KeyKind.AI) ?: run { testMsg["AI"] = "AI API is not configured."; return }
        busy["AI"] = true
        viewModelScope.launch {
            try {
                AiClient.chat(k, cfg.aiModel, "Reply with the single word OK.", listOf(Msg("user", "ping")), 16)
                store.setStatus(KeyKind.AI, "OK"); testMsg["AI"] = "Connected \u2713"
            } catch (e: ApiException) { failed(KeyKind.AI, e) }
            finally { busy["AI"] = false; statusTick++ }
        }
    }

    fun testVoice() {
        val k = store.getKey(KeyKind.VOICE) ?: run { testMsg["VOICE"] = "Inworld Voice is not configured."; return }
        busy["VOICE"] = true
        viewModelScope.launch {
            try {
                voice.test(k, cfg.inworldVoice, cfg.inworldModel)
                store.setStatus(KeyKind.VOICE, "OK"); testMsg["VOICE"] = "Connected \u2713 (you should have heard the voice)"
            } catch (e: ApiException) { failed(KeyKind.VOICE, e) }
            finally { busy["VOICE"] = false; statusTick++ }
        }
    }

    fun testSearch() {
        val k = store.getKey(KeyKind.SEARCH) ?: run { testMsg["SEARCH"] = "Live Search API is not configured."; return }
        busy["SEARCH"] = true
        viewModelScope.launch {
            try {
                val r = SearchClient.search(k, "ABHYNEX test", 1)
                store.setStatus(KeyKind.SEARCH, "OK"); testMsg["SEARCH"] = "Connected \u2713 (${r.size} result)"
            } catch (e: ApiException) { failed(KeyKind.SEARCH, e) }
            finally { busy["SEARCH"] = false; statusTick++ }
        }
    }

    // ---------- chat ----------
    private val currentRe = Regex("\\b(today|latest|news|current|currently|weather|scores?|prices?|recent|recently|2025|2026)\\b", RegexOption.IGNORE_CASE)
    private val currentDev = listOf("\u0906\u091C", "\u0924\u093E\u091C\u094D\u092F\u093E", "\u092C\u093E\u0924\u092E\u094D\u092F\u093E", "\u0916\u092C\u0930", "\u092E\u094C\u0938\u092E", "\u0939\u0935\u093E\u092E\u093E\u0928")
    private fun looksCurrent(p: String) = currentRe.containsMatchIn(p) || currentDev.any { p.contains(it) }

    fun send(text: String, viaVoice: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty()) return
        voice.stop()
        val pass = if (viaVoice && cfg.voiceVerify && cfg.ownerProtection) store.getSecret("pass") else null
        var shown = t
        var verified = true
        var forRouting = t
        if (pass != null) {
            verified = t.contains(pass, ignoreCase = true)
            shown = t.replace(pass, "\u2022\u2022\u2022", ignoreCase = true)
            forRouting = t.replace(pass, "", ignoreCase = true)
        }
        messages.add(ChatMsg(nid(), "user", shown))
        val body = CommandRouter.stripWake(forRouting)
        val cmd = CommandRouter.match(body)
        if (cmd != null) {
            if (!verified) {
                suspicious()
                addError("Voice verification failed. Include your owner passphrase with the command.")
                return
            }
            reply(device.run(cmd))
            return
        }
        askAi(body)
    }

    private fun reply(text: String, sources: List<Src> = emptyList()) {
        messages.add(ChatMsg(nid(), "assistant", text, sources))
        persist()
        if (cfg.autoSpeak) speakOut(text)
    }

    fun speakOut(text: String) {
        if (!cfg.voiceOn) return
        val key = store.getKey(KeyKind.VOICE)
        if (key == null && !voiceWarned) {
            voiceWarned = true
            addError("Inworld Voice is not configured. Using Android voice as fallback.", "API")
        }
        voice.speak(viewModelScope, key, cfg.inworldVoice, cfg.inworldModel, text, langTag(),
            onState = { s -> if (s) core = Core.SPEAKING else if (core == Core.SPEAKING) core = Core.IDLE },
            onInworld = { ok, msg ->
                store.setStatus(KeyKind.VOICE, if (ok) "OK" else "FAILED")
                statusTick++
                if (!ok) testMsg["VOICE"] = (msg ?: "Inworld Voice unavailable") + " \u2014 using Android voice."
            })
    }

    fun stopSpeaking() { voice.stop(); if (core == Core.SPEAKING) core = Core.IDLE }

    private fun askAi(prompt: String) {
        val key = store.getKey(KeyKind.AI)
        if (key == null) { addError("AI API is not configured.", "API"); return }
        refreshOnline()
        if (!online) { addError("Internet connection unavailable."); return }
        job?.cancel()
        job = viewModelScope.launch {
            core = Core.THINKING; coreNote = ""
            var sources = emptyList<Src>()
            var ctxText: String? = null
            var searchFailed = false
            val skey = store.getKey(KeyKind.SEARCH)
            val explicit = forceSearch
            forceSearch = false
            val want = explicit || (cfg.searchOn && skey != null && looksCurrent(prompt))
            if (want) {
                if (skey == null) {
                    if (explicit) { addError("Live Search API is not configured.", "SEARCH"); core = Core.IDLE; return@launch }
                } else if (!cfg.searchOn) {
                    if (explicit) { addError("Live Search is turned off in Settings.", "SEARCH"); core = Core.IDLE; return@launch }
                } else {
                    core = Core.SEARCHING; coreNote = "\uD83C\uDF10 Searching the web..."
                    try {
                        sources = SearchClient.search(skey, prompt)
                        store.setStatus(KeyKind.SEARCH, "OK"); statusTick++
                        ctxText = buildString {
                            append("LIVE WEB SEARCH RESULTS (retrieved just now):\n")
                            sources.forEachIndexed { i, s -> append("[${i + 1}] ${s.title}\n${s.snippet}\n${s.url}\n\n") }
                            append("Use these results to answer and cite them as [n]. If they do not answer the question, say so.\n\nQuestion: ")
                        }
                    } catch (e: ApiException) {
                        searchFailed = true
                        if (e.kind == Kind.AUTH) { store.setStatus(KeyKind.SEARCH, "FAILED"); statusTick++ }
                        addError("Live Search is currently unavailable.", "SEARCH")
                    }
                }
                core = Core.THINKING; coreNote = ""
            }
            val hist = buildHistory(ctxText?.let { it + prompt })
            val lang = when (cfg.language) { "ENGLISH" -> "English"; "HINDI" -> "Hindi"; "MARATHI" -> "Marathi"; else -> null }
            val system = buildString {
                append("You are ABHYNEX JARVIS, a personal AI assistant on the user's Android phone. ")
                append("Reply in the language the user writes in (English, Hindi or Marathi). ")
                if (lang != null) append("Always reply in $lang. ")
                append("Use Markdown (headings, lists, code blocks, tables) when helpful. Keep answers clear and reasonably concise, since they may be read aloud. ")
                append("Device actions are handled by the app, not by you; never claim you performed one. ")
                if (ctxText != null) append("Search results are provided in the user's message. ")
                else append("You have NO live web access in this reply: never present your own training knowledge as live or current information. ")
                if (searchFailed) append("Live search was attempted but failed; say you could not retrieve live information. ")
            }
            try {
                val out = AiClient.chat(key, cfg.aiModel, system, hist)
                store.setStatus(KeyKind.AI, "OK"); statusTick++
                reply(out, sources)
            } catch (e: ApiException) {
                if (e.kind == Kind.AUTH) { store.setStatus(KeyKind.AI, "FAILED"); statusTick++ }
                addError(e.message ?: "AI request failed.", if (e.kind == Kind.AUTH) "API" else null)
            } catch (e: Exception) {
                addError("AI request failed.")
            } finally {
                coreNote = ""
                if (core == Core.THINKING || core == Core.SEARCHING) core = Core.IDLE
            }
        }
    }

    private fun buildHistory(lastOverride: String?): List<Msg> {
        val raw = messages.filter { !it.isError }.takeLast(24).map { Msg(it.role, it.text) }.toMutableList()
        while (raw.isNotEmpty() && raw.first().role != "user") raw.removeAt(0)
        val merged = ArrayList<Msg>()
        for (m in raw) {
            if (merged.isNotEmpty() && merged.last().role == m.role) {
                merged[merged.size - 1] = Msg(m.role, merged.last().text + "\n\n" + m.text)
            } else merged.add(m)
        }
        if (lastOverride != null && merged.isNotEmpty() && merged.last().role == "user") {
            merged[merged.size - 1] = Msg("user", lastOverride)
        }
        return merged
    }

    fun regenerate(id: Long) {
        val i = messages.indexOfFirst { it.id == id }
        if (i < 0) return
        val u = messages.subList(0, i).lastOrNull { it.role == "user" } ?: return
        while (messages.size > i) messages.removeAt(messages.size - 1)
        messages.remove(u)
        send(u.text)
    }

    // ---------- microphone ----------
    private fun hasMic() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun toggleMic() {
        if (micOn || core == Core.LISTENING) { stopMic(); return }
        voice.stop()
        if (!hasMic()) { requestMic?.invoke(); return }
        startListening()
    }

    fun toggleWake() {
        if (wake) { stopMic(); return }
        voice.stop()
        if (!hasMic()) { pendingWake = true; requestMic?.invoke(); return }
        wake = true; startListening()
    }

    fun onMicPermission(granted: Boolean) {
        if (!granted) { pendingWake = false; addError("Microphone permission is required."); return }
        if (pendingWake) { pendingWake = false; wake = true }
        startListening()
    }

    private fun startListening() { core = Core.LISTENING; speech.start(langTag()) }

    fun stopMic() {
        wake = false; wakeJob?.cancel(); speech.stop(); micOn = false; partial = ""
        if (core == Core.LISTENING) core = Core.IDLE
    }

    private val wakeRe = Regex("(hey\\s+)?(abhynex|\u0905\u092D\u093F\u0928\u0947\u0915\u094D\u0938|\u0905\u092D\u093F\u0928\u0947\u0915\u094D\u0937)", RegexOption.IGNORE_CASE)

    private fun handleSpeech(raw: String) {
        if (wake) {
            val m = wakeRe.find(raw)
            if (m == null) { restartWakeSoon(); return }
            val rest = raw.substring(m.range.last + 1).trim(' ', ',', '.')
            if (rest.isBlank()) { reply("Yes?"); restartWakeSoon(); return }
            send(rest, viaVoice = true)
            restartWakeSoon()
        } else send(raw, viaVoice = true)
    }

    private fun restartWakeSoon() {
        if (!wake) return
        wakeJob?.cancel()
        wakeJob = viewModelScope.launch {
            delay(600)
            while (wake && (core == Core.THINKING || core == Core.SEARCHING || core == Core.SPEAKING)) delay(400)
            if (wake && !micOn) startListening()
        }
    }

    // ---------- security ----------
    fun requireAuth(title: String, action: () -> Unit) {
        val runner = authRunner
        if (runner == null) { note("This feature is not supported on this device."); return }
        runner(title, action) { susp, msg -> if (susp) suspicious() else note("Not confirmed: $msg") }
    }

    /** Sensitive actions: biometric/PIN confirmation unless the owner turned confirmation off. */
    fun sensitive(title: String, action: () -> Unit) { if (cfg.bioConfirm) requireAuth(title, action) else action() }

    fun suspicious() {
        if (!cfg.alertOn) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAlert < 10_000) return
        lastAlert = now
        alert = "Unauthorized access detected."
        if (cfg.notifications) alerts.notify("ABHYNEX SECURITY ALERT", "Unauthorized access detected.")
        if (cfg.alarmOn) alerts.startAlarm()
        else if (cfg.voiceOn) voice.speak(viewModelScope, store.getKey(KeyKind.VOICE), cfg.inworldVoice, cfg.inworldModel,
            "Security alert. Unauthorized access detected.", langTag(), {}, { _, _ -> })
    }

    fun dismissAlert() { alert = null; alerts.stopAlarm(); voice.stop() }

    fun tryUnlock() {
        val runner = authRunner ?: return
        runner("Unlock ABHYNEX JARVIS", { locked = false }) { susp, msg -> if (susp) suspicious() else note(msg) }
    }

    fun setOwnerProtection(on: Boolean) =
        requireAuth(if (on) "Enable Owner Protection" else "Disable Owner Protection") { upd { it.copy(ownerProtection = on) } }

    fun setBioConfirm(on: Boolean) {
        if (on) upd { it.copy(bioConfirm = true) } else requireAuth("Turn off biometric confirmation") { upd { it.copy(bioConfirm = false) } }
    }

    fun setVoiceVerify(on: Boolean) {
        if (on && store.getSecret("pass") == null) { note("Set an owner passphrase first."); return }
        sensitive("Change security settings") { upd { it.copy(voiceVerify = on) } }
    }

    fun setAlert(on: Boolean) = sensitive("Change security settings") { upd { it.copy(alertOn = on) } }
    fun setAlarm(on: Boolean) = sensitive("Change security settings") { upd { it.copy(alarmOn = on) } }

    fun savePassphrase(p: String) {
        if (p.trim().length < 4) { note("Passphrase must be at least 4 characters."); return }
        sensitive("Change security settings") { store.putSecret("pass", p.trim()); note("Passphrase saved.") }
    }

    fun hasPassphrase() = store.getSecret("pass") != null

    fun setNotifications(on: Boolean) {
        if (on) requestNotif?.invoke() else upd { it.copy(notifications = false) }
    }

    fun clearMemory() = sensitive("Delete memory") {
        MemoryStore.clear(ctx); messages.clear(); note("Memory cleared.")
    }

    fun runCmd(c: Cmd) { note(device.run(c)) }

    override fun onCleared() {
        speech.stop(); voice.shutdown(); alerts.stopAlarm()
    }
}
