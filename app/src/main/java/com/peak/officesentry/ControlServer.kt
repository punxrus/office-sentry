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
    private val voices: () -> List<Voice.VoiceInfo>,
    private val engines: () -> List<Voice.EngineInfo>,
    private val onEngineChanged: () -> Unit,
    private val faceJson: () -> String,
    private val onCalibrate: () -> Unit,
    private val desk: DeskLink,
    /** Extra live JSON fields for /api/state, e.g. "\"mood\":\"IDLE\",\"visitor\":false" */
    private val liveState: () -> String,
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
        val remoteIp = s.inetAddress?.hostAddress ?: ""

        // Optional PIN gate for anything that changes state
        val pinOk = prefs.pin.isBlank() || form["pin"] == prefs.pin

        when {
            method == "GET" && path == "/" -> respond(out, 200, "text/html; charset=utf-8", page())
            method == "GET" && path == "/api/state" -> respond(out, 200, "application/json", stateJson())
            method == "GET" && path == "/api/face" -> respond(out, 200, "application/json", faceJson())
            method == "GET" && path.startsWith("/audio/") && path.endsWith(".wav") -> {
                val f = desk.audioFile(path.removePrefix("/audio/").removeSuffix(".wav"))
                if (f == null) respond(out, 404, "text/plain", "gone") else respondFile(out, "audio/wav", f)
            }
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
            method == "POST" && path == "/api/register" -> {
                desk.register(form["role"].orEmpty(), form["ip"]?.takeIf { it.isNotBlank() } ?: remoteIp)
                respond(out, 200, "application/json", "{\"ok\":true}")
            }
            method == "POST" && path == "/calibrate" -> { main.post { onCalibrate() }; redirect(out) }
            method == "POST" && path == "/detect/settings" -> {
                form["minFace"]?.toFloatOrNull()?.let { prefs.minFaceFrac = it / 100f }
                form["maxYaw"]?.toIntOrNull()?.let { prefs.maxYaw = it }
                form["sleepAfter"]?.toIntOrNull()?.let { prefs.sleepAfterSec = it }
                prefs.dimWhenIdle = form["dim"] == "on"
                prefs.greetPets = form["pets"] == "on"
                redirect(out)
            }
            method == "POST" && path == "/desk/test" -> {
                desk.announce("test", "", "Lord ${prefs.ownerName}, your desk herald stands ready.")
                redirect(out)
            }
            method == "POST" && path == "/desk/settings" -> {
                prefs.deskEnabled = form["desk"] == "on"
                prefs.calEnabled = form["cal"] == "on"
                form["calLead"]?.toIntOrNull()?.let { prefs.calLeadMin = it }
                form["deskVol"]?.toIntOrNull()?.let { prefs.deskVolume = it }
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
                form["newPin"]?.let { if (it.isNotBlank()) prefs.pin = if (it == "-") "" else it }
                val newEngine = form["ttsEngine"]
                if (newEngine != null && newEngine != prefs.ttsEngine) {
                    prefs.ttsEngine = newEngine
                    prefs.voiceName = ""            // voice names differ per engine
                    main.post { onEngineChanged() }
                } else form["voiceName"]?.let { prefs.voiceName = it }
                form["pitch"]?.toFloatOrNull()?.let { prefs.pitch = it }
                form["rate"]?.toFloatOrNull()?.let { prefs.rate = it }
                main.post { onStatusChanged() }
                if (form["action"] == "preview") main.post { onSay("Halt! State thy business, traveler. I have guarded this door for thirty winters, and I'll guard it thirty more.") }
                redirect(out)
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

    private fun respondFile(out: OutputStream, type: String, f: java.io.File) {
        val head = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${f.length()}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        f.inputStream().use { it.copyTo(out, 8192) }
        out.flush()
    }

    private fun redirect(out: OutputStream) {
        out.write("HTTP/1.1 303 See Other\r\nLocation: /\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun devLine(role: String): String {
        val d = desk.devices[role] ?: return "<span class='warn'>not connected</span>"
        val ago = (System.currentTimeMillis() - d.seen) / 1000
        val ok = desk.online(role) != null
        return "<span class='${if (ok) "ok" else "warn"}'>${esc(d.ip)} (seen ${ago}s ago)</span>"
    }

    private fun engineOptions(): String {
        val cur = prefs.ttsEngine
        val def = "<option value=''${if (cur.isBlank()) " selected" else ""}>Phone default</option>"
        return def + engines().joinToString("") { e ->
            "<option value='${esc(e.name)}'${if (e.name == cur) " selected" else ""}>${esc(e.label)} (${esc(e.name)})</option>"
        }
    }

    private fun voiceOptions(): String {
        val cur = prefs.voiceName
        val list = voices()
        val def = "<option value=''${if (cur.isBlank()) " selected" else ""}>Phone default</option>"
        return def + list.joinToString("") { v ->
            "<option value='${esc(v.name)}'${if (v.name == cur) " selected" else ""}>${esc(v.label)}</option>"
        }
    }

    private fun stateJson(): String {
        val msgs = prefs.messages().joinToString(",") { "{\"t\":${it.time},\"m\":\"${it.text.replace("\\", "\\\\").replace("\"", "\\\"")}\"}" }
        val live = liveState().let { if (it.isBlank()) "" else ",$it" }
        return "{\"status\":\"${prefs.status.name}\",\"label\":\"${prefs.status.label}\",\"note\":\"${prefs.note.replace("\"", "\\\"")}\",\"owner\":\"${prefs.ownerName.replace("\"", "")}\",\"guard\":\"${prefs.guardName.replace("\"", "")}\",\"messages\":[$msgs]$live}"
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
input[type=text],input[type=password],input[type=number],select{width:100%;box-sizing:border-box;padding:10px;background:#15101c;color:#eee;border:1px solid #4a3f60;border-radius:6px;font:14px monospace;margin:4px 0 10px}
.pin{width:120px!important}button.go{background:#2f5fb3;color:#fff;border:0;padding:10px 16px;border-radius:6px;font:bold 14px monospace;cursor:pointer}
label{display:block;margin:6px 0}input[type=range]{width:100%;margin:4px 0 10px}.muted{color:#888}.warn{color:#f0a040}.ok{color:#5c5}ul{padding-left:18px}li{margin:6px 0}
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

<h2>Visitor detection and power</h2>
<div class='card'>
<p style='margin-top:0'>Garrick speaks only when someone is <b>close</b>, <b>facing the door</b>, and <b>both eyes</b> are visible, for about a second. People passing in profile or farther down the hall only wake the screen.</p>
<div id='live' style='background:#15101c;border-radius:8px;padding:10px;margin-bottom:10px;font-size:13px'>Live camera: loading...</div>
<form method='post' action='/calibrate'>$pinField <button class='go' style='background:#7a5a1e'>&#127919; Calibrate distance (Garrick will talk you through it)</button></form>
<form method='post' action='/detect/settings' style='margin-top:12px'>
<label>Minimum face size to speak (% of camera width, bigger = must be closer)<input type='number' name='minFace' min='4' max='80' step='1' value='${(prefs.minFaceFrac * 100).toInt()}'></label>
<label>Max head turn to count as "facing the door" (degrees)<input type='number' name='maxYaw' min='5' max='60' value='${prefs.maxYaw}'></label>
<label><input type='checkbox' name='dim' ${chk(prefs.dimWhenIdle)}> Sleep the screen when nobody is around (camera keeps watching)</label>
<label>Sleep after (seconds with no faces)<input type='number' name='sleepAfter' min='10' max='3600' value='${prefs.sleepAfterSec}'></label>
<label><input type='checkbox' name='pets' ${chk(prefs.greetPets)}> Greet dogs and cats</label>
$pinField <button class='go'>Save detection settings</button></form>
</div>
<script>
async function tick(){try{const r=await fetch('/api/face');const f=await r.json();const el=document.getElementById('live');
if(!f.seen){el.innerHTML='Live camera: no face seen yet'+(f.asleep?' (screen asleep)':'');return}
const ok=(b,t)=>`<span style="color:${'$'}{b?'#5c5':'#f0a040'}">${'$'}{b?'&#10003;':'&#10007;'} ${'$'}{t}</span>`;
const age=(f.ageMs/1000).toFixed(0);
el.innerHTML=`Last face ${'$'}{age}s ago${'$'}{f.asleep?' (screen asleep)':''}<br>`+
ok(f.frac>=f.minFace,`size ${'$'}{(f.frac*100).toFixed(0)}% (need ${'$'}{(f.minFace*100).toFixed(0)}%)`)+' &nbsp; '+
ok(Math.abs(f.yaw)<=f.maxYaw&&Math.abs(f.pitch)<=f.maxYaw,`facing ${'$'}{Math.round(f.yaw)}&deg;`)+' &nbsp; '+
ok(f.bothEyes,'both eyes')+'<br>'+(f.engaged?'<b style="color:#5c5">Would speak to this person</b>':'<span class="muted">Would not speak</span>')}catch(e){}}
setInterval(tick,1000);tick();
</script>

<h2>Desk herald (ATOM Voice + panel)</h2>
<div class='card'>
<p>Speaker: ${devLine("voice")}<br>Panel: ${devLine("panel")}</p>
<form method='post' action='/desk/settings'>
<label><input type='checkbox' name='desk' ${chk(prefs.deskEnabled)}> Announce visitors, messages and reminders at my desk</label>
<label><input type='checkbox' name='cal' ${chk(prefs.calEnabled)}> Calendar reminders (phone's signed-in calendars)</label>
<label>Remind me this many minutes before events<input type='number' name='calLead' min='1' max='120' value='${prefs.calLeadMin}'></label>
<label>Speaker volume %<input type='number' name='deskVol' min='10' max='200' value='${prefs.deskVolume}'></label>
$pinField <button class='go'>Save desk settings</button></form>
<form method='post' action='/desk/test' style='margin-top:10px'>$pinField <button class='go' style='background:#7a5a1e'>&#128264; Test desk announcement</button></form>
</div>

<h2>Settings</h2>
<div class='card'><form method='post' action='/settings'>
<label>Your name<input type='text' name='owner' value='${esc(prefs.ownerName)}'></label>
<label>Guard name<input type='text' name='guard' value='${esc(prefs.guardName)}'></label>
<label>Claude API key: $keySet<input type='password' name='apiKey' placeholder='sk-ant-... (leave blank to keep)' autocomplete='off'></label>
<label>Claude model<input type='text' name='model' value='${esc(prefs.model)}'></label>
<label>Speech engine<select name='ttsEngine'>${engineOptions()}</select></label>
<p class='muted' style='margin:0 0 10px'>Want a natural AI voice for free? Install <b>VoxSherpa TTS</b> (Play Store) or <b>SherpaTTS</b> (F-Droid), download a Piper voice in that app, then pick it here and Save. After switching engines, reload this page to see its voices.</p>
<label>Voice<select name='voiceName'>${voiceOptions()}</select></label>
<label>Pitch: <output id='po'>${"%.2f".format(prefs.pitch)}</output><input type='range' name='pitch' min='0.5' max='1.6' step='0.05' value='${prefs.pitch}' oninput="po.value=Number(this.value).toFixed(2)"></label>
<label>Speed: <output id='ro'>${"%.2f".format(prefs.rate)}</output><input type='range' name='rate' min='0.5' max='1.6' step='0.05' value='${prefs.rate}' oninput="ro.value=Number(this.value).toFixed(2)"></label>
<button class='go' name='action' value='preview' style='background:#7a5a1e;margin-bottom:12px'>&#128264; Save &amp; preview voice</button>
<label>Cooldown between visitors (seconds)<input type='number' name='cooldown' min='5' max='600' value='${prefs.cooldownSec}'></label>
<label><input type='checkbox' name='sendImage' ${chk(prefs.sendImage)}> Send a camera snapshot to Claude (for visual comments)</label>
<label><input type='checkbox' name='listen' ${chk(prefs.listenEnabled)}> Listen for replies (two-way conversation)</label>
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
