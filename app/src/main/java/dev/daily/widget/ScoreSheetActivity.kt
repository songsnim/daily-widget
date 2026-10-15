package dev.daily.widget

import android.app.Activity
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
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

// Why 평가 is what it is: each part's points, and the cap when they add up past 5.
class ScoreSheetActivity : Activity() {
    companion object { const val DATE = "date" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Store.state.value == null) runBlocking { Store.refresh(this@ScoreSheetActivity) }
        val s = Store.state.value ?: return finish()
        val date = intent.getStringExtra(DATE)?.let(LocalDate::parse) ?: s.day
        // The cached note includes edits still being written; the live screen count only applies to the shown day.
        val parts = Score.parts(s.note(Vault.daily(date)), s.screenMinutes.takeIf { date == s.day })
        val sum = parts.sumOf { it.got }
        val score = minOf(Score.MAX, sum)

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = topRound(Ui.SHEET, 24)
            isClickable = true
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(dp(24), dp(28), dp(24), dp(20) + insets.getInsets(WindowInsets.Type.systemBars()).bottom)
                insets
            }
        }
        sheet.addView(text("평가 ${Score.format(score)}점", 20f, bold = true))
        sheet.addView(text("${Store.dayLabel(date)} · 만점 ${Score.format(Score.MAX)}점", 14f, Ui.SUB)
            .apply { setPadding(0, dp(6), 0, dp(12)) })
        parts.forEach { p ->
            sheet.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(p.label, 16f, bold = true))
                    addView(text(p.detail, 13f, Ui.SUB))
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(text("${Score.format(p.got)} / ${Score.format(p.max)}", 16f,
                    if (p.got > 0) Ui.BLUE else Ui.SUB, bold = true))
            })
        }
        if (sum > Score.MAX) sheet.addView(text("합계 ${Score.format(sum)}점, 만점 ${Score.format(Score.MAX)}점에서 멈춰요",
            13f, Ui.SUB).apply { setPadding(0, dp(12), 0, 0) })

        setContentView(FrameLayout(this).apply {
            setOnClickListener { finish() }
            addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })
    }
}
