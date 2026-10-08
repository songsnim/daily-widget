package dev.daily.widget

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition.PRIMITIVE_CLICK
import android.os.VibrationEffect.Composition.PRIMITIVE_LOW_TICK
import android.os.VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
import android.os.VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
import android.os.VibrationEffect.Composition.PRIMITIVE_THUD
import android.os.VibrationEffect.Composition.PRIMITIVE_TICK
import android.os.VibratorManager
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class Tier { TASK, HABIT, WEEK, MONTH, YEAR, WAKE, WAKE_LOW, BED }

// What one tap earned. gain = the item's XP after its rarity roll, bonus = a completion bonus it unlocked
// (완벽한 하루 / 주간·월간 올클리어); both 0 when the note lies outside this week.
data class Show(
    val tier: Tier,
    val rarity: Xp.Rarity = Xp.Rarity.NORMAL,
    val gain: Int = 0,
    val bonus: Int = 0,
    val before: Int = 0,
    val all: Boolean = false,
    val title: String = "",
    val sub: String = "",
)

// The reward overlay: one Canvas particle engine shared by every tier so all rewards speak the same language
// (gold burst, XP orbs flying into the weekly bar); only the scale grows with importance. Port of the
// "보상 시스템 최종안" mockup.
class FxView(ctx: Context) : View(ctx) {
    private val dp = resources.displayMetrics.density
    private val rnd = java.util.Random()
    private fun r(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
    private fun <T> pick(a: List<T>) = a[rnd.nextInt(a.size)]
    private fun clamp(t: Float) = t.coerceIn(0f, 1f)
    private fun eo(t: Float) = 1 - (1 - clamp(t)).pow(3)
    private fun eio(x0: Float): Float { val x = clamp(x0); return if (x < .5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2 }
    private fun backOut(t: Float) = 1 + 2.7f * (t - 1).pow(3) + 1.7f * (t - 1).pow(2)

    private val gold = listOf(0xFFFFC94D, 0xFFFFE08A, 0xFFFFFFFF, 0xFFF2A93B, 0xFFFFF3D0).map { it.toInt() }
    private val accent = listOf(0xFFFFB547, 0xFFFF8A3D, 0xFFFFF3D0).map { it.toInt() }
    private val warm = listOf(0xFFFFD36E, 0xFFFFB547, 0xFFFFF3D6, 0xFFFF8A3D).map { it.toInt() }
    private val orbGold = 0xFFFFC94D.toInt()

    // ---------- particles ----------
    private enum class K { SPARK, CONF, RING, STAR, DOT, DUST }
    private class P(
        val k: K, var x: Float, var y: Float, var vx: Float = 0f, var vy: Float = 0f, val g: Float = 0f, val drag: Float = 1f,
        val life: Float, val size: Float = 0f, val color: Int, var delay: Float = 0f, var rot: Float = 0f, val vr: Float = 0f,
        val fs: Float = 0f, val r0: Float = 0f, val r1: Float = 0f, val lw: Float = 0f, val ph: Float = 0f,
    ) { var age = 0f; var flip = 0f }

    // ---------- timed entities (ms on the show's clock) ----------
    private class E(val at: Long, val dur: Long, val z: Int = 1, val draw: ((Canvas, Float, Long) -> Unit)? = null,
                    val start: (() -> Unit)? = null, val end: (() -> Unit)? = null) { var started = false; var ended = false }

    private val ps = ArrayList<P>()
    private val es = ArrayList<E>()
    private var t0 = 0L
    private var last = 0L
    private var onDone: (() -> Unit)? = null

    private fun add(p: P) { ps += p }
    private fun at(ms: Long, fn: () -> Unit) { es += E(ms, 1, start = fn) }
    private fun layer(at: Long, dur: Long, z: Int = 1, draw: (Canvas, Float, Long) -> Unit) { es += E(at, dur, z, draw) }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val heavy = Typeface.create(Typeface.DEFAULT, 800, false)
    private val medium = Typeface.create(Typeface.DEFAULT, 500, false)

    // ---------- haptics ----------
    private val vibrator = ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator

    private fun buzz(vararg steps: Triple<Int, Float, Int>) {
        val v = vibrator ?: return
        if (!v.areAllPrimitivesSupported(*steps.map { it.first }.toIntArray())) {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)); return
        }
        val c = VibrationEffect.startComposition()
        steps.forEach { c.addPrimitive(it.first, it.second, it.third) }
        v.vibrate(c.compose())
    }

    private fun tick(scale: Float = .4f) = buzz(Triple(PRIMITIVE_TICK, scale, 0))

    // ---------- weekly XP bar ----------
    private var showBar = false
    private var barBase = 0
    private var landed = 0
    private var pendingOrbs = 0
    private var lastLand = 0L
    private var bump = -1000L
    private var orbN = 0
    private var gainLabel = ""
    private var gainColor = Color.WHITE
    // The widget's own bar when FxActivity could place it on screen; otherwise a bar near the top.
    private var widgetBar: RectF? = null
    private val barW get() = widgetBar?.width() ?: (width * .8f)
    private val barX get() = widgetBar?.left ?: ((width - barW) / 2)
    private val barY get() = widgetBar?.centerY() ?: (rootWindowInsets?.systemWindowInsetTop?.plus(56 * dp) ?: (80 * dp))
    private fun shown() = barBase + landed
    private fun head() = PointF(barX + barW * min(1f, shown() / Xp.TARGET.toFloat()), barY)

    private fun drawBar(c: Canvas, now: Long) {
        if (!showBar) return
        val fadeIn = clamp(now / 220f)
        val fadeOut = if (pendingOrbs == 0 && lastLand > 0) 1 - clamp((now - lastLand - 700) / 300f) else 1f
        val a = min(fadeIn, fadeOut)
        if (a <= 0) return
        val v = shown()
        val over = v >= Xp.TARGET
        text.typeface = heavy; text.textSize = 12 * dp; text.textAlign = Paint.Align.LEFT
        text.setShadowLayer(3 * dp, 0f, dp, 0xCC000000.toInt())
        text.color = if (over) 0xFFFFD66B.toInt() else Color.WHITE; text.alpha = (255 * a).toInt()
        val label = "이번 주 · $v / ${Xp.TARGET} XP" + if (v > Xp.TARGET) " · 목표 초과" else ""
        val wb = widgetBar
        val h = wb?.height()?.coerceAtLeast(3 * dp) ?: (5 * dp)
        // On the widget the labels go just below its bottom edge, so they never cover the cells.
        val labelY = if (wb != null) barY + h / 2 + 17 * dp else barY - 9 * dp
        c.drawText(label, barX, labelY, text)
        text.textAlign = Paint.Align.RIGHT; text.color = gainColor; text.alpha = (255 * a).toInt()
        c.drawText(gainLabel, barX + barW, labelY, text)
        text.clearShadowLayer(); text.textAlign = Paint.Align.CENTER
        val track = RectF(barX, barY - h / 2, barX + barW, barY + h / 2)
        paint.reset(); paint.isAntiAlias = true
        // Over the widget the track is opaque: the widget bar underneath already shows the new total.
        if (wb != null) { paint.color = 0xFF34343C.toInt(); paint.alpha = (255 * a).toInt() }
        else { paint.color = 0x24FFFFFF; paint.alpha = (0x24 * a).toInt() }
        c.drawRoundRect(track, h, h, paint)
        val fillW = barW * min(1f, v / Xp.TARGET.toFloat())
        if (fillW > 0) {
            val lit = if (now - bump < 90) 1.5f else 1f
            paint.shader = LinearGradient(barX, 0f, barX + barW, 0f,
                intArrayOf(0xFFF29D1B.toInt(), 0xFFFFC94D.toInt(), 0xFFFFE8A3.toInt()), floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
            paint.alpha = (255 * a).toInt()
            paint.setShadowLayer(10 * dp * lit, 0f, 0f, 0xFFFFB21F.toInt())
            c.drawRoundRect(RectF(barX, track.top, barX + fillW, track.bottom), h, h, paint)
            paint.shader = null; paint.clearShadowLayer()
        }
    }

    // ---------- shared visual vocabulary ----------
    private fun burst(x: Float, y: Float, m: Float = 1f, pal: List<Int> = gold, acc: List<Int> = accent, conf: Boolean = true) {
        add(P(K.RING, x, y, life = .42f, color = pal[1], r0 = 6 * dp, r1 = 70 * m * dp, lw = 3 * dp))
        add(P(K.RING, x, y, life = .32f, color = Color.WHITE, r0 = 4 * dp, r1 = 46 * m * dp, lw = 1.5f * dp, delay = .05f))
        repeat((24 * m).toInt()) {
            val a = r(0f, 6.28f); val s = r(380f, 900f) * sqrt(m) * dp
            add(P(K.SPARK, x, y, cos(a) * s, sin(a) * s, 500 * dp, .9f, r(.3f, .6f), r(1.4f, 2.6f) * dp, pick(pal)))
        }
        if (conf) repeat((20 * m).toInt()) {
            val a = (-PI / 2).toFloat() + r(-1.25f, 1.25f); val s = r(260f, 640f) * sqrt(m) * dp
            add(P(K.CONF, x, y, cos(a) * s, sin(a) * s, 950 * dp, .93f, r(.8f, 1.15f), r(5f, 8f) * dp,
                if (rnd.nextFloat() < .3f) pick(acc) else pick(pal), rot = r(0f, 6.28f), vr = r(-12f, 12f), fs = r(8f, 16f)))
        }
        repeat((5 * m).toInt()) {
            val a = r(0f, 6.28f); val s = r(60f, 200f) * dp
            add(P(K.STAR, x, y, cos(a) * s, sin(a) * s, 0f, .94f, r(.5f, .85f), r(5f, 9f) * dp,
                if (rnd.nextFloat() < .5f) 0xFFFFD43B.toInt() else Color.WHITE, delay = r(0f, .12f)))
        }
    }

    private fun firework(x: Float, y: Float, n: Int, pal: List<Int>) {
        add(P(K.RING, x, y, life = .5f, color = Color.WHITE, r0 = 4 * dp, r1 = 110 * dp, lw = 2 * dp))
        repeat(n) { i ->
            val a = i * 6.283f / n + r(-.05f, .05f); val s = r(260f, 420f) * dp
            add(P(K.SPARK, x, y, cos(a) * s, sin(a) * s, 260 * dp, .955f, r(.8f, 1.1f), r(1.6f, 2.6f) * dp, pick(pal)))
        }
        repeat(n / 3) {
            val a = r(0f, 6.28f); val s = r(40f, 180f) * dp
            add(P(K.DOT, x, y, cos(a) * s, sin(a) * s, 120 * dp, .96f, r(.7f, 1.1f), r(1.5f, 3f) * dp, Color.WHITE))
        }
    }

    private fun cannon(left: Boolean, n: Int) {
        val x = if (left) -10f else width + 10f
        val y = height + 10f
        repeat(n) {
            val a = (-PI / 2).toFloat() + if (left) r(.25f, .75f) else -r(.25f, .75f)
            val s = r(900f, 1500f) * dp * max(.75f, height / (820 * dp))
            add(P(K.CONF, x, y, cos(a) * s, sin(a) * s, 820 * dp, .975f, r(1.5f, 2.1f), r(6f, 10f) * dp,
                if (rnd.nextFloat() < .35f) pick(accent) else pick(gold), r(0f, .18f), r(0f, 6.28f), r(-10f, 10f), r(6f, 14f)))
        }
    }

    private fun flash(at: Long, dur: Long, inner: Int, outer: Int) = layer(at, dur) { c, p, _ ->
        val a = if (p < .2f) p / .2f else 1 - (p - .2f) / .8f
        paint.reset(); paint.alpha = 255
        paint.shader = RadialGradient(width / 2f, height * .45f, max(width, height) * .7f,
            intArrayOf(inner, outer, Color.TRANSPARENT), floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
        paint.alpha = (255 * a).toInt()
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint); paint.shader = null
    }

    private fun edge(at: Long, dur: Long, color: Int) = layer(at, dur) { c, p, _ ->
        val a = if (p < .2f) p / .2f else 1 - (p - .2f) / .8f
        val d = 70 * dp
        paint.reset(); paint.alpha = 255
        val w = width.toFloat(); val h = height.toFloat()
        fun side(x0: Float, y0: Float, x1: Float, y1: Float, rect: RectF) {
            paint.shader = LinearGradient(x0, y0, x1, y1, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            paint.alpha = (255 * a).toInt(); c.drawRect(rect, paint)
        }
        side(0f, 0f, 0f, d, RectF(0f, 0f, w, d)); side(0f, h, 0f, h - d, RectF(0f, h - d, w, h))
        side(0f, 0f, d, 0f, RectF(0f, 0f, d, h)); side(w, 0f, w - d, 0f, RectF(w - d, 0f, w, h))
        paint.shader = null
    }

    private fun banner(at: Long, dur: Long, title: String, sub: String) = layer(at, dur) { c, p, _ ->
        val s = when { p < .14f -> .4f + .72f * (p / .14f); p < .24f -> 1.12f - .12f * ((p - .14f) / .1f); else -> 1f }
        val a = when { p < .14f -> p / .14f; p < .8f -> 1f; else -> 1 - (p - .8f) / .2f }
        val y = height * .36f - (if (p > .8f) (p - .8f) / .2f * 10 * dp else 0f)
        c.save(); c.translate(width / 2f, y); c.scale(s, s)
        text.typeface = heavy; text.textSize = 30 * dp; text.color = 0xFFFFF3D0.toInt(); text.alpha = (255 * a).toInt()
        text.setShadowLayer(18 * dp, 0f, 0f, 0xF2FFAA1E.toInt())
        c.drawText(title, 0f, 0f, text); text.clearShadowLayer()
        if (sub.isNotEmpty()) {
            text.textSize = 13 * dp
            val w = min(text.measureText(sub) + 24 * dp, width - 32 * dp)
            paint.reset(); paint.isAntiAlias = true; paint.alpha = 255
            paint.shader = LinearGradient(-w / 2, 0f, w / 2, 0f, 0xFFF29D1B.toInt(), 0xFFFFC94D.toInt(), Shader.TileMode.CLAMP)
            paint.alpha = (255 * a).toInt()
            c.drawRoundRect(RectF(-w / 2, 14 * dp, w / 2, 40 * dp), 13 * dp, 13 * dp, paint); paint.shader = null
            text.color = 0xFF2A1A00.toInt(); text.alpha = (255 * a).toInt()
            c.drawText(sub, 0f, 32 * dp, text)
        }
        c.restore()
    }

    // Two-line caption for wake / bed (warm or bedtime style).
    private fun caption(at: Long, dur: Long, title: String, sub: String, y: Float, bed: Boolean) = layer(at, dur) { c, p, _ ->
        val inEnd = if (bed) .35f else .22f
        val a = when { p < inEnd -> p / inEnd; p < .8f -> 1f; else -> 1 - (p - .8f) / .2f }
        val dy = if (p < inEnd) (1 - p / inEnd) * 12 * dp else 0f
        text.typeface = if (bed) medium else heavy; text.textSize = 21 * dp
        text.color = if (bed) 0xFFF3E3C3.toInt() else Color.WHITE; text.alpha = (255 * a).toInt()
        text.setShadowLayer(16 * dp, 0f, 0f, if (bed) 0x73E8B36A else 0xB3FFAA3C.toInt())
        c.drawText(title, width / 2f, y + dy, text); text.clearShadowLayer()
        text.typeface = medium; text.textSize = 14 * dp
        text.color = if (bed) 0xFFB8936A.toInt() else 0xFFFFE1A8.toInt(); text.alpha = (255 * a).toInt()
        c.drawText(sub, width / 2f, y + dy + 24 * dp, text)
    }

    private fun holo(now: Long, x0: Float, x1: Float): Shader {
        val cs = IntArray(7) { Color.HSVToColor(floatArrayOf(((now / 5f + it * 55) % 360), .55f, 1f)) }
        return LinearGradient(x0, 0f, x1, 0f, cs, null, Shader.TileMode.CLAMP)
    }

    // ---------- variable reward: reel -> reveal -> orbs into the bar ----------
    private fun reward(s: Show, from: PointF, orbs: IntArray, odur: Long, small: Boolean, orbFrom: PointF = from,
                       orbDelay: Long = 250, orbColor: Int = orbGold) {
        if (s.gain <= 0) return
        showBar = true
        val a = PointF(from.x.coerceIn(56 * dp, width - 56 * dp), from.y - (if (small) 22 else 32) * dp)
        val k = s.rarity
        val labels = listOf("+XP" to 0xFFFFE08A.toInt(), "×2" to 0xFFFFB21F.toInt(), "잭팟" to 0)
        val strip = List(9) { labels[it % 3] } + labels[k.ordinal]
        layer(0, 250) { c, p, now ->
            val w = (if (small) 60 else 78) * dp; val h = (if (small) 20 else 24) * dp
            c.save(); c.translate(a.x, a.y)
            val box = RectF(-w / 2, -h / 2, w / 2, h / 2)
            paint.reset(); paint.isAntiAlias = true; paint.color = 0xD10E121E.toInt()
            c.drawRoundRect(box, h / 2, h / 2, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp; paint.color = 0x4DFFFFFF
            c.drawRoundRect(box, h / 2, h / 2, paint); paint.style = Paint.Style.FILL
            c.clipRect(box)
            val off = eo(p) * (strip.size - 1) * h
            text.typeface = heavy; text.textSize = (if (small) 11 else 13) * dp
            strip.forEachIndexed { i, (label, col) ->
                val y = i * h - off
                if (abs(y) > h) return@forEachIndexed
                if (col == 0) text.shader = holo(now, -20 * dp, 20 * dp) else text.color = col
                c.drawText(label, 0f, y + text.textSize * .35f, text); text.shader = null
            }
            c.restore()
        }
        es += E(250, 1, start = {
            gainLabel = when (k) { Xp.Rarity.CRIT -> "크리티컬 "; Xp.Rarity.JACKPOT -> "잭팟 "; else -> "" } + "+${s.gain} XP"
            gainColor = if (k == Xp.Rarity.NORMAL) 0xFFFFE08A.toInt() else 0xFFFFC94D.toInt()
            when (k) {
                Xp.Rarity.CRIT -> buzz(Triple(PRIMITIVE_THUD, .7f, 0), Triple(PRIMITIVE_CLICK, 1f, 40))
                Xp.Rarity.JACKPOT -> buzz(Triple(PRIMITIVE_QUICK_RISE, 1f, 0), Triple(PRIMITIVE_CLICK, 1f, 30),
                    Triple(PRIMITIVE_CLICK, 1f, 60), Triple(PRIMITIVE_THUD, 1f, 60))
                else -> tick(.5f)
            }
        })
        if (k == Xp.Rarity.JACKPOT) layer(250, 950) { c, p, now ->
            paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.STROKE; paint.strokeWidth = 5 * dp
            paint.shader = holo(now, 0f, width.toFloat()); paint.alpha = (255 * sin(p * PI).toFloat() * .75f).toInt()
            c.drawRect(2.5f * dp, 2.5f * dp, width - 2.5f * dp, height - 2.5f * dp, paint); paint.shader = null
        }
        layer(250, if (small) 600 else 1000) { c, p, now ->
            val sc = if (p < .18f) backOut(p / .18f) else 1f
            val al = if (p > .72f) (1 - p) / .28f else 1f
            c.save(); c.translate(a.x, a.y - 14 * dp * p); c.scale(sc, sc)
            if (k == Xp.Rarity.JACKPOT) repeat(6) { i ->
                val g = now / 500f + i * PI.toFloat() / 3
                paint.reset(); paint.isAntiAlias = true
                paint.color = Color.HSVToColor(floatArrayOf((now / 4f + i * 60) % 360, .6f, 1f)); paint.alpha = (255 * al).toInt()
                star(c, cos(g) * 62 * dp, sin(g) * 20 * dp, (4 + 2 * sin(now / 120f + i)) * dp, now / 300f)
            }
            val main = when (k) { Xp.Rarity.NORMAL -> "+${s.gain} XP"; Xp.Rarity.CRIT -> "크리티컬! ×2"; Xp.Rarity.JACKPOT -> "잭팟 ×5" }
            text.typeface = heavy
            text.textSize = ((if (k == Xp.Rarity.JACKPOT) 21 else if (k == Xp.Rarity.CRIT) 18 else 16) - (if (small) 3 else 0)) * dp
            text.setShadowLayer(14 * dp, 0f, 0f, 0xE6FFAA1E.toInt())
            when (k) {
                Xp.Rarity.NORMAL -> text.color = Color.WHITE
                Xp.Rarity.CRIT -> text.shader = LinearGradient(-55 * dp, 0f, 55 * dp, 0f,
                    intArrayOf(0xFFFFE8A3.toInt(), 0xFFFFC94D.toInt(), 0xFFF29D1B.toInt()), null, Shader.TileMode.CLAMP)
                Xp.Rarity.JACKPOT -> text.shader = holo(now, -50 * dp, 50 * dp)
            }
            text.alpha = (255 * al).toInt()
            c.drawText(main, 0f, text.textSize * .35f, text); text.shader = null; text.clearShadowLayer()
            if (k != Xp.Rarity.NORMAL) {
                text.textSize = 12 * dp; text.color = 0xFFFFE3A3.toInt(); text.alpha = (255 * al).toInt()
                c.drawText("+${s.gain} XP", 0f, 22 * dp, text)
            }
            c.restore()
        }
        orbs(orbFrom, orbs[k.ordinal], s.gain, orbDelay, orbColor, odur, k)
    }

    private fun orbs(from: PointF, n: Int, amount: Int, delay: Long, color: Int, dur: Long, k: Xp.Rarity = Xp.Rarity.NORMAL) {
        showBar = true
        val base = amount / n; val rem = amount - base * n
        val spread = min(60f, 6f + n * 2) * dp
        repeat(n) { i ->
            val chunk = base + if (i < rem) 1 else 0
            val ang = r(0f, 6.28f)
            val b = PointF(from.x + cos(ang) * (14 * dp + r(0f, spread)), from.y + sin(ang) * (10 * dp + r(0f, spread * .6f)))
            val bend = r(-70f, 70f) * dp; val lift = r(50f, 110f) * dp
            fun pos(p: Float): PointF {
                if (p < .22f) { val q = eo(p / .22f); return PointF(from.x + (b.x - from.x) * q, from.y + (b.y - from.y) * q) }
                val t = ((p - .22f) / .78f).pow(2); val h = head(); val cx = b.x + bend; val cy = min(b.y, h.y) - lift; val u = 1 - t
                return PointF(u * u * b.x + 2 * u * t * cx + t * t * h.x, u * u * b.y + 2 * u * t * cy + t * t * h.y)
            }
            pendingOrbs++
            val col = when (k) { Xp.Rarity.CRIT -> 0xFFFFB21F.toInt(); else -> color }
            es += E(delay + i * (if (n > 12) 22L else 40L), dur + r(0f, 80f).toLong(), draw = { c, p, now ->
                val rad = (3.4f - p) * dp
                paint.reset(); paint.isAntiAlias = true; paint.blendMode = BlendMode.PLUS
                for (j in 3 downTo 1) { val q = pos(max(0f, p - j * .035f)); orb(c, q.x, q.y, rad * .8f, if (k == Xp.Rarity.JACKPOT) hue(now, i) else col, .22f / j) }
                val q = pos(p); orb(c, q.x, q.y, rad, if (k == Xp.Rarity.JACKPOT) hue(now, i) else col, 1f)
                paint.blendMode = null
            }, end = {
                landed += chunk; pendingOrbs--; lastLand = SystemClock.uptimeMillis() - t0; bump = lastLand
                if (orbN++ % 2 == 0) tick(.3f)
            })
        }
    }

    private fun hue(now: Long, i: Int) = Color.HSVToColor(floatArrayOf((now / 3f + i * 47) % 360, .55f, 1f))

    private fun orb(c: Canvas, x: Float, y: Float, rad: Float, col: Int, a: Float) {
        paint.color = col; paint.alpha = (60 * a).toInt(); c.drawCircle(x, y, rad * 3.2f, paint)
        paint.alpha = (140 * a).toInt(); c.drawCircle(x, y, rad * 1.8f, paint)
        paint.color = Color.WHITE; paint.alpha = (255 * a).toInt(); c.drawCircle(x, y, rad * .9f, paint)
    }

    private fun star(c: Canvas, x: Float, y: Float, rad: Float, rot: Float) {
        val path = Path()
        for (i in 0 until 8) {
            val q = if (i % 2 == 1) rad * .28f else rad; val a = rot + i * PI.toFloat() / 4
            if (i == 0) path.moveTo(x + cos(a) * q, y + sin(a) * q) else path.lineTo(x + cos(a) * q, y + sin(a) * q)
        }
        path.close(); c.drawPath(path, paint)
    }

    // ---------- the big scenes ----------
    private fun perfectDay(at: Long) {
        at(at) {
            buzz(Triple(PRIMITIVE_QUICK_RISE, .6f, 0), Triple(PRIMITIVE_CLICK, 1f, 30), Triple(PRIMITIVE_CLICK, 1f, 60),
                Triple(PRIMITIVE_CLICK, 1f, 60), Triple(PRIMITIVE_THUD, 1f, 80))
            cannon(true, 45); cannon(false, 45)
        }
        flash(at, 650, 0x73FFD278, 0x1FFFAA28)
        banner(at, 1900, "완벽한 하루", "독서 · 운동 · 금주 3/3 · +${Xp.DAY_BONUS} XP")
        listOf(250L, 550L).forEach { ms -> at(at + ms) { firework(width * r(.2f, .8f), height * r(.14f, .36f), 40, gold); tick(.6f) } }
    }

    private fun weekAll(at: Long) {
        at(at) {
            buzz(Triple(PRIMITIVE_QUICK_RISE, .8f, 0), Triple(PRIMITIVE_CLICK, 1f, 30), Triple(PRIMITIVE_CLICK, 1f, 50),
                Triple(PRIMITIVE_CLICK, 1f, 50), Triple(PRIMITIVE_CLICK, 1f, 50), Triple(PRIMITIVE_THUD, 1f, 80))
            cannon(true, 90); cannon(false, 90)
        }
        flash(at, 700, 0x8CFFD278.toInt(), 0x26FFAA28)
        banner(at, 2300, "이번 주 목표 올클리어", "주간 목표 전부 달성 · +${Xp.WEEK_BONUS} XP")
        listOf(180L, 480L, 780L, 1050L).forEach { ms -> at(at + ms) { firework(width * r(.18f, .82f), height * r(.12f, .38f), 46, gold); tick(.6f) } }
    }

    private fun monthAll(at: Long) {
        at(at) {
            buzz(Triple(PRIMITIVE_THUD, 1f, 0), Triple(PRIMITIVE_QUICK_RISE, 1f, 40), Triple(PRIMITIVE_CLICK, 1f, 40),
                Triple(PRIMITIVE_CLICK, 1f, 40), Triple(PRIMITIVE_CLICK, 1f, 40), Triple(PRIMITIVE_THUD, 1f, 120))
            cannon(true, 110); cannon(false, 110)
        }
        flash(at, 1000, 0x99FFDC8C.toInt(), 0x33FFAA28)
        edge(at, 2600, 0xE6FFBE3C.toInt())
        banner(at, 2800, "이번 달 목표 올클리어", "월간 목표 전부 달성 · +${Xp.MONTH_BONUS} XP")
        repeat(8) { f -> at(at + 150 + f * 190L) { firework(width * r(.08f, .92f), height * r(.08f, .5f), 64, if (f % 2 == 1) gold else warm); tick(.7f) } }
    }

    // 올해: slow, ceremonial. Dim, gold motes gather into the box, a deep boom, light pillars, slow gold dust.
    private fun ceremony(from: PointF, goal: String, s: Show) {
        val d = 3200L
        val diag = hypot(width.toFloat(), height.toFloat())
        class Mote(val a: Float, val r0: Float, val sp: Float, val sz: Float, val dl: Float)
        class Pillar(val x: Float, val w: Float, val dl: Float, val h: Float, val ph: Float)
        val motes = List(80) { Mote(r(0f, 6.28f), r(.45f, .75f) * diag, r(.8f, 1.4f), r(1f, 2.4f) * dp, r(0f, .35f)) }
        val pillars = List(7) { Pillar(width * (.08f + it * .14f) + r(-16f, 16f) * dp, r(22f, 64f) * dp, r(0f, .25f), r(.7f, 1f), r(0f, 6f)) }
        buzz(Triple(PRIMITIVE_SLOW_RISE, .3f, 0), Triple(PRIMITIVE_SLOW_RISE, .6f, 0), Triple(PRIMITIVE_SLOW_RISE, 1f, 0))
        layer(0, d, z = -1) { c, p, now ->
            val t = p * d; val cx = width / 2f; val cy = height * .42f
            val dim = if (t < 900) eio(t / 900) * .8f else if (t < 2300) .8f else .8f * (1 - eio((t - 2300) / 900))
            paint.reset(); paint.color = Color.argb((255 * dim).toInt(), 6, 4, 2)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.blendMode = BlendMode.PLUS
            if (t < 1050) {
                paint.color = 0xFFFFE3A0.toInt()
                motes.forEach { m ->
                    val q = clamp((t / 1000 - m.dl) / (1 - m.dl)); if (q <= 0) return@forEach
                    val e = q * q; val rr = m.r0 * (1 - e); val a = m.a + e * 1.4f * m.sp
                    paint.alpha = (255 * min(1f, q * 2)).toInt()
                    c.drawCircle(from.x + cos(a) * rr, from.y + sin(a) * rr, m.sz, paint)
                }
                val gq = eo(t / 1000)
                paint.alpha = 255
                paint.shader = RadialGradient(from.x, from.y, (10 + 60 * gq) * dp,
                    intArrayOf(Color.argb((255 * (.4f + .6f * gq)).toInt(), 255, 240, 200), 0x00FFB43C), null, Shader.TileMode.CLAMP)
                c.drawCircle(from.x, from.y, (10 + 60 * gq) * dp, paint); paint.shader = null
            }
            if (t >= 1000 && t < 1600) {
                paint.color = Color.argb((255 * .6f * (1 - (t - 1000) / 600)).toInt(), 255, 236, 190)
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
            if (t > 1000) {
                val q = (t - 1000) / (d - 1000)
                val env = if (q < .15f) eo(q / .15f) else 1 - eio((q - .6f) / .4f)
                val big = max(width, height) * .65f
                paint.alpha = 255
                paint.shader = RadialGradient(cx, cy, big, intArrayOf(Color.argb((107 * env).toInt(), 255, 214, 120),
                    Color.argb((36 * env).toInt(), 255, 170, 50), 0), floatArrayOf(0f, .4f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint); paint.shader = null
                paint.color = Color.argb((15 * env).toInt(), 255, 220, 150)
                val rays = Path()
                for (i in 0 until 16) {
                    val a = i / 16f * 6.283f + now * .00008f
                    rays.moveTo(cx, cy); rays.lineTo(cx + cos(a - .05f) * diag, cy + sin(a - .05f) * diag)
                    rays.lineTo(cx + cos(a + .05f) * diag, cy + sin(a + .05f) * diag); rays.close()
                }
                c.drawPath(rays, paint)
                pillars.forEach { pl ->
                    val rq = eo((q - pl.dl) / .4f); if (rq <= 0) return@forEach
                    val top = height - height * pl.h * rq; val ww = pl.w * (.85f + .15f * sin(now / 300f + pl.ph))
                    paint.shader = LinearGradient(0f, height.toFloat(), 0f, top, intArrayOf(Color.argb((97 * env).toInt(), 255, 200, 90),
                        Color.argb((41 * env).toInt(), 255, 225, 150), 0), floatArrayOf(0f, .7f, 1f), Shader.TileMode.CLAMP)
                    c.drawRect(pl.x - ww / 2, top, pl.x + ww / 2, height.toFloat(), paint); paint.shader = null
                }
            }
            paint.blendMode = null
        }
        at(1000) {
            buzz(Triple(PRIMITIVE_THUD, 1f, 0), Triple(PRIMITIVE_LOW_TICK, 1f, 40), Triple(PRIMITIVE_LOW_TICK, 1f, 40),
                Triple(PRIMITIVE_LOW_TICK, .8f, 40), Triple(PRIMITIVE_LOW_TICK, .5f, 40))
            burst(from.x, from.y, 2.2f, conf = false)
            add(P(K.RING, from.x, from.y, life = 1.1f, color = 0xFFFFE3A0.toInt(), r0 = 10 * dp, r1 = diag * .7f, lw = 4 * dp))
            repeat(90) {
                add(P(K.DUST, r(0f, width.toFloat()), r(-60 * dp, height * .55f), r(-14f, 14f) * dp, r(18f, 55f) * dp, 6 * dp, .995f,
                    r(1.9f, 2.3f), r(.9f, 2.4f) * dp, pick(gold), r(0f, .4f), ph = r(0f, 6f)))
            }
        }
        layer(1150, 2050) { c, p, _ ->
            val a = if (p < .2f) eo(p / .2f) else if (p > .82f) 1 - (p - .82f) / .18f else 1f
            val sc = 1.08f - .08f * eo(p / .6f)
            c.save(); c.translate(width / 2f, height * .42f); c.scale(sc, sc)
            text.typeface = medium; text.textSize = 12 * dp; text.color = 0xD9FFE6B4.toInt(); text.alpha = (217 * a).toInt()
            text.letterSpacing = .5f; c.drawText("올해의 목표", 0f, -46 * dp, text); text.letterSpacing = 0f
            text.typeface = heavy; text.textSize = min(34 * dp, width * .085f)
            text.shader = LinearGradient(-120 * dp, 0f, 120 * dp, 0f, intArrayOf(0xFFFFE8A3.toInt(), 0xFFFFC94D.toInt(), 0xFFF29D1B.toInt()), null, Shader.TileMode.CLAMP)
            text.alpha = (255 * a).toInt(); text.setShadowLayer(24 * dp, 0f, 0f, 0xE6FFAA28.toInt())
            c.drawText("올해 목표 달성", 0f, 0f, text); text.shader = null
            text.setShadowLayer(10 * dp, 0f, 0f, 0xE6FFAA28.toInt())
            text.typeface = medium; text.textSize = 16 * dp; text.color = 0xFFFFF3D6.toInt(); text.alpha = (255 * a).toInt()
            c.drawText(goal, 0f, 36 * dp, text); text.clearShadowLayer()
            paint.reset(); paint.isAntiAlias = true; paint.strokeWidth = dp; paint.color = 0x99FFD678.toInt(); paint.alpha = (153 * a).toInt()
            c.drawLine(-110 * dp, 54 * dp, -30 * dp, 54 * dp, paint); c.drawLine(30 * dp, 54 * dp, 110 * dp, 54 * dp, paint)
            if (s.gain > 0) {
                text.typeface = heavy; text.textSize = 13 * dp; text.color = 0xFFFFD66B.toInt(); text.alpha = (255 * a).toInt()
                val label = when (s.rarity) { Xp.Rarity.NORMAL -> ""; Xp.Rarity.CRIT -> "크리티컬 ×2 · "; Xp.Rarity.JACKPOT -> "잭팟 ×5 · " }
                c.drawText("$label+${s.gain} XP", 0f, 74 * dp, text)
            }
            c.restore()
        }
    }

    private fun sunrise(from: PointF) {
        layer(0, 1900, z = -1) { c, p, now ->
            val t = p * 1900; val w = width.toFloat(); val h = height.toFloat()
            val env = if (t < 350) eo(t / 350) else 1 - eio((t - 1450) / 450); val rise = eo(t / 900)
            paint.reset(); paint.alpha = 255
            paint.shader = LinearGradient(0f, h, 0f, h * (1 - .9f * rise), intArrayOf(Color.argb((140 * env).toInt(), 255, 140, 40),
                Color.argb((51 * env).toInt(), 255, 185, 90), 0), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, paint)
            paint.blendMode = BlendMode.PLUS
            val sx = w / 2; val sy = h + 70 * dp - rise * 170 * dp; val l = max(21 * dp, h * 1.05f * rise)
            paint.shader = RadialGradient(sx, sy, l, Color.argb((82 * env).toInt(), 255, 215, 130), 0, Shader.TileMode.CLAMP)
            val rays = Path()
            for (i in 0 until 14) {
                val a = (-PI + (i + .5) / 14 * PI).toFloat() + t * .00012f
                rays.moveTo(sx, sy); rays.lineTo(sx + cos(a - .045f) * l, sy + sin(a - .045f) * l)
                rays.lineTo(sx + cos(a + .045f) * l, sy + sin(a + .045f) * l); rays.close()
            }
            c.drawPath(rays, paint)
            paint.shader = RadialGradient(sx, sy, 140 * dp, intArrayOf(Color.argb((230 * env).toInt(), 255, 240, 200),
                Color.argb((128 * env).toInt(), 255, 190, 90), 0), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(sx, sy, 140 * dp, paint)
            paint.shader = null; paint.blendMode = null
        }
    }

    private fun softDawn() = layer(0, 1800, z = -1) { c, p, _ ->
        val t = p * 1800; val env = if (t < 500) eio(t / 500) else 1 - eio((t - 1200) / 600)
        paint.reset(); paint.alpha = 255
        paint.shader = LinearGradient(0f, height.toFloat(), 0f, height * .45f, Color.argb((46 * env).toInt(), 255, 205, 160), 0, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint); paint.shader = null
    }

    // Bedtime: no blue light at all. Warm dim, crescent moon, slow amber stars and embers.
    private fun night() {
        class Dot(val x: Float, val y: Float, val r: Float, val ph: Float, val c: Int, val sp: Float)
        val stars = List(18) { Dot(r(0f, width.toFloat()), 20 * dp + r(0f, height * .55f), r(.7f, 2.2f) * dp, r(0f, 6.3f),
            if (rnd.nextBoolean()) Color.rgb(255, 217, 160) else Color.rgb(245, 185, 113), 0f) }
        val embers = List(12) { Dot(r(0f, width.toFloat()), height * .55f + r(0f, height * .45f), r(1f, 2.6f) * dp, r(0f, 6.3f), 0, r(.5f, 1.5f)) }
        val moon = Path().apply {
            addCircle(0f, 0f, 30 * dp, Path.Direction.CW)
            op(Path().apply { addCircle(13 * dp, -9 * dp, 27 * dp, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        }
        layer(0, 2500, z = -1) { c, p, _ ->
            val t = p * 2500; val w = width.toFloat(); val h = height.toFloat()
            val dim = if (t < 900) eio(t / 900) else 1 - eio((t - 1900) / 600)
            paint.reset(); paint.color = Color.argb((158 * dim).toInt(), 8, 5, 3); c.drawRect(0f, 0f, w, h, paint)
            paint.shader = RadialGradient(w / 2, h, h * .7f, Color.argb((71 * dim).toInt(), 120, 40, 20), 0, Shader.TileMode.CLAMP)
            paint.alpha = 255; c.drawRect(0f, 0f, w, h, paint); paint.shader = null
            val ma = eio((t - 250) / 1000) * (if (t > 1900) 1 - eio((t - 1900) / 600) else 1f)
            c.save(); c.translate(w / 2, h * .27f + 16 * dp * (1 - eio((t - 250) / 1400)))
            paint.color = 0xFFF4E4C1.toInt(); paint.alpha = (255 * ma).toInt()
            paint.setShadowLayer(28 * dp, 0f, 0f, Color.argb((166 * ma).toInt(), 255, 190, 110))
            c.drawPath(moon, paint); paint.clearShadowLayer(); c.restore()
            stars.forEach { s ->
                paint.color = s.c; paint.alpha = (255 * dim * (.35f + .65f * (.5f + .5f * sin(t / 650 + s.ph)))).toInt()
                c.drawCircle(s.x, s.y, s.r, paint)
            }
            embers.forEach { e ->
                paint.color = Color.rgb(200, 100, 60); paint.alpha = (128 * dim).toInt()
                c.drawCircle(e.x + sin(t / 900 + e.ph) * 6 * dp, e.y - t * .018f * e.sp * dp, e.r, paint)
            }
        }
    }

    // ---------- entry ----------
    fun play(s: Show, from: PointF, bar: RectF?, done: () -> Unit) {
        onDone = done
        widgetBar = bar
        barBase = s.before
        post {
            t0 = SystemClock.uptimeMillis(); last = t0
            stage(s, from)
            postInvalidateOnAnimation()
        }
    }

    private fun stage(s: Show, from: PointF) {
        val center = PointF(width / 2f, height * .36f)
        when (s.tier) {
            Tier.TASK -> {
                buzz(Triple(PRIMITIVE_CLICK, .6f, 0))
                burst(from.x, from.y, .4f, conf = false)
                reward(s, from, intArrayOf(1, 2, 3), 330, small = true)
            }
            Tier.HABIT -> {
                buzz(Triple(PRIMITIVE_CLICK, .8f, 0), Triple(PRIMITIVE_THUD, .5f, 30))
                burst(from.x, from.y)
                reward(s, from, intArrayOf(3, 5, 8), 560, small = false)
                if (s.all) {
                    perfectDay(950)
                    if (s.bonus > 0) orbs(center, 6, s.bonus, 1500, orbGold, 650)
                }
            }
            Tier.WEEK -> {
                buzz(Triple(PRIMITIVE_CLICK, 1f, 0), Triple(PRIMITIVE_TICK, .6f, 40), Triple(PRIMITIVE_THUD, .7f, 60))
                burst(from.x, from.y, 1.5f)
                edge(0, 1400, 0xBFFFC94D.toInt())
                reward(s, from, intArrayOf(7, 10, 16), 620, small = false)
                if (s.all) {
                    weekAll(700)
                    if (s.bonus > 0) orbs(center, 12, s.bonus, 1500, orbGold, 650)
                }
            }
            Tier.MONTH -> {
                buzz(Triple(PRIMITIVE_THUD, 1f, 0), Triple(PRIMITIVE_QUICK_RISE, .8f, 30), Triple(PRIMITIVE_CLICK, 1f, 40),
                    Triple(PRIMITIVE_CLICK, 1f, 40), Triple(PRIMITIVE_CLICK, 1f, 40))
                burst(from.x, from.y, 1.9f)
                flash(0, 900, 0x80FFD278.toInt(), 0x26FFAA28)
                edge(0, 2000, 0xBFFFB93C.toInt())
                banner(250, 2000, "월간 목표 달성", s.title)
                repeat(6) { f -> at(150 + f * 260L) { firework(width * r(.1f, .9f), height * r(.1f, .45f), 52, gold); tick(.6f) } }
                reward(s, from, intArrayOf(12, 16, 22), 680, small = false)
                if (s.all) {
                    monthAll(1500)
                    if (s.bonus > 0) orbs(center, 16, s.bonus, 2600, orbGold, 650)
                }
            }
            Tier.YEAR -> {
                ceremony(from, s.title, s)
                reward(s, from, intArrayOf(20, 26, 34), 900, small = false, orbFrom = PointF(width / 2f, height * .42f), orbDelay = 2000)
            }
            Tier.WAKE -> {
                buzz(Triple(PRIMITIVE_QUICK_RISE, .5f, 0), Triple(PRIMITIVE_CLICK, 1f, 40), Triple(PRIMITIVE_TICK, .5f, 90))
                sunrise(from)
                at(300) { burst(from.x, from.y, .9f, warm, warm) }
                caption(0, 1700, s.title, s.sub, from.y + 90 * dp, bed = false)
                reward(s, from, intArrayOf(3, 5, 8), 620, small = false, orbDelay = 550, orbColor = 0xFFFFB547.toInt())
            }
            Tier.WAKE_LOW -> {
                buzz(Triple(PRIMITIVE_LOW_TICK, .4f, 0))
                softDawn()
                caption(0, 1700, s.title, s.sub, from.y + 90 * dp, bed = false)
            }
            Tier.BED -> {
                buzz(Triple(PRIMITIVE_LOW_TICK, .25f, 0))
                night()
                caption(0, 2400, s.title, s.sub, height * .27f + 70 * dp, bed = true)
            }
        }
    }

    override fun onDraw(c: Canvas) {
        if (t0 == 0L) return
        val wall = SystemClock.uptimeMillis()
        val now = wall - t0
        val dt = ((wall - last) / 1000f).coerceIn(0f, .033f)
        last = wall
        val live = es.filter { !it.ended && now >= it.at }
        live.forEach { if (!it.started) { it.started = true; it.start?.invoke() } }
        live.filter { it.z < 0 }.forEach { e -> e.draw?.invoke(c, clamp((now - e.at) / e.dur.toFloat()), now) }
        step(c, dt)
        live.filter { it.z >= 0 }.forEach { e -> e.draw?.invoke(c, clamp((now - e.at) / e.dur.toFloat()), now) }
        drawBar(c, now)
        live.forEach { if (now >= it.at + it.dur) { it.ended = true; it.end?.invoke() } }
        val barDone = !showBar || (pendingOrbs == 0 && now > lastLand + 1000)
        if (es.any { !it.ended } || ps.isNotEmpty() || !barDone) postInvalidateOnAnimation()
        else onDone?.invoke().also { onDone = null }
    }

    private fun step(c: Canvas, dt: Float) {
        val k = 60 * dt
        val it = ps.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.delay > 0) { p.delay -= dt; continue }
            p.age += dt
            val t = p.age / p.life
            if (t >= 1) { it.remove(); continue }
            val d = p.drag.pow(k)
            p.vx *= d; p.vy = p.vy * d + p.g * dt
            p.x += p.vx * dt; p.y += p.vy * dt; p.rot += p.vr * dt; p.flip += p.fs * dt
            paint.reset(); paint.isAntiAlias = true; paint.color = p.color
            if (p.k == K.CONF) {
                paint.alpha = (255 * (if (t < .7f) 1f else 1 - (t - .7f) / .3f)).toInt()
                c.save(); c.translate(p.x, p.y); c.rotate(Math.toDegrees(p.rot.toDouble()).toFloat()); c.scale(1f, cos(p.flip))
                c.drawRect(-p.size / 2, -p.size / 4, p.size / 2, p.size / 4, paint); c.restore()
                continue
            }
            paint.blendMode = BlendMode.PLUS
            when (p.k) {
                K.SPARK -> {
                    paint.alpha = (255 * (1 - t)).toInt(); paint.strokeWidth = p.size; paint.strokeCap = Paint.Cap.ROUND
                    c.drawLine(p.x, p.y, p.x - p.vx * .035f, p.y - p.vy * .035f, paint)
                }
                K.RING -> {
                    val rad = p.r0 + (p.r1 - p.r0) * (1 - (1 - t).pow(3))
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = p.lw * (1 - t) + .5f * dp
                    paint.alpha = (255 * (1 - t) * .9f).toInt(); c.drawCircle(p.x, p.y, rad, paint)
                }
                K.STAR -> { paint.alpha = (255 * sin(t * PI).toFloat()).toInt(); star(c, p.x, p.y, p.size * (.6f + .4f * sin(t * 9)), 0f) }
                K.DUST -> {
                    val a = sin(t * PI).toFloat() * (.55f + .45f * sin(p.age * 7 + p.ph))
                    paint.alpha = (255 * a * .25f).toInt().coerceAtLeast(0); c.drawCircle(p.x, p.y, p.size * 3, paint)
                    paint.alpha = (255 * a).toInt().coerceAtLeast(0); c.drawCircle(p.x, p.y, p.size, paint)
                }
                else -> { paint.alpha = (255 * (1 - t)).toInt(); c.drawCircle(p.x, p.y, p.size, paint) }
            }
        }
        paint.reset()
    }
}
