package dev.daily.widget

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TimePicker
import dev.daily.widget.Ui.button
import dev.daily.widget.Ui.dp
import dev.daily.widget.Ui.topRound
import dev.daily.widget.Ui.text
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Bottom sheet for editing a recorded time.
class SheetActivity : Activity() {
    companion object { const val KEY = "key"; const val DATE = "date" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(KEY) ?: return finish()
        val date = intent.getStringExtra(DATE)?.let(LocalDate::parse) ?: dateFor(key, LocalDateTime.now())
        val current = Frontmatter.parse(Vault.read(Vault.daily(date)))[key].orEmpty()

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = topRound(Ui.SHEET, 24)
            isClickable = true
            setPadding(dp(24), dp(28), dp(24), dp(16))
        }
        sheet.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(dp(24), dp(28), dp(24), dp(16) + insets.getInsets(WindowInsets.Type.systemBars()).bottom)
            insets
        }

        // The widget updates instantly; the write and its toast continue after the sheet closes.
        fun save(value: String) { Store.setProp(this, key, value, date, announce = true); finish() }

        val time = Frontmatter.time(current)
        sheet.addView(text("$key 시간 수정", 20f, bold = true))
        sheet.addView(text("${Store.dayLabel(date)} 노트 · 지금은 ${time ?: "비어 있어요"}", 14f, Ui.SUB)
            .apply { setPadding(0, dp(6), 0, dp(8)) })
        val picker = (layoutInflater.inflate(R.layout.time_picker, sheet, false) as TimePicker).apply {
            setIs24HourView(true)
            time?.split(":")?.let { (h, m) -> hour = h.toInt(); minute = m.toInt() }
        }
        sheet.addView(picker)
        sheet.addView(text("기록 지우기", 14f, Ui.SUB).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(16))
            setOnClickListener { save("") }
        })
        val row = LinearLayout(this)
        row.addView(button("지금 시각으로", Ui.SURFACE) {
            save(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(button("저장", Ui.BLUE) { save("%02d:%02d".format(picker.hour, picker.minute)) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        sheet.addView(row)

        setContentView(FrameLayout(this).apply {
            setOnClickListener { finish() }
            addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })
    }
}
