package com.peak.officesentry

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Tiny LAN web page so the owner can change status from a PC browser:
 * http://<phone-ip>:8080
 */
class ControlServer(
    private val prefs: Prefs,
    private val port: Int,
    private val onStatusChanged: () -> Unit,
    private val onSay: (String) -> Unit,
    private val onTest: () -> Unit,
) {
    private var server: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())

    fun start() {
        if (server != null) return
        thread(name = "control-server", isDaemon = true) {
            try {
                val ss = ServerSocket(port).also { server = it }
                while (!ss.isClosed) {
                    val s = ss.accept()
                    thread(isDaemon = true) { handle(s) }
                }
            } catch (e: Exception) {
                Log.w("ControlServer", "stopped: ${e.message}")
            }
        }
    }

    fun stop() { runCatching { server?.close() }; server = null }

    private fun handle(sock: Socket) = sock.use { s ->
        s.soTimeout = 5000
        val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        val requestLine = input.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val fullPath = parts[1]
        var contentLength = 0
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.lowercase().startsWith("content-length:")) contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
        }
        val body = if (contentLength > 0) {
            val buf = CharArray(contentLength.coerceAtMost(20_000))
            var read = 0
            while (read < buf.size) { val n = input.read(buf, read, buf.size - read); if (n < 0) break; read += n }
            String(buf, 0, read)
        } else ""
        val path = fullPath.substringBefore("?")
        val form = parseForm(if (method == "POST") body else fullPath.substringAfter("?", ""))
        val out = s.getOutputStream()

        // Optional PIN gate for anything that changes state
        val pinOk = prefs.pin.isBlank() || form["pin"] == prefs.pin

        when {
            method == "GET" && path == "/" -> respond(out, 200, "text/html; charset=utf-8", page())
            method == "GET" && path == "/api/state" -> respond(out, 200, "application/json", stateJson())
            method == "POST" && !pinOk -> respond(out, 403, "text/html; charset=utf-8", "<h2>Wrong PIN</h2><a href='/'>Back</a>")
            method == "POST" && path == "/status" -> {
                form["status"]?.let { runCatching { prefs.status = Status.valueOf(it) } }
                form["note"]?.let { prefs.note = it }
                main.post { onStatusChanged() }; redirect(out)
            }
            method == "POST" && path == "/say" -> {
                form["text"]?.takeIf { it.isNotBlank() }?.let { t -> main.post { onSay(t.take(300)) } }
                redirect(out)
            }
            method == "POST" && path == "/test" -> { main.post { onTest() }; redirect(out) }
            method == "POST" && path == "/messages/clear" -> { prefs.clearMessages(); main.post { onStatusChanged() }; redirect(out) }
            method == "POST" && path == "/settings" -> {
                form["owner"]?.let { prefs.ownerName = it }
                form["guard"]?.let { prefs.guardName = it }
                form["model"]?.let { prefs.model = it }
                form["apiKey"]?.takeIf { it.isNotBlank() }?.let { prefs.apiKey = it }
                form["cooldown"]?.toIntOrNull()?.let { prefs.cooldownSec = it }
                prefs.sendImage = form["sendImage"] == "on"
                prefs.listenEnabled = form["listen"] == "on"
                prefs.dimWhenIdle = form["dim"] == "on"
                form["newPin"]?.let { if (it.isNotBlank()) prefs.pin = if (it == "-") "" else it }
                main.post { onStatusChanged() }; redirect(out)
            }
            else -> respond(out, 404, "text/plain", "Not found")
        }
    }

    private fun parseForm(s: String): Map<String, String> =
        s.split("&").filter { it.contains("=") }.associate {
            URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray()); out.write(bytes); out.flush()
    }

    private fun redirect(out: OutputStream) {
        out.write("HTTP/1.1 303 See Other\r\nLocation: /\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun stateJson(): String {
        val msgs = prefs.messages().joinToString(",") { "{\"t\":${it.time},\"m\":\"${it.text.replace("\\", "\\\\").replace("\"", "\\\"")}\"}" }
        return "{\"status\":\"${prefs.status.name}\",\"note\":\"${prefs.note.replace("\"", "\\\"")}\",\"messages\":[$msgs]}"
    }

    private fun page(): String {
        val cur = prefs.status
        val pinField = if (prefs.pin.isNotBlank()) "<input name='pin' type='password' placeholder='PIN' class='pin'>" else ""
        val buttons = Status.values().joinToString("") { st ->
            val c = String.format("#%06X", 0xFFFFFF and st.color)
            val sel = if (st == cur) " sel" else ""
            "<button class='st$sel' style='background:$c' name='status' value='${st.name}'>${st.label}</button>"
        }
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.US)
        val msgs = prefs.messages().reversed().joinToString("") { "<li><b>${fmt.format(Date(it.time))}</b> &mdash; ${esc(it.text)}</li>" }
            .ifEmpty { "<li class='muted'>No messages.</li>" }
        val keySet = if (prefs.apiKey.isBlank()) "<span class='warn'>not set &mdash; guard uses scripted lines</span>" else "<span class='ok'>set (hidden)</span>"
        fun chk(b: Boolean) = if (b) "checked" else ""
        return """<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
<title>Office Sentry</title><style>
body{font-family:ui-monospace,Consolas,monospace;background:#1a1420;color:#eee;max-width:640px;margin:auto;padding:16px}
h1{font-size:22px;margin:4px 0 12px}h2{font-size:15px;color:#b9a8d8;margin:22px 0 8px;text-transform:uppercase;letter-spacing:1px}
.card{background:#2a2238;border:2px solid #3d3352;border-radius:10px;padding:14px}
.st{border:0;color:#fff;font:bold 15px monospace;padding:14px;border-radius:8px;margin:4px;width:calc(50% - 10px);cursor:pointer;opacity:.55}
.st.sel{opacity:1;outline:3px solid #fff}
input[type=text],input[type=password],input[type=number]{width:100%;box-sizing:border-box;padding:10px;background:#15101c;color:#eee;border:1px solid #4a3f60;border-radius:6px;font:14px monospace;margin:4px 0 10px}
.pin{width:120px!important}button.go{background:#2f5fb3;color:#fff;border:0;padding:10px 16px;border-radius:6px;font:bold 14px monospace;cursor:pointer}
label{display:block;margin:6px 0}.muted{color:#888}.warn{color:#f0a040}.ok{color:#5c5}ul{padding-left:18px}li{margin:6px 0}
</style></head><body>
<h1>&#9876; ${esc(prefs.guardName)} &mdash; Office Sentry</h1>
<div class='card'><form method='post' action='/status'>
$buttons
<input type='text' name='note' maxlength='80' placeholder='Optional note, e.g. "Back at 2:30"' value='${esc(prefs.note)}'>
$pinField <button class='go'>Update</button></form></div>

<h2>Make the guard speak</h2>
<div class='card'><form method='post' action='/say'>
<input type='text' name='text' maxlength='300' placeholder='e.g. Come in, I will be out in five minutes!'>
$pinField <button class='go'>Say it</button></form>
<form method='post' action='/test' style='margin-top:10px'>$pinField <button class='go' style='background:#555'>Test a visitor conversation</button></form></div>

<h2>Messages left by visitors</h2>
<div class='card'><ul>$msgs</ul>
<form method='post' action='/messages/clear'>$pinField <button class='go' style='background:#8e1f2a'>Clear messages</button></form></div>

<h2>Settings</h2>
<div class='card'><form method='post' action='/settings'>
<label>Your name<input type='text' name='owner' value='${esc(prefs.ownerName)}'></label>
<label>Guard name<input type='text' name='guard' value='${esc(prefs.guardName)}'></label>
<label>Claude API key: $keySet<input type='password' name='apiKey' placeholder='sk-ant-... (leave blank to keep)' autocomplete='off'></label>
<label>Claude model<input type='text' name='model' value='${esc(prefs.model)}'></label>
<label>Cooldown between visitors (seconds)<input type='number' name='cooldown' min='5' max='600' value='${prefs.cooldownSec}'></label>
<label><input type='checkbox' name='sendImage' ${chk(prefs.sendImage)}> Send a camera snapshot to Claude (for visual comments)</label>
<label><input type='checkbox' name='listen' ${chk(prefs.listenEnabled)}> Listen for replies (two-way conversation)</label>
<label><input type='checkbox' name='dim' ${chk(prefs.dimWhenIdle)}> Dim screen when nobody is around</label>
<label>Set control-page PIN (enter "-" to remove)<input type='password' name='newPin' placeholder='${if (prefs.pin.isBlank()) "no PIN set" else "PIN is set"}'></label>
$pinField <button class='go'>Save settings</button></form></div>
<p class='muted'>Page served by the phone at port $port.</p>
</body></html>"""
    }

    companion object {
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { it != null && (it.startsWith("192.") || it.startsWith("10.") || it.startsWith("172.")) }
        }.getOrNull()
    }
}
