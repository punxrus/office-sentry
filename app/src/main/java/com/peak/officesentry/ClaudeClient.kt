package com.peak.officesentry

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** Minimal client for the Anthropic Messages API. */
class ClaudeClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun chat(apiKey: String, model: String, system: String, messages: JSONArray, maxTokens: Int = 250): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", maxTokens)
                .put("system", system)
                .put("messages", messages)
            val req = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { JSONObject(txt).getJSONObject("error").getString("message") }.getOrDefault(txt.take(200))
                    throw IllegalStateException("Claude API ${resp.code}: $msg")
                }
                val content = JSONObject(txt).getJSONArray("content")
                buildString {
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if (block.optString("type") == "text") append(block.getString("text"))
                    }
                }.trim()
            }
        }

    companion object {
        fun imageBlock(bmp: Bitmap): JSONObject {
            val scale = 768f / max(bmp.width, bmp.height)
            val scaled = if (scale < 1f)
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
            return JSONObject()
                .put("type", "image")
                .put("source", JSONObject()
                    .put("type", "base64")
                    .put("media_type", "image/jpeg")
                    .put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)))
        }

        fun textBlock(t: String) = JSONObject().put("type", "text").put("text", t)

        fun msg(role: String, content: JSONArray) = JSONObject().put("role", role).put("content", content)
        fun msg(role: String, text: String) = JSONObject().put("role", role).put("content", text)
    }
}
