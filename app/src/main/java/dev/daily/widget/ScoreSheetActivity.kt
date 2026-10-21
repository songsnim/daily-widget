package dev.daily.widget

import android.app.Activity
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.daily.widget.Ui.dp
import dev.daily.widget.Ui.text
import dev.daily.widget.Ui.topRound
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

// Why 평가 is what it is: each part's points, and the cap when they add up past 5. Points (right) and the
// blue thresholds are tappable; a change applies from now on (today's note is rescored, older notes keep theirs).
class ScoreSheetActivity : Activity() {
    companion object { const val DATE = "date" }

    private lateinit var date: LocalDate
    private lateinit var sheet: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Store.state.value == null) runBlocking { Store.refresh(this@ScoreSheetActivity) }
        val s = Store.state.value ?: return finish()
        date = intent.getStringExtra(DATE)?.let(LocalDate::parse) ?: s.day
        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = topRound(Ui.SHEET, 24)
            isClickable = true
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(dp(24), dp(28), dp(24), dp(20) + insets.getInsets(WindowInsets.Type.systemBars()).bottom)
                insets
            }
        }
        render()
        setContentView(FrameLayout(this).apply {
            setOnClickListener { finish() }
            addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })
    }

    private fun render() {
        val s = Store.state.value ?: return
        sheet.removeAllViews()
        // The cached note includes edits still being written; the live screen count only applies to the shown day.
        val parts = Score.parts(s.note(Vault.daily(date)), s.screenMinutes.takeIf { date == s.day })
        val sum = parts.sumOf { it.got }
        val score = minOf(Score.MAX, sum)
        val r = Score.rules

        sheet.addView(text("평가 ${Score.format(score)}점", 20f, bold = true))
        sheet.addView(text("${Store.dayLabel(date)} · 만점 ${Score.format(Score.MAX)}점", 14f, Ui.SUB)
            .apply { setPadding(0, dp(6), 0, dp(2)) })
        sheet.addView(text("오른쪽 배점과 파란 기준을 눌러 바꿀 수 있어요", 12f, Ui.SUB).apply { setPadding(0, 0, 0, dp(12)) })
        parts.forEach { p ->
            sheet.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(p.label, 16f, bold = true))
                    addView(text(p.detail, 13f, Ui.SUB))
                    val edits = when (p.label) {
                        "기상" -> listOf("기준 ${Score.clock(r.wakeBy)}까지" to { pick(r.wakeBy) { set(r.copy(wakeBy = it)) } })
                        "수면" -> listOf("기준 ${Score.duration(r.sleepAtLeast)} 이상" to { pick(r.sleepAtLeast) { set(r.copy(sleepAtLeast = it)) } })
                        // Keep full <= half so the tiers stay in order.
                        "스크린" -> listOf(
                            "만점 ${Score.duration(r.screenFull)} 이하" to { pick(r.screenFull) { set(r.copy(screenFull = it, screenHalf = maxOf(it, r.screenHalf))) } },
                            "절반 ${Score.duration(r.screenHalf)} 이하" to { pick(r.screenHalf) { set(r.copy(screenHalf = it, screenFull = minOf(it, r.screenFull))) } })
                        else -> emptyList()
                    }
                    if (edits.isNotEmpty()) addView(LinearLayout(context).apply {
                        edits.forEach { (label, onClick) ->
                            addView(text(label, 13f, Ui.BLUE, bold = true).apply {
                                setPadding(0, dp(4), dp(16), dp(4))
                                setOnClickListener { onClick() }
                            })
                        }
                    })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                // Cycles the part's worth (per item for Big3) between 0.5 and 1.
                addView(text("${Score.format(p.got)} / ${Score.format(p.max)}", 16f,
                    if (p.got > 0) Ui.BLUE else Ui.SUB, bold = true).apply {
                    setPadding(dp(12), dp(8), 0, dp(8))
                    setOnClickListener {
                        val pts = r.pts(p.label)
                        set(r.copy(points = r.points + (p.label to if (pts == 1.0) .5 else 1.0)))
                    }
                })
            })
        }
        if (sum > Score.MAX) sheet.addView(text("합계 ${Score.format(sum)}점, 만점 ${Score.format(Score.MAX)}점에서 멈춰요",
            13f, Ui.SUB).apply { setPadding(0, dp(12), 0, 0) })
    }

    // Hours and minutes, for both clock times and durations.
    private fun pick(minutes: Int, done: (Int) -> Unit) =
        TimePickerDialog(this, { _, h, m -> done(h * 60 + m) }, minutes / 60, minutes % 60, true).show()

    private fun set(r: Rules) {
        Prefs.setRules(this, r)
        Score.rules = r
        render()
        // Rescore today's note if it exists; older notes are left as they were scored.
        val s = Store.state.value ?: return
        if (s.note(Vault.daily(s.today)) != null) Store.editTodos(this, s.today) { it }
        else MainScope().launch { DailyWidget().updateAll(applicationContext) }
    }
}
