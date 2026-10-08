package dev.daily.widget

import android.app.Activity
import android.graphics.PointF
import android.os.Bundle
import android.view.WindowManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// A widget tap that completes something: records it (same optimistic Store path as everything else),
// then plays its reward over the home screen. Glance can't animate, so this transparent, untouchable
// window does it and closes itself. Undo taps never come here; they stay quiet callbacks.
class FxActivity : Activity() {
    companion object {
        const val KIND = "kind"
        const val KEY = "key"
        const val INDEX = "index"
        const val HABIT = "habit"
        const val WAKE = "wake"
        const val BED = "bed"
        const val GOAL = "goal"
        const val TASK = "task"
    }

    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.setDecorFitsSystemWindows(false)
        val view = FxView(this)
        setContentView(view)
        val kind = intent.getStringExtra(KIND) ?: return finish()
        val key = intent.getStringExtra(KEY).orEmpty()
        val index = intent.getIntExtra(INDEX, -1)
        scope.launch {
            if (Store.state.value == null) Store.refresh(applicationContext)
            val show = Store.state.value?.takeIf { it.problem == null }?.let { perform(kind, key, index) }
            if (show == null) { finish(); return@launch }
            // The launcher passes the tapped cell's screen rect; the screen centre is the fallback.
            val from = intent.sourceBounds?.let { PointF(it.exactCenterX(), it.exactCenterY()) }
                ?: PointF(resources.displayMetrics.widthPixels / 2f, resources.displayMetrics.heightPixels * .6f)
            view.play(show, from) { finish() }
        }
    }

    override fun onDestroy() {
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
            TASK -> {
                Store.editBacklog(this, check(Backlog, index, today))
                val text = Store.state.value!!.backlog.getOrNull(index)?.text ?: return null
                show(Tier.TASK, Xp.rarity(today, Xp.taskId(text)), Xp.TASK)
            }
            else -> null
        }
    }

    // Idempotent: a double tap re-checks instead of toggling back.
    private fun check(list: Checklist, i: Int, today: LocalDate) = { text: String ->
        list.parse(text).getOrNull(i)?.let { list.set(text, i, it.check(true, today)) } ?: text
    }
}
