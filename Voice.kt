package com.abhynex.jarvis

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import com.abhynex.jarvis.ApiException.Kind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Voice output. Primary: Inworld TTS (POST https://api.inworld.ai/tts/v1/voice, "Authorization: Basic <key>").
 * Text is split into sentence chunks; chunk N+1 is synthesized while chunk N plays (low perceived latency).
 * Fallback: Android TextToSpeech.
 */
class VoiceEngine(private val app: Context) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null
    private var job: Job? = null

    init {
        tts = TextToSpeech(app) { ttsReady = it == TextToSpeech.SUCCESS }
    }

    fun stop() {
        job?.cancel()
        job = null
        tts?.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
    }

    private suspend fun inworld(key: String, voice: String, model: String, text: String): File =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("text", text).put("voiceId", voice).put("modelId", model).toString()
            val (code, bytes) = Http.request(
                "POST", "https://api.inworld.ai/tts/v1/voice",
                mapOf("Authorization" to "Basic $key", "Content-Type" to "application/json"), body
            )
            val s = String(bytes, Charsets.UTF_8)
            if (code == 401 || code == 403) throw ApiException(Kind.AUTH, "Inworld authentication failed.")
            if (code !in 200..299) throw ApiException(Kind.HTTP, Http.errorMessage(s) ?: "Inworld Voice is unavailable ($code).")
            val b64 = try {
                JSONObject(s).getString("audioContent")
            } catch (e: Exception) {
                throw ApiException(Kind.PARSE, "Unexpected Inworld response.")
            }
            val f = File.createTempFile("tts", ".audio", app.cacheDir)
            f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
            f
        }

    /** Used by TEST VOICE. Throws ApiException on failure. */
    suspend fun test(key: String, voice: String, model: String) {
        val f = inworld(key, voice, model, "ABHYNEX voice online.")
        try {
            play(f)
        } finally {
            f.delete()
        }
    }

    fun speak(
        scope: CoroutineScope, key: String?, voice: String, model: String, text: String, langTag: String?,
        onState: (Boolean) -> Unit, onInworld: (Boolean, String?) -> Unit
    ) {
        stop()
        val chunks = chunk(clean(text))
        if (chunks.isEmpty()) return
        job = scope.launch {
            onState(true)
            try {
                var fallbackFrom = 0
                if (key != null) {
                    supervisorScope {
                        var next: Deferred<File>? = async { inworld(key, voice, model, chunks[0]) }
                        for (i in chunks.indices) {
                            val f = try {
                                next!!.await()
                            } catch (e: ApiException) {
                                onInworld(false, e.message)
                                fallbackFrom = i
                                null
                            }
                            if (f == null) break
                            next = if (i + 1 < chunks.size) async { inworld(key, voice, model, chunks[i + 1]) } else null
                            fallbackFrom = i + 1
                            if (i == 0) onInworld(true, null)
                            try {
                                play(f)
                            } finally {
                                f.delete()
                            }
                        }
                        next?.cancel()
                    }
                }
                if (key == null || fallbackFrom < chunks.size) {
                    for (c in chunks.drop(fallbackFrom)) speakAndroid(c, langTag)
                }
            } finally {
                onState(false)
            }
        }
    }

    private suspend fun play(f: File) = suspendCancellableCoroutine<Unit> { cont ->
        val mp = MediaPlayer()
        player = mp
        fun done() {
            runCatching { mp.release() }
            if (player === mp) player = null
            if (cont.isActive) cont.resume(Unit)
        }
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            FileInputStream(f).use { mp.setDataSource(it.fd) }
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { done() }
            mp.setOnErrorListener { _, _, _ -> done(); true }
            mp.prepareAsync()
        } catch (e: Exception) {
            done()
        }
        cont.invokeOnCancellation {
            runCatching { mp.stop() }
            runCatching { mp.release() }
            if (player === mp) player = null
        }
    }

    private suspend fun speakAndroid(text: String, langTag: String?) {
        val t = tts
        if (t == null || !ttsReady || text.isBlank()) return
        if (langTag != null) runCatching { t.language = Locale.forLanguageTag(langTag) }
        suspendCancellableCoroutine<Unit> { cont ->
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }
            })
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u${System.nanoTime()}")
            cont.invokeOnCancellation { t.stop() }
        }
    }

    private fun clean(t: String): String = t
        .replace(Regex("```[\\s\\S]*?```"), " code block omitted. ")
        .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "\$1")
        .replace(Regex("[*`#>|]+"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun chunk(t: String): List<String> {
        val parts = t.split(Regex("(?<=[.!?\u0964])\\s+")).filter { it.isNotBlank() }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotBlank()) out.add(cur.toString().trim())
            cur.clear()
        }
        parts.forEachIndexed { i, p ->
            if (i == 0) out.addAll(p.chunked(350))
            else if (p.length > 350) { flush(); out.addAll(p.chunked(350)) }
            else { if (cur.length + p.length > 350) flush(); cur.append(p).append(' ') }
        }
        flush()
        return out
    }
}

/** Visible, user-triggered speech recognition (Android SpeechRecognizer). Never records in the background. */
class SpeechInput(private val ctx: Context, private val cb: Callbacks) {
    interface Callbacks {
        fun onListening(on: Boolean)
        fun onPartial(t: String)
        fun onFinal(t: String)
        fun onError(code: Int, msg: String?)
    }

    private var rec: SpeechRecognizer? = null

    fun start(langTag: String?) {
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            cb.onError(-1, "This feature is not supported on this device.")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = cb.onListening(true)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(cb::onPartial)
            }

            override fun onResults(results: Bundle?) {
                cb.onListening(false)
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                finish()
                if (t.isNullOrBlank()) cb.onError(SpeechRecognizer.ERROR_NO_MATCH, null) else cb.onFinal(t)
            }

            override fun onError(error: Int) {
                cb.onListening(false)
                finish()
                cb.onError(error, null)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        if (langTag != null) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
        r.startListening(i)
    }

    private fun finish() {
        rec?.let { runCatching { it.destroy() } }
        rec = null
    }

    fun stop() {
        rec?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        rec = null
    }
}
