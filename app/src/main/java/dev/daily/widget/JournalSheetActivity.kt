package dev.daily.widget

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.daily.widget.Ui.dp
import dev.daily.widget.Ui.round
import dev.daily.widget.Ui.text
import dev.daily.widget.Ui.topRound
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

// The day's "### Journal" section in one text box; written through Store when the sheet closes.
class JournalSheetActivity : Activity() {
    companion object { const val DATE = "date" }

    private lateinit var date: LocalDate
    private lateinit var field: EditText
    private var saved = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Store.state.value == null) runBlocking { Store.refresh(this@JournalSheetActivity) }
        date = intent.getStringExtra(DATE)?.let(LocalDate::parse) ?: LocalDate.now()
        val file = Vault.daily(date)
        saved = Journal.get(Store.state.value?.note(file) ?: Vault.read(file))

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = topRound(Ui.SHEET, 24)
            isClickable = true
            setOnApplyWindowInsetsListener { v, insets ->
                val bottom = maxOf(insets.getInsets(WindowInsets.Type.systemBars()).bottom,
                    insets.getInsets(WindowInsets.Type.ime()).bottom)
                v.setPadding(dp(24), dp(28), dp(24), dp(16) + bottom)
                insets
            }
        }
        sheet.addView(text("Journal", 20f, bold = true))
        sheet.addView(text(Store.dayLabel(date), 14f, Ui.SUB).apply { setPadding(0, dp(6), 0, dp(16)) })
        field = EditText(this).apply {
            setText(saved)
            hint = "오늘 있었던 일, 생각"
            setHintTextColor(Ui.SUB)
            setTextColor(Ui.TEXT)
            textSize = 16f
            gravity = Gravity.TOP
            minLines = 6
            maxLines = 12
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = round(Ui.SURFACE, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        sheet.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(FrameLayout(this).apply {
            setOnClickListener { finish() }
            addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })
        field.requestFocus()
        field.setSelection(field.text.length)
        field.post { getSystemService(InputMethodManager::class.java).showSoftInput(field, 0) }
    }

    override fun onPause() {
        val body = field.text.toString().trim()
        if (body != saved) {
            saved = body
            Store.commit(this, Vault.daily(date), "Daily", "Journal", null) { Journal.set(it, body) }
        }
        super.onPause()
    }
}
