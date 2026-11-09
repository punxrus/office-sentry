package com.peak.officesentry

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 * Draws the whole kiosk: status banner, pixel-art scene with the guard, speech bubble.
 * The scene is a 48x52 "virtual pixel" grid scaled up with crisp rectangles.
 */
class GuardView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    // ---- state pushed in from the activity ----
    var status: Status = Status.OPEN; set(v) { field = v; invalidate() }
    var note: String = ""; set(v) { field = v; invalidate() }
    var mood: GuardMood = GuardMood.IDLE; set(v) { field = v; invalidate() }
    var bubbleText: String = ""; set(v) { field = v; invalidate() }
    var messageCount: Int = 0; set(v) { field = v; invalidate() }
    var footer: String = ""; set(v) { field = v; invalidate() }

    private val px = Paint().apply { isAntiAlias = false }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = Color.WHITE
    }

    private var nextBlink = SystemClock.uptimeMillis() + 2500
    private var blinkUntil = 0L

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 110)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(ticker) }
    override fun onDetachedFromWindow() { removeCallbacks(ticker); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        c.drawColor(Color.rgb(20, 16, 28))

        // ---------- banner ----------
        val bannerH = h * 0.16f
        fill.color = status.color
        c.drawRoundRect(RectF(w * 0.04f, h * 0.02f, w * 0.96f, bannerH), 18f, 18f, fill)
        fill.color = Color.argb(70, 0, 0, 0)
        c.drawRect(w * 0.04f, bannerH - 10f, w * 0.96f, bannerH, fill)
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
        drawBackground(c, ox, oy, p, now)
        drawGuard(c, ox, oy, p, now)

        // ---------- speech bubble ----------
        val bTop = sceneTop + sceneMaxH + h * 0.01f
        val bRect = RectF(w * 0.04f, bTop, w * 0.96f, h * 0.95f)
        fill.color = Color.rgb(250, 246, 232)
        c.drawRoundRect(bRect, 22f, 22f, fill)
        fill.color = Color.rgb(26, 20, 32)
        fill.style = Paint.Style.STROKE; fill.strokeWidth = 6f
        c.drawRoundRect(bRect, 22f, 22f, fill)
        fill.style = Paint.Style.FILL
        // tail pointing up at the guard
        val tail = android.graphics.Path().apply {
            moveTo(w / 2 - 24, bTop + 3); lineTo(w / 2, bTop - 26); lineTo(w / 2 + 24, bTop + 3); close()
        }
        fill.color = Color.rgb(250, 246, 232); c.drawPath(tail, fill)

        val text = when (mood) {
            GuardMood.LISTENING -> if (bubbleText.isBlank()) "I'm listening..." else bubbleText
            GuardMood.THINKING -> "Hmm" + ".".repeat(((now / 300) % 4).toInt())
            else -> bubbleText
        }
        val tp = TextPaint(textPaint).apply {
            color = Color.rgb(26, 20, 32); textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val innerW = (bRect.width() - 56).toInt()
        val innerH = bRect.height() - 40
        var size = h * 0.034f
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
            // pulsing mic dot
            val r = 10f + 5f * ((now / 120) % 4)
            fill.color = Color.rgb(205, 50, 50)
            c.drawCircle(bRect.right - 34, bRect.top + 34, r, fill)
        }

        // ---------- footer: messages badge + hint ----------
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = h * 0.018f
        textPaint.color = Color.rgb(170, 160, 190)
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

    private fun pix(c: Canvas, ox: Float, oy: Float, p: Float, x: Int, y: Int, color: Int) {
        px.color = color
        c.drawRect(ox + x * p, oy + y * p, ox + (x + 1) * p, oy + (y + 1) * p, px)
    }

    private fun rect(c: Canvas, ox: Float, oy: Float, p: Float, x: Int, y: Int, w: Int, h: Int, color: Int) {
        px.color = color
        c.drawRect(ox + x * p, oy + y * p, ox + (x + w) * p, oy + (y + h) * p, px)
    }

    private fun drawBackground(c: Canvas, ox: Float, oy: Float, p: Float, now: Long) {
        // stone wall
        rect(c, ox, oy, p, 0, 0, VW.toInt(), 50, Color.rgb(58, 52, 70))
        val mortar = Color.rgb(40, 34, 50)
        val hi = Color.rgb(74, 66, 88)
        for (row in 0 until 13) {
            val y = row * 4
            rect(c, ox, oy, p, 0, y + 3, VW.toInt(), 1, mortar)
            val shift = if (row % 2 == 0) 0 else 4
            var x = -shift
            while (x < VW) {
                if (x >= 0) rect(c, ox, oy, p, x, y, 1, 3, mortar)
                if (x + 1 in 0 until VW.toInt()) rect(c, ox, oy, p, x + 1, y, 6, 1, hi)
                x += 8
            }
        }
        // door with arch
        val doorC = Color.rgb(96, 58, 32); val doorD = Color.rgb(66, 38, 20); val iron = Color.rgb(30, 26, 34)
        for (y in 6 until 50) {
            val inset = when (y) { 6 -> 6; 7 -> 4; 8 -> 2; 9 -> 1; else -> 0 }
            rect(c, ox, oy, p, 13 + inset, y, 22 - inset * 2, 1, doorC)
        }
        for (x in listOf(17, 21, 25, 29)) rect(c, ox, oy, p, x, 9, 1, 41, doorD)
        rect(c, ox, oy, p, 13, 18, 22, 2, iron); rect(c, ox, oy, p, 13, 38, 22, 2, iron)
        // torches with flicker
        val flick = ((now / 140) % 3).toInt()
        for (tx in listOf(5, 42)) {
            rect(c, ox, oy, p, tx, 20, 1, 6, Color.rgb(80, 50, 30))
            rect(c, ox, oy, p, tx - 1, 19, 3, 1, Color.rgb(50, 44, 56))
            val fh = 3 + flick % 2
            rect(c, ox, oy, p, tx - 1, 19 - fh, 3, fh, Color.rgb(240, 120, 30))
            rect(c, ox, oy, p, tx, 18 - fh + (flick == 2).compareTo(false), 1, fh, Color.rgb(255, 220, 90))
        }
        // floor
        rect(c, ox, oy, p, 0, 50, VW.toInt(), 2, Color.rgb(36, 30, 44))
        rect(c, ox, oy, p, 0, 50, VW.toInt(), 1, Color.rgb(84, 74, 96))
    }

    private fun drawGuard(c: Canvas, ox: Float, oy: Float, p: Float, now: Long) {
        val sleeping = mood == GuardMood.SLEEPING
        val bob = if (sleeping) ((now / 900) % 2).toInt() else if (mood == GuardMood.ALERT) 0 else ((now / 600) % 2).toInt()
        val gx = 8  // guard sprite origin x in scene
        val gy = 15 + bob

        for ((y, row) in BODY.withIndex()) for ((x, ch) in row.withIndex()) {
            val col = PAL[ch] ?: continue
            pix(c, ox, oy, p, gx + x, gy + y, col)
        }

        // eyes
        if (now > nextBlink) { blinkUntil = now + 140; nextBlink = now + 2500 + Random.nextLong(3500) }
        val blinking = now < blinkUntil
        val stern = status.blocking && mood != GuardMood.SPEAKING
        for (ex in intArrayOf(10, 20)) {
            if (sleeping || blinking) {
                for (dx in 0..1) pix(c, ox, oy, p, gx + ex + dx, gy + 14, K)
            } else {
                pix(c, ox, oy, p, gx + ex, gy + 12, Color.WHITE); pix(c, ox, oy, p, gx + ex + 1, gy + 12, K)
                for (dy in 13..14) for (dx in 0..1) pix(c, ox, oy, p, gx + ex + dx, gy + dy, K)
                if (mood == GuardMood.ALERT || mood == GuardMood.LISTENING) {
                    // wide-eyed: extra highlight
                    pix(c, ox, oy, p, gx + ex + 1, gy + 13, Color.WHITE)
                }
            }
        }
        if (stern && !sleeping) { // angled brows
            pix(c, ox, oy, p, gx + 9, gy + 11, K); pix(c, ox, oy, p, gx + 10, gy + 11, K)
            pix(c, ox, oy, p, gx + 22, gy + 11, K); pix(c, ox, oy, p, gx + 21, gy + 11, K)
        }
        // cheeks
        pix(c, ox, oy, p, gx + 9, gy + 15, BLUSH); pix(c, ox, oy, p, gx + 22, gy + 15, BLUSH)

        // mouth
        val talking = mood == GuardMood.SPEAKING && (now / 130) % 2 == 0L
        when {
            talking -> {
                for (x in 14..17) pix(c, ox, oy, p, gx + x, gy + 16, K)
                pix(c, ox, oy, p, gx + 15, gy + 17, MOUTH); pix(c, ox, oy, p, gx + 16, gy + 17, MOUTH)
            }
            mood == GuardMood.THINKING -> { pix(c, ox, oy, p, gx + 15, gy + 16, K) }
            stern -> for (x in 14..17) pix(c, ox, oy, p, gx + x, gy + 16, K)
            else -> { // smile
                pix(c, ox, oy, p, gx + 14, gy + 15, K); pix(c, ox, oy, p, gx + 17, gy + 15, K)
                pix(c, ox, oy, p, gx + 15, gy + 16, K); pix(c, ox, oy, p, gx + 16, gy + 16, K)
            }
        }

        // spear
        val shaft = Color.rgb(107, 66, 38); val steel = Color.rgb(200, 208, 216)
        val steelD = Color.rgb(138, 150, 164); val gold = Color.rgb(240, 192, 64)
        if (status.blocking && !sleeping) {
            // held horizontally across the body: "none shall pass"
            val y = gy + 25
            for (x in 1..38) pix(c, ox, oy, p, gx - 4 + x + 4, y, shaft)
            val bx = gx - 4
            pix(c, ox, oy, p, bx + 1, y, steel); pix(c, ox, oy, p, bx + 2, y - 1, steel); pix(c, ox, oy, p, bx + 2, y, Color.WHITE)
            pix(c, ox, oy, p, bx + 2, y + 1, steelD); pix(c, ox, oy, p, bx + 3, y - 1, steel); pix(c, ox, oy, p, bx + 3, y, steel)
            pix(c, ox, oy, p, bx + 3, y + 1, steelD); pix(c, ox, oy, p, bx + 4, y, steelD)
            for (dy in -1..1) pix(c, ox, oy, p, bx + 5, y + dy, gold)
        } else {
            val x = gx + 27
            val top = gy + if (sleeping) 3 else 0
            for (y in top + 5..gy + 34) pix(c, ox, oy, p, x, y, shaft)
            pix(c, ox, oy, p, x, top, steel)
            pix(c, ox, oy, p, x - 1, top + 1, steel); pix(c, ox, oy, p, x, top + 1, Color.WHITE); pix(c, ox, oy, p, x + 1, top + 1, steelD)
            pix(c, ox, oy, p, x - 1, top + 2, steel); pix(c, ox, oy, p, x, top + 2, steel); pix(c, ox, oy, p, x + 1, top + 2, steelD)
            pix(c, ox, oy, p, x, top + 3, steelD)
            for (dx in -1..1) pix(c, ox, oy, p, x + dx, top + 4, gold)
        }

        // mood icons above head
        when (mood) {
            GuardMood.ALERT -> { // "!"
                rect(c, ox, oy, p, gx + 26, gy - 1 - 7, 2, 5, Color.rgb(255, 220, 90))
                rect(c, ox, oy, p, gx + 26, gy - 1, 2, 2, Color.rgb(255, 220, 90))
            }
            GuardMood.SLEEPING -> {
                val zy = gy - 4 - ((now / 500) % 3).toInt()
                drawZ(c, ox, oy, p, gx + 24, zy)
            }
            else -> {}
        }
    }

    private fun drawZ(c: Canvas, ox: Float, oy: Float, p: Float, x: Int, y: Int) {
        val col = Color.rgb(200, 200, 230)
        rect(c, ox, oy, p, x, y, 4, 1, col)
        pix(c, ox, oy, p, x + 2, y + 1, col); pix(c, ox, oy, p, x + 1, y + 2, col)
        rect(c, ox, oy, p, x, y + 3, 4, 1, col)
    }

    companion object {
        const val VW = 48f
        const val VH = 52f
        val K = Color.rgb(26, 20, 32)
        val BLUSH = Color.rgb(240, 140, 140)
        val MOUTH = Color.rgb(122, 42, 42)

        val PAL = mapOf(
            'K' to K, 'S' to Color.rgb(200, 208, 216), 's' to Color.rgb(138, 150, 164),
            'd' to Color.rgb(86, 96, 110), 'W' to Color.WHITE, 'P' to Color.rgb(216, 58, 58),
            'p' to Color.rgb(142, 31, 42), 'F' to Color.rgb(242, 194, 155), 'f' to Color.rgb(201, 141, 107),
            'B' to Color.rgb(47, 95, 179), 'b' to Color.rgb(29, 60, 120), 'G' to Color.rgb(240, 192, 64),
            'g' to Color.rgb(176, 128, 42), 'L' to Color.rgb(107, 66, 38), 'l' to Color.rgb(67, 40, 26),
        )

        /** Original 32x35 chibi guard. Eyes, mouth and spear are drawn as overlays. */
        val BODY = arrayOf(
            "...............KKK..............",
            "..............KPPPK.............",
            ".............KPPPPpK............",
            ".............KPPPppK............",
            "..........KKKKKPpKKKKK..........",
            "........KKSSSSSKKSSSSsKK........",
            ".......KSSSSSSSSSSSSSSssK.......",
            "......KSSSWWSSSSSSSSSSsssK......",
            "......KSSWSSSSSSSSSSSSsssK......",
            ".....KSSSSSSSSSSSSSSSSSsssK.....",
            ".....KddddddddddddddddddddK.....",
            ".....KsKFFFFFFFFFFFFFFFFKsK.....",
            ".....KsKFFFFFFFFFFFFFFFFKsK.....",
            ".....KsKFFFFFFFFFFFFFFFFKsK.....",
            ".....KsKFFFFFFFFFFFFFFFFKsK.....",
            ".....KsKFFFFFFFFFFFFFFFFKsK.....",
            ".....KsKfFFFFFFFFFFFFFFfKsK.....",
            ".....KdKKfFFFFFFFFFFFFfKKdK.....",
            "......KKdKKKKKKKKKKKKKKdKK......",
            ".......KKsssKKKKKKKKsssKK.......",
            "....KKKSSSsKBBBBBBBBKsSSSKKK....",
            "...KSSSSssKBBBBGGBBBBKssSSSSK...",
            "...KSsssssKBBBGggGBBBKsssssSK...",
            "...KKKKKKKKBBBBGGBBBBKKKKKKKK...",
            "....KFFFKKBBBBBBBBBBBBKKFFFK....",
            "....KFFfKBBBBBBBBBBBBBBKfFFK....",
            "....KKKKKLLLLLLGGLLLLLLKKKKK....",
            ".........KBBBBBbbBBBBBK.........",
            ".........KBBBBbbbbBBBBK.........",
            ".........KbBBBbKKbBBBbK.........",
            "..........KKKKKK.KKKKKK.........",
            "..........KssssK.KssssK.........",
            "..........KsSSsK.KsSSsK.........",
            ".........KLLLLLK.KLLLLLK........",
            ".........KKKKKKK.KKKKKKK........",
        )
    }
}
