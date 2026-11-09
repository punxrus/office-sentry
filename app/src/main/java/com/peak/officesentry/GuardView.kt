package com.peak.officesentry

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Kiosk screen: status banner, framed pixel portrait of the veteran guard, speech bubble.
 * The scene is a 64x60 "virtual pixel" grid; the portrait itself is 48x48.
 */
class GuardView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var status: Status = Status.OPEN; set(v) { field = v; invalidate() }
    var note: String = ""; set(v) { field = v; invalidate() }
    var mood: GuardMood = GuardMood.IDLE; set(v) { field = v; invalidate() }
    var bubbleText: String = ""; set(v) { field = v; invalidate() }
    var messageCount: Int = 0; set(v) { field = v; invalidate() }
    var footer: String = ""; set(v) { field = v; invalidate() }

    private val px = Paint().apply { isAntiAlias = false }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        color = Color.WHITE
    }

    // working copy of the portrait, rebuilt each frame from BASE + expression overlays
    private val face = Array(N) { CharArray(N) }

    private var nextBlink = SystemClock.uptimeMillis() + 2500
    private var blinkUntil = 0L
    private var look = 0
    private var nextLook = SystemClock.uptimeMillis() + 4000

    /** Screen-sleep: draw pure black and stop the animation loop entirely (saves CPU/GPU). */
    var blackout: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            removeCallbacks(ticker)
            if (!v) post(ticker)
            invalidate()
        }

    private val ticker = object : Runnable {
        override fun run() {
            if (blackout) return
            invalidate(); postDelayed(this, 110)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(ticker) }
    override fun onDetachedFromWindow() { removeCallbacks(ticker); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        if (blackout) { c.drawColor(Color.BLACK); return }
        val w = width.toFloat(); val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        c.drawColor(Color.rgb(18, 14, 22))

        // ---------- banner ----------
        val bannerH = h * 0.15f
        fill.color = status.color
        c.drawRoundRect(RectF(w * 0.04f, h * 0.02f, w * 0.96f, bannerH), 14f, 14f, fill)
        fill.style = Paint.Style.STROKE; fill.strokeWidth = 5f; fill.color = GOLD
        c.drawRoundRect(RectF(w * 0.04f, h * 0.02f, w * 0.96f, bannerH), 14f, 14f, fill)
        fill.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = fitText(status.label, w * 0.86f, bannerH * 0.42f)
        val labelY = if (note.isBlank()) h * 0.02f + bannerH * 0.55f else h * 0.02f + bannerH * 0.42f
        c.drawText(status.label, w / 2, labelY, textPaint)
        if (note.isNotBlank()) {
            textPaint.textSize = fitText(note, w * 0.86f, bannerH * 0.2f)
            c.drawText(note, w / 2, h * 0.02f + bannerH * 0.78f, textPaint)
        }

        // ---------- scene ----------
        val sceneTop = bannerH + h * 0.02f
        val sceneMaxH = h * 0.56f
        val p = max(1f, min(w / VW, sceneMaxH / VH).toInt().toFloat())
        val ox = (w - VW * p) / 2f
        val oy = sceneTop + (sceneMaxH - VH * p) / 2f
        drawWall(c, ox, oy, p, now)
        drawPortrait(c, ox, oy, p, now)

        // ---------- speech bubble (parchment) ----------
        val bTop = sceneTop + sceneMaxH + h * 0.015f
        val bRect = RectF(w * 0.04f, bTop, w * 0.96f, h * 0.95f)
        fill.color = Color.rgb(238, 224, 190)
        c.drawRoundRect(bRect, 16f, 16f, fill)
        fill.style = Paint.Style.STROKE; fill.strokeWidth = 6f; fill.color = Color.rgb(92, 62, 34)
        c.drawRoundRect(bRect, 16f, 16f, fill)
        fill.style = Paint.Style.FILL
        val tail = Path().apply {
            moveTo(w / 2 - 24, bTop + 3); lineTo(w / 2, bTop - 24); lineTo(w / 2 + 24, bTop + 3); close()
        }
        fill.color = Color.rgb(238, 224, 190); c.drawPath(tail, fill)

        val text = when (mood) {
            GuardMood.LISTENING -> if (bubbleText.isBlank()) "Speak up, then..." else bubbleText
            GuardMood.THINKING -> "Hrmm" + ".".repeat(((now / 300) % 4).toInt())
            else -> bubbleText
        }
        val tp = TextPaint(textPaint).apply {
            color = Color.rgb(48, 30, 18); textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        val innerW = (bRect.width() - 56).toInt()
        val innerH = bRect.height() - 40
        var size = h * 0.036f
        var layout: StaticLayout
        do {
            tp.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, innerW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(4f, 1f).build()
            size *= 0.9f
        } while (layout.height > innerH && size > 14f)
        c.save()
        c.translate(bRect.left + 28, bRect.top + (bRect.height() - layout.height) / 2f)
        layout.draw(c)
        c.restore()

        if (mood == GuardMood.LISTENING) {
            val r = 10f + 5f * ((now / 120) % 4)
            fill.color = Color.rgb(190, 40, 40)
            c.drawCircle(bRect.right - 34, bRect.top + 34, r, fill)
        }

        // ---------- footer ----------
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = h * 0.018f
        textPaint.color = Color.rgb(170, 160, 150)
        if (messageCount > 0) c.drawText("✉ $messageCount message(s)", w * 0.05f, h * 0.985f, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        c.drawText(footer, w * 0.95f, h * 0.985f, textPaint)
        textPaint.color = Color.WHITE
    }

    private fun fitText(t: String, maxW: Float, maxSize: Float): Float {
        var s = maxSize
        textPaint.textSize = s
        while (textPaint.measureText(t) > maxW && s > 10f) { s *= 0.92f; textPaint.textSize = s }
        return s
    }

    private fun rect(c: Canvas, ox: Float, oy: Float, p: Float, x: Int, y: Int, w: Int, h: Int, color: Int) {
        px.color = color
        c.drawRect(ox + x * p, oy + y * p, ox + (x + w) * p, oy + (y + h) * p, px)
    }

    // ---------------- background: torch-lit stone wall ----------------

    private fun drawWall(c: Canvas, ox: Float, oy: Float, p: Float, now: Long) {
        val vw = VW.toInt(); val vh = VH.toInt()
        rect(c, ox, oy, p, 0, 0, vw, vh, Color.rgb(52, 46, 60))
        val mortar = Color.rgb(34, 29, 40); val hi = Color.rgb(68, 60, 76)
        for (row in 0 until vh / 4 + 1) {
            val y = row * 4
            rect(c, ox, oy, p, 0, y + 3, vw, 1, mortar)
            var x = if (row % 2 == 0) 0 else -4
            while (x < vw) {
                if (x >= 0) rect(c, ox, oy, p, x, y, 1, 3, mortar)
                if (x + 1 >= 0) rect(c, ox, oy, p, x + 1, y, 6, 1, hi)
                x += 8
            }
        }
        // torches either side of the portrait
        val flick = ((now / 140) % 3).toInt()
        for (tx in intArrayOf(3, 60)) {
            rect(c, ox, oy, p, tx, 24, 1, 7, Color.rgb(80, 50, 30))
            rect(c, ox, oy, p, tx - 1, 23, 3, 1, Color.rgb(40, 36, 44))
            val fh = 3 + flick % 2
            rect(c, ox, oy, p, tx - 1, 23 - fh, 3, fh, Color.rgb(240, 120, 30))
            rect(c, ox, oy, p, tx, 22 - fh + if (flick == 2) 1 else 0, 1, fh, Color.rgb(255, 220, 90))
        }
    }

    // ---------------- framed portrait ----------------

    private fun drawPortrait(c: Canvas, ox: Float, oy: Float, p: Float, now: Long) {
        val fx = FX; val fy = FY   // portrait top-left in scene coords
        // frame: dark iron + gold trim, LoL-style portrait plate
        rect(c, ox, oy, p, fx - 3, fy - 3, N + 6, N + 6, Color.rgb(28, 22, 18))
        rect(c, ox, oy, p, fx - 2, fy - 2, N + 4, N + 4, GOLD_D)
        rect(c, ox, oy, p, fx - 1, fy - 1, N + 2, N + 2, GOLD)
        for ((cx, cy) in listOf(fx - 3 to fy - 3, fx + N to fy - 3, fx - 3 to fy + N, fx + N to fy + N)) {
            rect(c, ox, oy, p, cx, cy, 3, 3, GOLD); rect(c, ox, oy, p, cx + 1, cy + 1, 1, 1, Color.rgb(170, 40, 40))
        }
        // backdrop with flickering warm glow
        val glow = ((now / 160) % 3).toInt()
        rect(c, ox, oy, p, fx, fy, N, N, Color.rgb(30 + glow * 2, 24 + glow, 30))
        rect(c, ox, oy, p, fx, fy + 8, N, 30, Color.rgb(40 + glow * 3, 30 + glow, 32))

        buildFace(now)
        val breathe = if (mood == GuardMood.SLEEPING) ((now / 1100) % 2).toInt() else ((now / 1400) % 2).toInt()
        for (y in 0 until N) {
            val ty = y + breathe
            if (ty >= N) continue
            val row = face[y]
            for (x in 0 until N) {
                val col = PAL[row[x]] ?: continue
                rect(c, ox, oy, p, fx + x, fy + ty, 1, 1, col)
            }
        }

        // mood icons inside the frame, top-right
        when (mood) {
            GuardMood.ALERT -> {
                rect(c, ox, oy, p, fx + 42, fy + 2, 2, 6, Color.rgb(255, 220, 90))
                rect(c, ox, oy, p, fx + 42, fy + 9, 2, 2, Color.rgb(255, 220, 90))
            }
            GuardMood.SLEEPING -> drawZ(c, ox, oy, p, fx + 40, fy + 2 + ((now / 500) % 3).toInt())
            else -> {}
        }
    }

    private fun buildFace(now: Long) {
        for (y in 0 until N) BASE[y].toCharArray(face[y])

        // blink + idle glances
        if (now > nextBlink) { blinkUntil = now + 150; nextBlink = now + 2800 + Random.nextLong(3500) }
        if (now > nextLook) { look = listOf(0, 0, -1, 1).random(); nextLook = now + 2500 + Random.nextLong(4000) }
        val blinking = now < blinkUntil
        val stern = status.blocking

        val eyeMode = when {
            mood == GuardMood.SLEEPING || blinking -> EyeMode.CLOSED
            mood == GuardMood.ALERT -> EyeMode.WIDE
            else -> EyeMode.OPEN
        }
        val eyeLook = when (mood) {
            GuardMood.THINKING -> 1
            GuardMood.IDLE -> look
            else -> 0
        }
        overlayShade = true
        eyes(eyeMode, eyeLook)
        brows(stern && mood != GuardMood.SLEEPING)
        val talking = mood == GuardMood.SPEAKING && (now / 140) % 2 == 0L
        mouth(when {
            talking -> MouthMode.OPEN
            stern && mood != GuardMood.SLEEPING -> MouthMode.FROWN
            else -> MouthMode.CLOSED
        })
        overlayShade = false
    }

    private var overlayShade = false

    /** Overlay pixels on the shadow side (x >= 31, dithered at 30) are darkened to match the torchlight. */
    private fun put(x: Int, y: Int, ch: Char) {
        if (x !in 0 until N || y !in 0 until N) return
        val c = if (overlayShade && (x >= 31 || (x == 30 && (x + y) % 2 == 0))) SHADOW[ch] ?: ch else ch
        face[y][x] = c
    }

    private enum class EyeMode { OPEN, CLOSED, WIDE }
    private enum class MouthMode { CLOSED, OPEN, FROWN }

    private fun eyes(mode: EyeMode, look: Int) {
        for (ex in intArrayOf(16, 27)) {
            val white = if (ex == 16) 'E' else 'e'   // right eye sits in shadow
            if (mode == EyeMode.CLOSED) {
                for (x in ex until ex + 5) { put(x, 23, 'f'); put(x, 24, 'n') }
                continue
            }
            val rows = if (mode == EyeMode.WIDE) intArrayOf(23, 24) else intArrayOf(24)
            val lid = rows.first() - 1
            for (x in ex until ex + 5) {
                put(x, lid, 'n')                          // heavy hooded lid
                for (y in rows) put(x, y, white)
                put(x, rows.last() + 1, 'f')               // lower lid
            }
            put(ex, rows.last(), 'f'); put(ex + 4, rows.last(), 'f')
            val ix = ex + 1 + look
            for (y in rows) { put(ix, y, 'I'); put(ix + 1, y, 'I') }
            put(ix + 1, rows.last(), 'K')                  // pupil
            if (mode == EyeMode.WIDE) put(ix, 23, 'H')     // catchlight
        }
    }

    private fun brows(stern: Boolean) {
        for ((i, x) in (14..21).withIndex()) {
            val y = if (!stern || i < 4) 20 else 21
            put(x, y, 'W'); put(x, y + 1, 'G')
            if (stern && i >= 4) put(x, 20, 'f')
        }
        for ((i, x) in (26..33).withIndex()) {
            val y = if (stern && i < 4) 21 else 20
            put(x, y, 'W'); put(x, y + 1, 'G')
            if (stern && i < 4) put(x, 20, 'f')
        }
        for (x in intArrayOf(15, 18, 20, 27, 29, 32)) put(x, 19, 'G')
        put(13, 20, 'G'); put(34, 20, 'G')
        put(29, 21, 'X')
    }

    private fun mouth(mode: MouthMode) {
        when (mode) {
            MouthMode.CLOSED -> for (x in 22..25) put(x, 33, 'g')
            MouthMode.OPEN -> {
                for (x in 21..26) { put(x, 32, 'M'); put(x, 33, 'M'); put(x, 34, 'M') }
                put(22, 32, 'T'); put(24, 32, 'T'); put(25, 32, 'T')
            }
            MouthMode.FROWN -> {
                for (x in 21..26) put(x, 33, 'M')
                put(20, 34, 'M'); put(27, 34, 'M')
            }
        }
    }

    private fun drawZ(c: Canvas, ox: Float, oy: Float, p: Float, x: Int, y: Int) {
        val col = Color.rgb(210, 210, 235)
        rect(c, ox, oy, p, x, y, 4, 1, col)
        rect(c, ox, oy, p, x + 2, y + 1, 1, 1, col); rect(c, ox, oy, p, x + 1, y + 2, 1, 1, col)
        rect(c, ox, oy, p, x, y + 3, 4, 1, col)
    }

    companion object {
        const val VW = 64f
        const val VH = 60f
        const val N = 48
        const val FX = 8
        const val FY = 6
        val GOLD = Color.rgb(212, 168, 64)
        val GOLD_D = Color.rgb(130, 96, 34)

        val SHADOW = mapOf('L' to 'F', 'F' to 'f', 'f' to 'n', 'X' to 'f', 'W' to 'G', 'G' to 'g', 'g' to 'q', 'T' to 'G')

        val PAL = mapOf(
            'K' to Color.rgb(24, 18, 22),
            'H' to Color.rgb(220, 224, 228),
            'S' to Color.rgb(168, 176, 186),
            's' to Color.rgb(118, 126, 138),
            'd' to Color.rgb(72, 76, 88),
            'R' to Color.rgb(150, 86, 46),
            'r' to Color.rgb(100, 58, 36),
            'C' to Color.rgb(108, 112, 120),
            'c' to Color.rgb(72, 75, 84),
            'h' to Color.rgb(146, 150, 158),
            'D' to Color.rgb(52, 54, 62),
            'F' to Color.rgb(212, 158, 118),
            'f' to Color.rgb(172, 116, 86),
            'n' to Color.rgb(122, 78, 58),
            'X' to Color.rgb(230, 148, 132),
            'W' to Color.rgb(232, 228, 220),
            'G' to Color.rgb(178, 174, 168),
            'g' to Color.rgb(116, 112, 110),
            'N' to Color.rgb(196, 112, 92),
            'M' to Color.rgb(58, 26, 26),
            'T' to Color.rgb(226, 220, 200),
            'E' to Color.rgb(214, 204, 184),
            'I' to Color.rgb(78, 112, 146),
            'L' to Color.rgb(234, 188, 146),
            'e' to Color.rgb(170, 160, 146),
            'q' to Color.rgb(84, 80, 80),
            'O' to Color.rgb(214, 150, 96),
            'k' to Color.rgb(40, 34, 40)
        )

        /** Original 48x48 veteran guard portrait (torchlit from the left). Eyes, brows and mouth are overlays. */
        val BASE = arrayOf(
            "...................KKKKKKKKKK...................",
            "................KKKSSSSHSsSSSKKK................",
            "..............KKSSSSSSSHSsSSSSsdKK..............",
            "............KKSSSSSSSSSHSsSSSSSdddKK............",
            "...........KSSSSHHHHSSSHSsSSSSsdddddK...........",
            "..........KSSSSHHHHHHSSHSsSSSSSRddddkK..........",
            ".........KSOSSSHHHHHHSSHSsSSSSsrRdddkkK.........",
            "........KSOSSSSSHHHHSSSHSsSSSSSdddddkkkK........",
            "........KSSSSSSSSSSSSSSHSsSSSSsdddddkkkK........",
            ".......KSOSSSSSSSSddSSSHSsSSSSSdddddkkkkK.......",
            ".......KSOSSSSSSSdSSSSSHSsSSSSsdddddkkkkK.......",
            "......KSOSSSRSSSSSSSSSSHSsSSSSSdddddrkkkkK......",
            "......KSOSSSSrSSSSSSSSSHSsSSRSsdddddkkkkkK......",
            "......KOsssssssssssssssHSssssssdddddkkkkkK......",
            "......KOsssssssssssssssHSsssssddddddkkkkkK......",
            "......KssssssssssssssssssssssssddddddkkkkK......",
            "......KssHsssHsssHsssHsssHsssHdddSddkskkkK......",
            "......KddddddddddddddddHsndddddkkkkkkkkkkK......",
            ".......KKCchCffffffffffHsnffffnnnnnCDDkKK.......",
            "........KcDCcfLLLfFfFFFHsnFFfFfffnncDkkK........",
            ".......KCcCChfLLLLLFFFFHsnFFFXfffnncckDkK.......",
            ".......KcDccCfLLLLFLFFFHsnFFFXFffnnDckkkK.......",
            "......KCcCchCfLnnnnnnnFSsnnnnnnnnnnCDDkDkK......",
            "......KcDcDCcfLLLLFLFFFSsnnFFFXffnncDkkkkK......",
            "......KcCcCChfLfLLLFFFFSsnnFFFffnnncckDkDK......",
            ".....KcDcDccCfLLLLFLFFFSsnnFFFFffnnDckkkkkK.....",
            ".....KcCcCchCfLfffffffFSsnnfnfnfnnnCDDkDkDK.....",
            ".....KDcDcDCcfLLfLFLfLFsdnnfFFFnfnncDkkkkkK.....",
            ".....KCcCcCChfLXLfLFFLNNNNFFFFnffnncckDkDkK.....",
            ".....KcDcDccCfLLXLFGWWGWWGWWGFFffnnDckkkkkK.....",
            ".....KcCcCchCfLLLWGWWGWWGWWGWWgffnnCDDkDkDK.....",
            ".....KDcDcDCcfLWGGWGGWGGWGGWGGWggnncDkkkkkK.....",
            ".....KCcCcCChfGGGGGGggggggggGGggggncckDkDkK.....",
            ".....KcDcDccCfGGGGGGGGGGWGGGGGGgggnDckkkkkK.....",
            ".....KcCcCchCfGGGGGGGGWGGGGGGGggggnCDDkDkDK.....",
            "......KcDcDCcfGGGGGGWGGGGGGGGWGgggncDkkkkK......",
            "......KcCcCChfFFgGWGGGGGGGGWGGgqfnncckDkDK......",
            "......KDcDccCfFFgGgGGgGGgWGgGGgqfnnDckkkkK......",
            ".......KcCchChCFFggGGgGWgGGgGGqffCcCDDkDK.......",
            "......KKDcDCcCcFFFgGGWGGgGGgGgFffcDcDkkkKK......",
            ".....KCcCcCChChCFFFgGgGGgGGggFffCcCcckDkDkK.....",
            ".....KcDcDccCcCcFFFFggGGgGWgFFFfcDcDckkkkkK.....",
            "....KCcCcrchChChChChCgGGWGghChcCcCcCDDrDkDkK....",
            "....KcDcDrDCcCcCcCcCcCgGggcCcCccDcDcDkrkkkkK....",
            "...KCcCcCrCChChChChChChChChChCCcCcCcckrkDkDkK...",
            "...KcDcDcDrcCcCcCcCcCcCcCcCcCcCDcDcDcrkkkkkkK...",
            "..KCcCcCcCrhChChChChChChChChChcCcCcCDrkDkDkDkK..",
            "..KcDcDcDcrCcCcCcCcCcCcCcCcCcCccDcDcDrkkkkkkkK..",
        )
    }
}
