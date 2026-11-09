package com.peak.officesentry

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Garrick's private "desk voice": announcements to the owner, played on the
 * ATOM Voice speaker and visualised on the Touch-LCD-7 panel.
 *
 * Flow: Claude writes the line -> a dedicated TTS instance renders it to a WAV
 * -> resampled to 16 kHz mono + loudness envelope (30 fps) -> the speaker is told
 * to fetch /audio/<id>.wav and the panel gets the envelope, both with the same
 * wall-clock start time so the voice lines move with the audio.
 */
class DeskLink(
    private val ctx: Context,
    private val prefs: Prefs,
    private val claude: ClaudeClient,
    private val scope: CoroutineScope,
) {
    data class Device(val ip: String, val seen: Long)

    val devices = ConcurrentHashMap<String, Device>()
    private val dir = File(ctx.cacheDir, "desk").apply { mkdirs() }
    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    private val lock = Mutex()

    @Volatile private var ttsReady = false
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private lateinit var tts: TextToSpeech

    init {
        buildTts()
    }

    /** Same engine as the door voice so Garrick sounds identical at the desk. */
    fun buildTts() {
        ttsReady = false
        if (::tts.isInitialized) runCatching { tts.shutdown() }
        tts = TextToSpeech(ctx, { st ->
            if (st == TextToSpeech.SUCCESS) {
                if (runCatching { tts.voice }.getOrNull() == null) runCatching { tts.language = Locale.US }
                ttsReady = true
            }
        }, prefs.ttsEngine.ifBlank { null })
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { id?.let { pending.remove(it)?.complete(true) } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { id?.let { pending.remove(it)?.complete(false) } }
            override fun onError(id: String?, errorCode: Int) { id?.let { pending.remove(it)?.complete(false) } }
        })
    }

    fun register(role: String, ip: String) {
        if (role == "panel" || role == "voice") devices[role] = Device(ip, System.currentTimeMillis())
    }

    fun online(role: String): Device? = devices[role]?.takeIf { System.currentTimeMillis() - it.seen < 90_000 }

    fun audioFile(id: String): File? =
        if (id.matches(Regex("[a-f0-9]{8,40}"))) File(dir, "$id.wav").takeIf { it.exists() } else null

    // ------------------------------------------------------------------ public API

    /** kind: visitor | message | calendar | test. [detail] goes to Claude; [fallback] is spoken if Claude is unavailable. */
    fun announce(kind: String, detail: String, fallback: String) {
        if (!prefs.deskEnabled) return
        if (online("voice") == null && online("panel") == null) return
        scope.launch(Dispatchers.Main) {
            lock.withLock {
                try {
                    val line = writeLine(kind, detail) ?: fallback
                    deliver(line)
                } catch (e: Exception) {
                    Log.w(TAG, "desk announce failed", e)
                }
            }
        }
    }

    // ------------------------------------------------------------------ line writing

    private suspend fun writeLine(kind: String, detail: String): String? {
        val key = prefs.apiKey
        if (key.isBlank()) return null
        val owner = prefs.ownerName
        val system = """
You are ${prefs.guardName}, a grizzled old veteran castle guard: thirty winters of service, gruff, dry-humored, fiercely loyal, soft-hearted underneath. You serve $owner and are speaking PRIVATELY to him at his desk through a small speaker.
- Address him as "Lord $owner" or "my lord".
- Reply with ONE or TWO short spoken sentences, light old-soldier flavor. Plain words only: no markdown, emoji, or stage directions.
- Include every concrete detail you are given (names, times, places) exactly; never invent details.
""".trimIndent()
        val ask = when (kind) {
            "visitor" -> "Announce that a visitor has arrived at the chamber door. Current office status: ${prefs.status.label}. $detail"
            "message" -> "A visitor left this message for him. Deliver it faithfully: $detail"
            "calendar" -> "Remind him of this calendar event: $detail"
            else -> "Say a short line confirming the desk speaker works. $detail"
        }
        return runCatching {
            withTimeoutOrNull(10_000) {
                claude.chat(key, prefs.model, system, JSONArray().put(ClaudeClient.msg("user", ask)), 120)
            }?.replace(Regex("[*_#`]"), "")?.lines()?.filterNot { it.trim().equals("[END]", true) }?.joinToString(" ")?.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------------ rendering + delivery

    private suspend fun deliver(text: String) {
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val raw = File(dir, "raw-$id.wav")
        if (!renderTts(text, raw)) { Log.w(TAG, "TTS render failed"); return }
        val env = withContext(Dispatchers.IO) { processWav(raw, File(dir, "$id.wav")) }
        raw.delete()
        cleanup()
        if (env == null) return

        val phone = ControlServer.localIp() ?: return
        val at = System.currentTimeMillis() + LEAD_MS
        withContext(Dispatchers.IO) {
            online("voice")?.let { v ->
                post("http://${v.ip}/play", FormBody.Builder()
                    .add("url", "http://$phone:${MainActivity.PORT}/audio/$id.wav")
                    .add("at", at.toString())
                    .add("vol", prefs.deskVolume.toString())
                    .build())
            }
            online("panel")?.let { p ->
                val json = JSONObject()
                    .put("text", text)
                    .put("at", at)
                    .put("fps", FPS)
                    .put("env", env.joinToString("") { "%02x".format(it) })
                post("http://${p.ip}/say", json.toString().toRequestBody("application/json".toMediaType()))
            }
        }
    }

    private fun post(url: String, body: okhttp3.RequestBody) {
        runCatching { http.newCall(Request.Builder().url(url).post(body).build()).execute().close() }
            .onFailure { Log.w(TAG, "POST $url failed: ${it.message}") }
    }

    private suspend fun renderTts(text: String, out: File): Boolean {
        if (!ttsReady) withTimeoutOrNull(3000) { while (!ttsReady) kotlinx.coroutines.delay(100) }
        if (!ttsReady) return false
        tts.setPitch(prefs.pitch)
        tts.setSpeechRate(prefs.rate)
        prefs.voiceName.takeIf { it.isNotBlank() }?.let { n ->
            runCatching { tts.voices?.firstOrNull { it.name == n }?.let { tts.voice = it } }
        }
        val uid = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        pending[uid] = done
        val rc = tts.synthesizeToFile(text, Bundle(), out, uid)
        if (rc != TextToSpeech.SUCCESS) { pending.remove(uid); return false }
        val ok = withTimeoutOrNull(30_000) { done.await() } ?: false
        pending.remove(uid)
        return ok && out.exists() && out.length() > 44
    }

    /** Parse the TTS WAV, resample to 16 kHz mono 16-bit, normalise, write [out]. Returns the loudness envelope. */
    private fun processWav(src: File, out: File): ByteArray? {
        val bytes = src.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF") return null
        var pos = 12
        var rate = 0; var channels = 1; var bits = 16
        var dataOff = -1; var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = bb.getInt(pos + 4)
            if (id == "fmt ") {
                channels = bb.getShort(pos + 10).toInt()
                rate = bb.getInt(pos + 12)
                bits = bb.getShort(pos + 22).toInt()
            } else if (id == "data") {
                dataOff = pos + 8
                dataLen = min(len.takeIf { it > 0 } ?: Int.MAX_VALUE, bytes.size - dataOff)
                break
            }
            pos += 8 + len + (len and 1)
        }
        if (dataOff < 0 || rate <= 0 || bits != 16) return null

        // to mono float
        val frames = dataLen / (2 * channels)
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var s = 0f
            for (c in 0 until channels) s += bb.getShort(dataOff + (i * channels + c) * 2)
            mono[i] = s / channels
        }
        // resample (linear) to 16 kHz
        val outLen = (frames.toLong() * OUT_RATE / rate).toInt()
        val res = FloatArray(outLen)
        val step = rate.toDouble() / OUT_RATE
        for (i in 0 until outLen) {
            val p = i * step
            val i0 = p.toInt().coerceAtMost(frames - 1)
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            val f = (p - i0).toFloat()
            res[i] = mono[i0] * (1 - f) + mono[i1] * f
        }
        // normalise peak to ~ -1 dBFS (the ATOM speaker is quiet; use all the headroom)
        var peak = 1f
        for (v in res) peak = max(peak, abs(v))
        val gain = 29_000f / peak
        val pcm = ByteBuffer.allocate(outLen * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in res) pcm.putShort((v * gain).coerceIn(-32768f, 32767f).toInt().toShort())

        // loudness envelope at FPS
        val per = OUT_RATE / FPS
        val n = (outLen + per - 1) / per
        val rms = FloatArray(n)
        var maxRms = 1f
        for (k in 0 until n) {
            var acc = 0.0
            val a = k * per; val b = min(outLen, a + per)
            for (i in a until b) { val v = res[i] * gain; acc += v * v }
            rms[k] = sqrt(acc / max(1, b - a)).toFloat()
            maxRms = max(maxRms, rms[k])
        }
        val env = ByteArray(n) { k -> ((rms[k] / maxRms).toDouble().pow(0.6) * 255).toInt().coerceIn(0, 255).toByte() }

        writeWav(out, pcm.array())
        return env
    }

    private fun writeWav(f: File, pcm: ByteArray) {
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(0)
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
            h.putInt(OUT_RATE); h.putInt(OUT_RATE * 2); h.putShort(2); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(pcm.size)
            raf.write(h.array()); raf.write(pcm)
        }
    }

    private fun cleanup() {
        dir.listFiles { f -> f.name.endsWith(".wav") && !f.name.startsWith("raw-") }
            ?.sortedByDescending { it.lastModified() }?.drop(6)?.forEach { it.delete() }
    }

    fun shutdown() { runCatching { tts.shutdown() } }

    companion object {
        const val TAG = "DeskLink"
        const val OUT_RATE = 16_000
        const val FPS = 30
        const val LEAD_MS = 900L
    }
}
