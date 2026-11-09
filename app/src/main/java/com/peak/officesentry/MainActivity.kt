package com.peak.officesentry

import android.Manifest
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
import kotlinx.coroutines.delay
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

    @Volatile private var lastActivity = SystemClock.uptimeMillis()
    private var dimmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        view = GuardView(this)
        setContentView(view)
        hideSystemUi()

        voice = Voice(this)
        sentry = Sentry(prefs, voice, ClaudeClient(), lifecycleScope, this)
        refresh()
        view.bubbleText = sentry.idleText()
        setupOwnerGesture()

        server = ControlServer(
            prefs, PORT,
            onStatusChanged = { refresh(); if (!sentry.busy) view.bubbleText = sentry.idleText() },
            onSay = { wake(); sentry.announce(it) },
            onTest = { wake(); sentry.onVisitor(Sighting(1, emptyList(), null)) },
        ).also { it.start() }

        requestPerms()
        startIdleLoop()
    }

    // ---------------- permissions + camera ----------------

    private fun requestPerms() {
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
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
        detector = Detector(this,
            onVisitor = { s -> runOnUiThread { handleVisitor(s) } },
            onActivity = { lastActivity = SystemClock.uptimeMillis(); if (dimmed) runOnUiThread { wake() } },
        ).also { it.start(this, prefs.frontCamera) }
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

    override fun setMood(m: GuardMood) = runOnUiThread { view.mood = m }
    override fun setBubble(text: String) = runOnUiThread { view.bubbleText = text }
    override fun refresh() = runOnUiThread {
        view.status = prefs.status
        view.note = prefs.note
        view.messageCount = prefs.messages().size
        val ip = ControlServer.localIp()
        view.footer = if (ip != null) "$ip:$PORT" else "no Wi-Fi"
    }

    // ---------------- idle dimming / sleeping ----------------

    private fun startIdleLoop() = lifecycleScope.launch {
        while (true) {
            delay(5000)
            val idleMs = SystemClock.uptimeMillis() - lastActivity
            if (!sentry.busy && idleMs > 120_000) {
                if (view.mood == GuardMood.IDLE) view.mood = GuardMood.SLEEPING
                if (prefs.dimWhenIdle && !dimmed) setBrightness(0.05f).also { dimmed = true }
            }
            refresh()
        }
    }

    private fun wake() {
        lastActivity = SystemClock.uptimeMillis()
        if (view.mood == GuardMood.SLEEPING) view.mood = GuardMood.IDLE
        if (dimmed) { setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE); dimmed = false }
    }

    private fun setBrightness(v: Float) {
        window.attributes = window.attributes.apply { screenBrightness = v }
    }

    // ---------------- owner menu: hold the screen for 3 seconds ----------------

    private fun setupOwnerGesture() {
        val opener = Runnable { ownerMenu() }
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { wake(); v.postDelayed(opener, 3000) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(opener); v.performClick() }
            }
            true
        }
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
            listOf("Set note…", "Messages (${prefs.messages().size})…", "Settings…", "Test visitor conversation")
        AlertDialog.Builder(this).setTitle("Office Sentry  •  ${ControlServer.localIp() ?: "?"}:$PORT")
            .setItems(items.toTypedArray()) { _, i ->
                val n = Status.values().size
                when {
                    i < n -> { prefs.status = Status.values()[i]; refresh(); view.bubbleText = sentry.idleText() }
                    i == n -> noteDialog()
                    i == n + 1 -> messagesDialog()
                    i == n + 2 -> settingsDialog()
                    else -> sentry.onVisitor(Sighting(1, emptyList(), null))
                }
            }.show()
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
        server?.stop(); detector?.shutdown(); voice.shutdown()
        super.onDestroy()
    }

    companion object { const val PORT = 8080 }
}
