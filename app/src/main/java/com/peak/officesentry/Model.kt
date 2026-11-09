package com.peak.officesentry

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/** Office status. `blocking` = guard bars the door. `ai` = context given to Claude. */
enum class Status(
    val label: String,
    val color: Int,
    val blocking: Boolean,
    val ai: String,
    val idleLine: String,
) {
    OPEN(
        "COME IN", Color.rgb(46, 160, 67), false,
        "OWNER is available. Visitors are welcome to knock and come in.",
        "OWNER is in. Knock and enter."
    ),
    KNOCK(
        "KNOCK FIRST", Color.rgb(226, 160, 30), false,
        "OWNER is working. Visitors should knock and wait to be invited in.",
        "Knock, and await an answer."
    ),
    MEETING(
        "IN A MEETING", Color.rgb(205, 50, 50), true,
        "OWNER is in a meeting and may be on camera or microphone. Nobody should open the door or make noise. Offer to take a message.",
        "Halt. OWNER is in a meeting."
    ),
    DND(
        "DO NOT DISTURB", Color.rgb(130, 70, 190), true,
        "OWNER is focusing and does not want to be disturbed except for real emergencies. Offer to take a message.",
        "None shall disturb OWNER."
    ),
    AWAY(
        "AWAY", Color.rgb(110, 110, 125), false,
        "OWNER is not in the office right now. Offer to take a message.",
        "OWNER is away. I keep watch."
    );

    fun line(owner: String) = idleLine.replace("OWNER", owner)
    fun aiLine(owner: String) = ai.replace("OWNER", owner)
}

data class VisitorMessage(val time: Long, val text: String)

/** Thin wrapper over SharedPreferences. Safe to use from any thread. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("sentry", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v.trim()).apply()
    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()
    var ownerName: String
        get() = sp.getString("owner", "James") ?: "James"
        set(v) = sp.edit().putString("owner", v.trim().ifEmpty { "James" }).apply()
    var guardName: String
        get() = sp.getString("guard", "Old Garrick") ?: "Old Garrick"
        set(v) = sp.edit().putString("guard", v.trim().ifEmpty { "Old Garrick" }).apply()
    var status: Status
        get() = runCatching { Status.valueOf(sp.getString("status", "OPEN")!!) }.getOrDefault(Status.OPEN)
        set(v) = sp.edit().putString("status", v.name).apply()
    var note: String
        get() = sp.getString("note", "") ?: ""
        set(v) = sp.edit().putString("note", v.trim().take(80)).apply()
    var sendImage: Boolean
        get() = sp.getBoolean("sendImage", true)
        set(v) = sp.edit().putBoolean("sendImage", v).apply()
    var frontCamera: Boolean
        get() = sp.getBoolean("frontCamera", true)
        set(v) = sp.edit().putBoolean("frontCamera", v).apply()
    var listenEnabled: Boolean
        get() = sp.getBoolean("listen", true)
        set(v) = sp.edit().putBoolean("listen", v).apply()
    var cooldownSec: Int
        get() = sp.getInt("cooldown", 45)
        set(v) = sp.edit().putInt("cooldown", v.coerceIn(5, 600)).apply()
    var dimWhenIdle: Boolean
        get() = sp.getBoolean("dim", true)
        set(v) = sp.edit().putBoolean("dim", v).apply()
    /** Android TTS voice name, e.g. "en-us-x-iom-local". Blank = engine default. */
    var voiceName: String
        get() = sp.getString("voiceName", "") ?: ""
        set(v) = sp.edit().putString("voiceName", v.trim()).apply()
    /** Stout-veteran defaults: lower and a touch slower. */
    var pitch: Float
        get() = sp.getFloat("pitch", 0.8f)
        set(v) = sp.edit().putFloat("pitch", v.coerceIn(0.5f, 1.6f)).apply()
    var rate: Float
        get() = sp.getFloat("rate", 0.9f)
        set(v) = sp.edit().putFloat("rate", v.coerceIn(0.5f, 1.6f)).apply()
    var pin: String
        get() = sp.getString("pin", "") ?: ""
        set(v) = sp.edit().putString("pin", v.trim()).apply()

    @Synchronized
    fun messages(): List<VisitorMessage> {
        val arr = JSONArray(sp.getString("messages", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            VisitorMessage(o.getLong("t"), o.getString("m"))
        }
    }

    @Synchronized
    fun addMessage(text: String) {
        val arr = JSONArray(sp.getString("messages", "[]"))
        arr.put(JSONObject().put("t", System.currentTimeMillis()).put("m", text.take(500)))
        while (arr.length() > 50) arr.remove(0)
        sp.edit().putString("messages", arr.toString()).apply()
    }

    @Synchronized
    fun clearMessages() = sp.edit().putString("messages", "[]").apply()

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-5-5"
    }
}

enum class GuardMood { IDLE, ALERT, LISTENING, THINKING, SPEAKING, SLEEPING }
