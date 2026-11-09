package com.peak.officesentry

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), Sentry.Ui {

    private lateinit var prefs: Prefs
    private lateinit var view: GuardView
    private lateinit var voice: Voice
    private lateinit var sentry: Sentry
    private var detector: Detector? = null
    private var server: ControlServer? = null
    private lateinit var desk: DeskLink
    private lateinit var calendar: CalendarWatcher
    @Volatile private var currentMood = GuardMood.IDLE

    @Volatile private var lastActivity = SystemClock.uptimeMillis()
    private var dimmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        view = GuardView(this)
        setContentView(view)
        hideSystemUi()
        runCatching { preferLowRefresh() }

        voice = Voice(this, prefs)
        val claude = ClaudeClient()
        desk = DeskLink(this, prefs, claude, lifecycleScope)
        calendar = CalendarWatcher(this, prefs, desk)
        sentry = Sentry(prefs, voice, claude, lifecycleScope, this, desk) {
            // Someone is at the door: pull fresh calendar data, give it a moment, then read it.
            withContext(Dispatchers.IO) {
                calendar.requestSync(minGapMs = 20_000)
                delay(1500)
                calendar.scheduleSummary()
            }
        }
        refresh()
        view.bubbleText = sentry.idleText()
        setupOwnerGesture()

        server = ControlServer(
            prefs, PORT,
            onStatusChanged = { refresh(); if (!sentry.busy) view.bubbleText = sentry.idleText() },
            onSay = { wake(); sentry.announce(it) },
            onTest = { wake(); sentry.onVisitor(Sighting(1, emptyList(), null)) },
            voices = { voice.voiceList() },
            engines = { voice.engines },
            onEngineChanged = { voice.buildTts(); desk.buildTts() },
            faceJson = {
                val m = detector?.metrics
                val age = if (m == null) -1 else SystemClock.uptimeMillis() - m.at
                if (m == null) "{\"seen\":false,\"minFace\":${prefs.minFaceFrac},\"maxYaw\":${prefs.maxYaw},\"asleep\":$dimmed}"
                else "{\"seen\":true,\"ageMs\":$age,\"frac\":${m.frac},\"yaw\":${m.yaw},\"pitch\":${m.pitch},\"bothEyes\":${m.bothEyes},\"engaged\":${m.engaged},\"minFace\":${prefs.minFaceFrac},\"maxYaw\":${prefs.maxYaw},\"asleep\":$dimmed}"
            },
            onCalibrate = { calibrate() },
            desk = desk,
            liveState = {
                val seen = detector?.lastFaceSeen ?: 0L
                val visitor = seen > 0 && SystemClock.uptimeMillis() - seen < 10_000
                "\"mood\":\"${currentMood.name}\",\"visitor\":$visitor"
            },
        ).also { it.start() }

        requestPerms()
        startIdleLoop()
        lifecycleScope.launch {
            var n = 0
            while (true) {
                withContext(Dispatchers.IO) {
                    if (n++ % 15 == 0) calendar.requestSync(minGapMs = 0)   // every 15 min, catch new events
                    calendar.check()
                }
                delay(60_000)
            }
        }
    }

    // ---------------- permissions + camera ----------------

    private fun requestPerms() {
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CALENDAR)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startCamera() else requestPermissions(needed.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else Toast.makeText(this, "Camera permission is required to see visitors.", Toast.LENGTH_LONG).show()
    }

    private fun startCamera() {
        detector?.shutdown()
        detector = Detector(this, prefs,
            onVisitor = { s -> runOnUiThread { handleVisitor(s) } },
            onActivity = {
                lastActivity = SystemClock.uptimeMillis()
                if (dimmed) runOnUiThread { wake() }
                // someone appeared: start a calendar sync early so it's fresh if they come to the door
                calendar.requestSync()
            },
        ).also {
            it.intervalMs = if (dimmed) SLEEP_INTERVAL else AWAKE_INTERVAL
            it.onCalibrated = { frac -> runOnUiThread { finishCalibration(frac) } }
            it.start(this, prefs.frontCamera)
        }
    }

    private fun handleVisitor(s: Sighting) {
        val d = detector ?: return
        val sinceEnd = SystemClock.uptimeMillis() - sentry.lastEnded
        if (sentry.busy || (sentry.lastEnded > 0 && sinceEnd < prefs.cooldownSec * 1000L)) {
            d.armed = true // not now; keep watching
            return
        }
        wake()
        sentry.onVisitor(s)
    }

    override fun visitorDone() {
        // re-arm once the conversation is over (cooldown is enforced in handleVisitor)
        detector?.armed = true
    }

    // ---------------- Sentry.Ui ----------------

    override fun setMood(m: GuardMood) { currentMood = m; runOnUiThread { view.mood = m } }
    override fun setBubble(text: String) = runOnUiThread { view.bubbleText = text }
    override fun refresh() = runOnUiThread {
        view.status = prefs.status
        view.note = prefs.note
        view.messageCount = prefs.messages().size
        val ip = ControlServer.localIp()
        view.footer = if (ip != null) "$ip:$PORT" else "no Wi-Fi"
    }

    // ---------------- screen sleep (camera keeps watching) ----------------
    // After prefs.sleepAfterSec with no faces: screen goes black at minimum backlight,
    // the view stops redrawing, and the camera checks less often. Any face wakes it.
    // (The display is not switched fully off: Android would pause the camera.)

    private fun startIdleLoop() = lifecycleScope.launch {
        var ticks = 0
        while (true) {
            delay(2000)
            val idleMs = SystemClock.uptimeMillis() - lastActivity
            if (!sentry.busy && !dimmed && prefs.dimWhenIdle && idleMs > prefs.sleepAfterSec * 1000L) sleepScreen()
            if (!dimmed && ++ticks % 3 == 0) refresh()
        }
    }

    private fun sleepScreen() {
        dimmed = true
        view.mood = GuardMood.SLEEPING
        view.blackout = true
        setBrightness(0f)
        detector?.intervalMs = SLEEP_INTERVAL
    }

    private fun wake() {
        lastActivity = SystemClock.uptimeMillis()
        if (dimmed) {
            dimmed = false
            view.blackout = false
            setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            detector?.intervalMs = AWAKE_INTERVAL
            refresh()
        }
        if (view.mood == GuardMood.SLEEPING) view.mood = GuardMood.IDLE
    }

    /** Lower refresh rate (the G Power runs at 120 Hz by default): a static pixel scene doesn't need it. */
    private fun preferLowRefresh() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val d = display ?: return
        val cur = d.mode
        val low = d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .minByOrNull { kotlin.math.abs(it.refreshRate - 60f) } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = low.modeId }
    }

    // ---------------- distance calibration ----------------

    fun calibrate() {
        wake()
        sentry.announce("Stand where a visitor would stand, and look at me. Hold still.")
        lifecycleScope.launch {
            delay(5000)
            detector?.startCalibration(4000)
        }
    }

    private fun finishCalibration(frac: Float?) {
        if (frac == null) {
            sentry.announce("I could not see your face. Try again, closer to the light.")
            return
        }
        prefs.minFaceFrac = frac * 0.85f
        sentry.announce("Done. I shall speak to anyone who comes that close.")
    }

    private fun setBrightness(v: Float) {
        window.attributes = window.attributes.apply { screenBrightness = v }
    }

    // ---------------- owner menu: secret corner pattern ----------------
    // Tap the corners in order: top-left, top-right, bottom-right, bottom-left, within 5 seconds.

    private val secret = intArrayOf(0, 1, 2, 3)   // 0=TL 1=TR 2=BR 3=BL
    private var secretStep = 0
    private var secretStart = 0L

    private fun cornerOf(x: Float, y: Float): Int {
        val zx = view.width * 0.22f; val zy = view.height * 0.15f
        val left = x < zx; val right = x > view.width - zx
        val top = y < zy; val bottom = y > view.height - zy
        return when {
            top && left -> 0; top && right -> 1; bottom && right -> 2; bottom && left -> 3; else -> -1
        }
    }

    private fun setupOwnerGesture() {
        view.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                wake()
                val now = SystemClock.uptimeMillis()
                if (secretStep > 0 && now - secretStart > 5000) secretStep = 0
                val corner = cornerOf(e.x, e.y)
                when {
                    corner == secret[secretStep] -> {
                        if (secretStep == 0) secretStart = now
                        secretStep++
                        if (secretStep == secret.size) { secretStep = 0; ownerMenu() }
                    }
                    corner == secret[0] -> { secretStep = 1; secretStart = now }
                    else -> secretStep = 0
                }
            } else if (e.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    // ---------------- kiosk (app pinning) ----------------

    private val isPinned: Boolean
        get() = (getSystemService(ACTIVITY_SERVICE) as ActivityManager).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    private fun enterKiosk() {
        prefs.kiosk = true
        if (!isPinned) runCatching { startLockTask() }
    }

    private fun exitToHome() {
        prefs.kiosk = false
        if (isPinned) runCatching { stopLockTask() }
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onResume() {
        super.onResume()
        if (prefs.kiosk && !isPinned) runCatching { startLockTask() }
    }

    private fun ownerMenu() {
        if (prefs.pin.isNotBlank()) {
            val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
            AlertDialog.Builder(this).setTitle("Owner PIN").setView(input)
                .setPositiveButton("OK") { _, _ -> if (input.text.toString() == prefs.pin) showMenu() }
                .setNegativeButton("Cancel", null).show()
        } else showMenu()
    }

    private fun showMenu() {
        val items = Status.values().map { "Status: ${it.label}" } +
            listOf("Set note…", "Messages (${prefs.messages().size})…", "Settings…", "Test visitor conversation", "Voice…", "Calibrate visitor distance",
                if (isPinned) "Kiosk mode: ON (locked)" else "Turn on kiosk mode (lock to this app)",
                "Exit to home screen")
        AlertDialog.Builder(this).setTitle("Office Sentry  •  ${ControlServer.localIp() ?: "?"}:$PORT")
            .setItems(items.toTypedArray()) { _, i ->
                val n = Status.values().size
                when {
                    i < n -> { prefs.status = Status.values()[i]; refresh(); view.bubbleText = sentry.idleText() }
                    i == n -> noteDialog()
                    i == n + 1 -> messagesDialog()
                    i == n + 2 -> settingsDialog()
                    i == n + 3 -> sentry.onVisitor(Sighting(1, emptyList(), null))
                    i == n + 4 -> engineDialog()
                    i == n + 5 -> calibrate()
                    i == n + 6 -> if (!isPinned) enterKiosk()
                    else -> exitToHome()
                }
            }.show()
        if (prefs.pin.isBlank()) Toast.makeText(this, "Tip: set a PIN in Settings so only you can open this menu.", Toast.LENGTH_LONG).show()
    }

    // ---------------- voice: engine, then voice, with spoken preview ----------------

    private fun engineDialog() {
        val engines = voice.engines
        val labels = listOf("Phone default") + engines.map { it.label }
        val cur = engines.indexOfFirst { it.name == prefs.ttsEngine } + 1
        AlertDialog.Builder(this).setTitle("Speech engine")
            .setSingleChoiceItems(labels.toTypedArray(), cur) { d, i ->
                d.dismiss()
                val pkg = if (i == 0) "" else engines[i - 1].name
                if (pkg != prefs.ttsEngine) {
                    prefs.ttsEngine = pkg; prefs.voiceName = ""
                    voice.buildTts(); desk.buildTts()
                }
                lifecycleScope.launch { delay(1500); voicePickDialog() }
            }
            .setNegativeButton("Cancel", null).show()
        if (engines.size <= 1) Toast.makeText(this,
            "For free AI voices, install VoxSherpa TTS (Play Store) or SherpaTTS (F-Droid) and download a Piper voice.",
            Toast.LENGTH_LONG).show()
    }

    private fun voicePickDialog() {
        val list = voice.voiceList()
        if (list.isEmpty()) {
            Toast.makeText(this, "This engine has no voices yet. Open the engine's app and download one.", Toast.LENGTH_LONG).show()
            return
        }
        val labels = listOf("Engine default") + list.map { it.label }
        val cur = list.indexOfFirst { it.name == prefs.voiceName } + 1
        AlertDialog.Builder(this).setTitle("Garrick's voice (tap to hear)")
            .setSingleChoiceItems(labels.toTypedArray(), cur) { _, i ->
                prefs.voiceName = if (i == 0) "" else list[i - 1].name
                sentry.announce("Halt. State thy business, traveler.")
            }
            .setPositiveButton("Keep this voice", null).show()
    }

    private fun noteDialog() {
        val input = EditText(this).apply { setText(prefs.note); hint = "e.g. Back at 2:30" }
        AlertDialog.Builder(this).setTitle("Status note").setView(input)
            .setPositiveButton("Save") { _, _ -> prefs.note = input.text.toString(); refresh() }
            .setNeutralButton("Clear") { _, _ -> prefs.note = ""; refresh() }
            .show()
    }

    private fun messagesDialog() {
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.US)
        val text = prefs.messages().reversed().joinToString("\n\n") { "${fmt.format(Date(it.time))}\n${it.text}" }.ifEmpty { "No messages." }
        AlertDialog.Builder(this).setTitle("Messages").setMessage(text)
            .setPositiveButton("Close", null)
            .setNegativeButton("Clear all") { _, _ -> prefs.clearMessages(); refresh() }
            .show()
    }

    private fun settingsDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        fun field(hint: String, value: String, secret: Boolean = false) = EditText(this).apply {
            this.hint = hint; setText(value)
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            box.addView(this)
        }
        val key = field("Claude API key (blank = keep)", "", true)
        val owner = field("Your name", prefs.ownerName)
        val guard = field("Guard name", prefs.guardName)
        val model = field("Claude model", prefs.model)
        val pin = field("Owner PIN (blank = keep, - = remove)", "", true)
        val front = CheckBox(this).apply { text = "Use front camera"; isChecked = prefs.frontCamera; box.addView(this) }
        val img = CheckBox(this).apply { text = "Send snapshot to Claude"; isChecked = prefs.sendImage; box.addView(this) }
        val listen = CheckBox(this).apply { text = "Two-way conversation"; isChecked = prefs.listenEnabled; box.addView(this) }

        AlertDialog.Builder(this).setTitle("Settings").setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                if (key.text.isNotBlank()) prefs.apiKey = key.text.toString()
                prefs.ownerName = owner.text.toString(); prefs.guardName = guard.text.toString()
                prefs.model = model.text.toString()
                pin.text.toString().let { if (it == "-") prefs.pin = "" else if (it.isNotBlank()) prefs.pin = it }
                val camChanged = front.isChecked != prefs.frontCamera
                prefs.frontCamera = front.isChecked; prefs.sendImage = img.isChecked; prefs.listenEnabled = listen.isChecked
                if (camChanged) startCamera()
                refresh(); view.bubbleText = sentry.idleText()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------------- lifecycle ----------------

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onDestroy() {
        server?.stop(); detector?.shutdown(); voice.shutdown(); desk.shutdown()
        super.onDestroy()
    }

    companion object {
        const val PORT = 8080
        const val AWAKE_INTERVAL = 350L
        const val SLEEP_INTERVAL = 900L
    }
}
