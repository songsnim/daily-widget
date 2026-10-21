package dev.daily.widget

import android.app.Activity
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import android.view.View
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// A widget tap that completes something: records it (same optimistic Store path as everything else),
// then plays its reward over the home screen. Glance can't animate, so this transparent
// window does it and closes itself. Undo taps never come here; they stay quiet callbacks.
class FxActivity : Activity() {
    companion object {
        const val KIND = "kind"
        const val KEY = "key"
        const val INDEX = "index"
        const val CELL = "cell"
        const val BAR = "bar"
        const val RATIO = "ratio"
        const val HABIT = "habit"
        const val WAKE = "wake"
        const val BED = "bed"
        const val GOAL = "goal"
        const val HABITS = "habits"
        private val HABITS_KEYS = dev.daily.widget.HABITS
    }

    private val scope = MainScope()
    private var job: Job? = null
    private lateinit var view: FxView
    // Widget space (dp) to screen (px): screen = origin + widget * k. Null when the launcher gave no bounds.
    private var k = 0f
    private var origin: PointF? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Touchable on purpose: Android 12+ drops touches that pass through another app's window, so a
        // see-through overlay would swallow every widget tap until it closed. Instead a tap cuts the current
        // reward short and, on an open habit cell, plays that one (see onTouch).
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.setDecorFitsSystemWindows(false)
        view = FxView(this)
        view.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) onTap(e.rawX, e.rawY); true }
        setContentView(view)
        val kind = intent.getStringExtra(KIND) ?: return finish()
        val cell = intent.getFloatArrayExtra(CELL)
        val b = intent.sourceBounds
        if (b != null && cell != null && cell[2] > 0f) {
            // sourceBounds has the on-screen corner but the unscaled view width, hence One UI's shrink ratio.
            k = b.width() / cell[2] * intent.getFloatExtra(RATIO, 1f)
            origin = PointF(b.left - cell[0] * k, b.top - cell[1] * k)
        }
        // The launcher passes the tapped cell's screen rect; the screen centre is the fallback.
        val from = b?.let { PointF(it.exactCenterX(), it.exactCenterY()) }
            ?: PointF(resources.displayMetrics.widthPixels / 2f, resources.displayMetrics.heightPixels * .6f)
        run(kind, intent.getStringExtra(KEY).orEmpty(), intent.getIntExtra(INDEX, -1), from, wait = true)
    }

    private fun run(kind: String, key: String, index: Int, from: PointF, wait: Boolean) {
        job?.cancel()
        view.reset()
        job = scope.launch {
            if (Store.state.value == null) Store.refresh(applicationContext)
            val bar = widgetBar()
            // Keep the widget bar at the old total so the overlay can fill it in place.
            if (bar != null) Store.xpHold.value = Store.state.value?.xp
            val show = Store.state.value?.takeIf { it.problem == null }?.let { perform(kind, key, index) }
            if (show == null) { Store.releaseXp(applicationContext); finish(); return@launch }
            // One UI's launcher slides widget launches up whatever we ask for, so draw nothing until the window
            // has settled; otherwise the bar rises from below and reads as a second bar.
            if (wait) withTimeoutOrNull(800) { entered.await() }
            view.play(show, from, bar) {
                // The overlay's last frame stays up while the widget redraws at the new total, then both match.
                Store.releaseXp(applicationContext)
                job = scope.launch {
                    delay(400)
                    view.visibility = View.INVISIBLE // empty before the exit animation, so nothing slides away
                    delay(50)
                    finish()
                }
            }
        }
    }

    // A tap on an open habit cell starts its reward now; anywhere else just closes the overlay.
    private fun onTap(x: Float, y: Float) {
        val o = origin
        val cells = intent.getFloatArrayExtra(HABITS)
        val props = Store.state.value?.props.orEmpty()
        val hit = if (o == null || cells == null) null else HABITS_KEYS.indices.firstOrNull { i ->
            val c = cells.copyOfRange(i * 4, i * 4 + 4)
            props[HABITS_KEYS[i]] != "true" && RectF(o.x + c[0] * k, o.y + c[1] * k, o.x + (c[0] + c[2]) * k, o.y + (c[1] + c[3]) * k).contains(x, y)
        }
        if (hit == null) { Store.releaseXp(applicationContext); finish(); return }
        val c = cells!!.copyOfRange(hit * 4, hit * 4 + 4)
        run(HABIT, HABITS_KEYS[hit], -1, PointF(o!!.x + (c[0] + c[2] / 2) * k, o.y + (c[1] + c[3] / 2) * k), wait = false)
    }

    private val entered = CompletableDeferred<Unit>()

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        entered.complete(Unit)
    }

    // The widget's own XP bar on screen, mapped through the tapped cell. Its right end is kept within the
    // mirror of its left margin: a widget never reaches past the screen's opposite edge, and One UI's scaling
    // can make the mapped width run long (the fill then spilled onto the home screen).
    private fun widgetBar(): RectF? {
        val o = origin ?: return null
        val bar = intent.getFloatArrayExtra(BAR) ?: return null
        val left = o.x + bar[0] * k
        val right = minOf(o.x + (bar[0] + bar[2]) * k, resources.displayMetrics.widthPixels - left)
        return RectF(left, o.y + bar[1] * k, right, o.y + (bar[1] + bar[3]) * k)
    }

    override fun onDestroy() {
        Store.releaseXp(applicationContext) // never leave the widget bar stuck if the activity dies early
        scope.cancel()
        super.onDestroy()
    }

    private fun xp() = Store.state.value!!.xp

    private fun perform(kind: String, key: String, index: Int): Show? {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val before = xp()
        fun show(tier: Tier, rarity: Xp.Rarity, base: Int, all: Boolean = false, title: String = "", sub: String = ""): Show {
            val delta = xp() - before
            val gain = minOf(delta, base * rarity.mult).coerceAtLeast(0) // 0 outside this week or on a backfilled day
            return Show(tier, rarity, gain, (delta - gain).coerceAtLeast(0), before, all, title, sub)
        }
        return when (kind) {
            HABIT -> {
                val date = Store.dateFor(key, now)
                Store.setProp(this, key, "true", date)
                val props = Frontmatter.parse(Store.state.value!!.note(Vault.daily(date)))
                show(Tier.HABIT, Xp.rarity(date, key), Xp.HABIT[key] ?: 0, all = Xp.HABIT.keys.all { props[it] == "true" })
            }
            WAKE -> {
                val value = now.format(DateTimeFormatter.ofPattern("HH:mm"))
                val date = Store.dateFor("기상", now)
                Store.setProp(this, "기상", value, date)
                val sleep = Score.sleep(Frontmatter.parse(Store.state.value!!.note(Vault.daily(date))))
                val slept = sleep?.let { "${it / 60}시간" + if (it % 60 > 0) " ${it % 60}분" else "" }
                if (sleep != null && sleep >= 7 * 60)
                    show(Tier.WAKE, Xp.rarity(date, "기상"), Xp.WAKE, title = "$slept 푹 잤어요", sub = "오늘도 힘차게 시작해요!")
                else show(Tier.WAKE_LOW, Xp.Rarity.NORMAL, 0,
                    title = slept?.let { "$it 잤어요" } ?: "$value 기상", sub = if (slept != null) "오늘은 무리하지 마요" else "좋은 아침이에요")
            }
            BED -> {
                val value = now.format(DateTimeFormatter.ofPattern("HH:mm"))
                Store.setProp(this, "취침", value, Store.dateFor("취침", now))
                Show(Tier.BED, title = "잘 자요, 좋은 꿈 꾸세요", sub = "$value 취침 기록")
            }
            GOAL -> {
                val p = Period.valueOf(key)
                Store.editGoals(this, p, check(Goals, index, today))
                val goals = Store.state.value!!.goals(p).orEmpty().filter { it.text.isNotEmpty() }
                val text = Goals.parse(Store.state.value!!.note(Vault.period(p, today)).orEmpty()).getOrNull(index)?.text ?: return null
                val tier = when (p) { Period.WEEK -> Tier.WEEK; Period.MONTH -> Tier.MONTH; Period.YEAR -> Tier.YEAR }
                show(tier, Xp.rarity(today, Xp.goalId(p, text)), Xp.goal(p), all = goals.all { it.checked }, title = text)
            }
            else -> null
        }
    }

    // Idempotent: a double tap re-checks instead of toggling back.
    private fun check(list: Checklist, i: Int, today: LocalDate) = { text: String ->
        list.parse(text).getOrNull(i)?.let { list.set(text, i, it.check(true, today)) } ?: text
    }
}
