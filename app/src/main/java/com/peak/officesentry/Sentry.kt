package com.peak.officesentry

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The guard's brain: runs one conversation per visitor.
 * Claude does the talking; on-device detection decides when to start.
 */
class Sentry(
    private val prefs: Prefs,
    private val voice: Voice,
    private val claude: ClaudeClient,
    private val scope: CoroutineScope,
    private val ui: Ui,
) {
    interface Ui {
        fun setMood(m: GuardMood)
        fun setBubble(text: String)
        fun refresh()
        fun visitorDone()
    }

    private var job: Job? = null
    val busy get() = job?.isActive == true
    var lastEnded = 0L; private set

    fun idleText(): String = prefs.status.line(prefs.ownerName)

    /** Called when the detector confirms a visitor. */
    fun onVisitor(s: Sighting) {
        if (busy) return
        job = scope.launch {
            try {
                if (s.pet != null) greetPet(s) else converse(s)
            } catch (e: Exception) {
                Log.e(TAG, "conversation failed", e)
                ui.setMood(GuardMood.SPEAKING)
                val line = fallbackLine()
                ui.setBubble(line); voice.speak(line)
            } finally {
                lastEnded = SystemClock.uptimeMillis()
                ui.setMood(GuardMood.IDLE)
                ui.setBubble(idleText())
                ui.visitorDone()
            }
        }
    }

    /** Owner typed something on the control page: say it verbatim. */
    fun announce(text: String) {
        if (busy) job?.cancel()
        voice.stopSpeaking()
        job = scope.launch {
            try {
                ui.setMood(GuardMood.SPEAKING); ui.setBubble(text); voice.speak(text)
            } finally {
                ui.setMood(GuardMood.IDLE); ui.setBubble(idleText())
            }
        }
    }

    private fun fallbackLine(): String {
        val st = prefs.status; val owner = prefs.ownerName
        return when (st) {
            Status.OPEN -> "Hail, friend! $owner is in. Knock and enter."
            Status.KNOCK -> "Greetings! Please knock and wait for $owner to answer."
            Status.MEETING -> "Halt, friend! $owner is in a meeting. Please return later."
            Status.DND -> "Halt! $owner must not be disturbed right now, unless it is an emergency."
            Status.AWAY -> "Greetings! $owner is away from the office at the moment."
        } + if (prefs.note.isNotBlank()) " ${prefs.note}." else ""
    }

    private fun systemPrompt(): String {
        val owner = prefs.ownerName
        val time = SimpleDateFormat("EEEE h:mm a", Locale.US).format(Date())
        return """
You are ${prefs.guardName}, a small, cheerful pixel-art castle guard in steel helmet and blue tabard, posted outside $owner's home office door. You are shown on an old phone screen and your words are spoken aloud through a speaker.

CURRENT STATUS: ${prefs.status.label}. ${prefs.status.aiLine(owner)}
${if (prefs.note.isNotBlank()) "Owner's note: \"${prefs.note}\"" else ""}
Local time: $time.

How to behave:
- Speak in one to three SHORT sentences. Plain spoken English with a light, playful medieval flavor ("Hail!", "traveler", "milord"). Never use markdown, emoji, lists, or stage directions.
- Your job is to tell visitors whether they may knock or come in, based on the status. You cannot open the door yourself.
- Visitors are most likely family (a spouse, kids, pets) or guests. Be warm and kind, never scary. Keep it family-friendly.
- If the status blocks entry, politely hold them off and offer to take a message for $owner.
- EMERGENCIES ALWAYS WIN: if someone mentions an emergency, injury, fire, or anything urgent, tell them to knock loudly and go in right away.
- If they want to leave a message, confirm it back briefly, then put it on its own final line exactly like:
MESSAGE: <the message, written in third person, e.g. "Sarah says dinner is ready.">
- When the conversation is clearly over (they said goodbye, thanks, left a message, or have nothing more), end your reply with a final line containing only: [END]
- If you are shown a camera image, you may lightly reference what you see (a mug, a hat, a dog) to be charming, but never comment on anyone's body, looks, age, or identity, and never guess who someone is.
""".trimIndent()
    }

    private data class Parsed(val speech: String, val message: String?, val end: Boolean)

    private fun parse(reply: String): Parsed {
        var end = false
        var message: String? = null
        val speech = reply.lines().filter { line ->
            val t = line.trim()
            when {
                t.equals("[END]", true) -> { end = true; false }
                t.startsWith("MESSAGE:", true) -> { message = t.substringAfter(":").trim(); false }
                else -> true
            }
        }.joinToString(" ").replace("[END]", "").replace(Regex("[*_#`]"), "").trim()
        return Parsed(speech, message, end)
    }

    private suspend fun converse(s: Sighting) {
        ui.setMood(GuardMood.ALERT)
        val key = prefs.apiKey
        if (key.isBlank()) {
            val line = fallbackLine()
            ui.setMood(GuardMood.SPEAKING); ui.setBubble(line); voice.speak(line); return
        }

        ui.setMood(GuardMood.THINKING)
        val messages = JSONArray()
        val first = JSONArray()
        if (prefs.sendImage && s.snapshot != null) first.put(ClaudeClient.imageBlock(s.snapshot))
        first.put(ClaudeClient.textBlock(
            "[Camera event] ${s.faces} person(s) just walked up to the door. " +
                "On-device vision labels: ${s.labels.joinToString().ifEmpty { "none" }}. " +
                "Greet them and tell them the situation."
        ))
        messages.put(ClaudeClient.msg("user", first))

        var turns = 0
        var misses = 0
        while (true) {
            val reply = claude.chat(key, prefs.model, systemPrompt(), messages)
            messages.put(ClaudeClient.msg("assistant", reply))
            val p = parse(reply)
            p.message?.let { prefs.addMessage(it); ui.refresh() }

            ui.setMood(GuardMood.SPEAKING); ui.setBubble(p.speech)
            voice.speak(p.speech)
            turns++
            if (p.end || turns >= MAX_TURNS || !prefs.listenEnabled || !voice.canListen) return

            ui.setMood(GuardMood.LISTENING); ui.setBubble("")
            val heard = voice.listen { partial -> ui.setBubble("“$partial”") }
            if (heard == null) {
                misses++
                if (misses >= 2 || turns >= 2) {
                    val bye = "Very well. Fare thee well, traveler!"
                    ui.setMood(GuardMood.SPEAKING); ui.setBubble(bye); voice.speak(bye)
                    return
                }
                messages.put(ClaudeClient.msg("user", "[The visitor stayed silent.]"))
            } else {
                misses = 0
                ui.setBubble("“$heard”")
                messages.put(ClaudeClient.msg("user", heard))
            }
            ui.setMood(GuardMood.THINKING)
        }
    }

    private suspend fun greetPet(s: Sighting) {
        ui.setMood(GuardMood.ALERT)
        val key = prefs.apiKey
        val line = if (key.isBlank()) {
            "Halt, noble ${s.pet!!.lowercase()}! Thou art a very good guardian too."
        } else {
            ui.setMood(GuardMood.THINKING)
            val content = JSONArray()
            if (prefs.sendImage && s.snapshot != null) content.put(ClaudeClient.imageBlock(s.snapshot))
            content.put(ClaudeClient.textBlock(
                "[Camera event] A ${s.pet} (no human) wandered up to the door. Say one short, silly, affectionate line to it. Then [END]."
            ))
            parse(claude.chat(key, prefs.model, systemPrompt(), JSONArray().put(ClaudeClient.msg("user", content)), 120)).speech
        }
        ui.setMood(GuardMood.SPEAKING); ui.setBubble(line); voice.speak(line)
    }

    companion object {
        const val TAG = "Sentry"
        const val MAX_TURNS = 6
    }
}
