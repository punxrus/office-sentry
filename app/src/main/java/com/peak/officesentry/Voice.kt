package com.peak.officesentry

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Text-to-speech (goes to whatever audio output is active, e.g. a paired
 * Bluetooth speaker) and speech-to-text via Android's SpeechRecognizer.
 * Must be created and used on the main thread.
 */
class Voice(private val ctx: Context, private val prefs: Prefs) {
    data class VoiceInfo(val name: String, val label: String)

    data class EngineInfo(val name: String, val label: String)

    /** Voices offered by the current engine (English first). Thread-safe snapshot. */
    @Volatile var available: List<VoiceInfo> = emptyList(); private set
    @Volatile var engines: List<EngineInfo> = emptyList(); private set
    @Volatile var currentEngine: String = ""; private set

    private fun loadVoices() {
        available = runCatching {
            val all = tts.voices.orEmpty().filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            val english = all.filter { v ->
                v.locale.language.startsWith("en") || runCatching { v.locale.isO3Language == "eng" }.getOrDefault(false)
            }
            (english.ifEmpty { all })
                .sortedWith(compareBy({ it.locale.country != "US" }, { -it.quality }, { it.name }))
                .map { v ->
                    val where = if (v.isNetworkConnectionRequired) "online" else "offline"
                    val country = v.locale.displayCountry.ifBlank { v.locale.displayLanguage }
                    VoiceInfo(v.name, "$country \u2022 ${v.name} ($where)")
                }
        }.getOrDefault(emptyList())
        engines = runCatching { tts.engines.map { EngineInfo(it.name, it.label) } }.getOrDefault(emptyList())
        currentEngine = runCatching { tts.defaultEngine }.getOrDefault("")
    }

    fun voiceList(): List<VoiceInfo> {
        if (available.isEmpty() && ready) loadVoices()
        return available
    }

    /** Apply the saved voice / pitch / speed. Called before every utterance. */
    private fun applySettings() {
        tts.setPitch(prefs.pitch)
        tts.setSpeechRate(prefs.rate)
        val name = prefs.voiceName
        if (name.isNotBlank()) {
            runCatching { tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it } }
        }
    }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    @Volatile private var ready = false
    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null

    init { buildTts() }

    /**
     * (Re)create the TTS client on the engine chosen in [Prefs.ttsEngine]
     * (blank = the phone's default engine). We deliberately do NOT force a
     * language, so the voice chosen in Android settings / the engine is kept.
     */
    fun buildTts() {
        ready = false
        if (::tts.isInitialized) runCatching { tts.stop(); tts.shutdown() }
        val engine = prefs.ttsEngine.ifBlank { null }
        tts = TextToSpeech(ctx, { st ->
            if (st == TextToSpeech.SUCCESS) {
                // Only fall back to US English if the engine has no usable default voice.
                if (runCatching { tts.voice }.getOrNull() == null) runCatching { tts.language = Locale.US }
                loadVoices()
                if (engine != null) currentEngine = engine
                ready = true
            }
        }, engine)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { id?.let { pending.remove(it)?.complete(Unit) } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { id?.let { pending.remove(it)?.complete(Unit) } }
            override fun onError(id: String?, errorCode: Int) { id?.let { pending.remove(it)?.complete(Unit) } }
        })
    }

    val canListen: Boolean get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    /** Speak and suspend until finished (or a safety timeout). */
    suspend fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) { withTimeoutOrNull(3000) { while (!ready) kotlinx.coroutines.delay(100) } }
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        applySettings()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        withTimeoutOrNull(30_000L + text.length * 120L) { done.await() }
        pending.remove(id)
    }

    fun stopSpeaking() = tts.stop()

    /**
     * Listen for one utterance. Returns null on silence / no match / error.
     * [onPartial] receives live partial transcripts for the speech bubble.
     */
    suspend fun listen(onPartial: (String) -> Unit): String? {
        if (!canListen) return null
        val result = CompletableDeferred<String?>()
        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(ctx).also { recognizer = it }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) { result.complete(null) }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                result.complete(list?.firstOrNull()?.takeIf { it.isNotBlank() })
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        sr.startListening(intent)
        val text = withTimeoutOrNull(15_000) { result.await() }
        if (text == null) sr.cancel()
        return text
    }

    fun shutdown() {
        recognizer?.destroy(); recognizer = null
        tts.stop(); tts.shutdown()
    }
}
