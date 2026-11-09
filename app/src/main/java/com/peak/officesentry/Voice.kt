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
class Voice(private val ctx: Context) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    @Volatile private var ready = false
    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null

    init {
        tts = TextToSpeech(ctx) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setPitch(1.15f)
                tts.setSpeechRate(1.0f)
                ready = true
            }
        }
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
