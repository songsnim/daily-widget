package dev.daily.widget

import android.app.Activity
import android.graphics.Paint
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.daily.widget.Ui.dp
import dev.daily.widget.Ui.round
import dev.daily.widget.Ui.text
import dev.daily.widget.Ui.topRound
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

// Full list for one period's goals or for the backlog: toggle, edit, delete, add. Every change shows at once and is written
// in the background through Store, so closing the sheet never loses or waits on a write.
class GoalSheetActivity : Activity() {
    companion object {
        const val PERIOD = "period" // a Period name, or BACKLOG
        const val BACKLOG = "BACKLOG"
        const val FOCUS_ADD = "focusAdd"
    }

    private var period: Period? = null
    private val checklist: Checklist get() = if (period == null) Backlog else Goals
    private lateinit var list: LinearLayout
    private var clearing = false
    // Periods away from the current one, moved with the header arrows.
    private var offset = 0L
    private val date get() = period?.shift(LocalDate.now(), offset) ?: LocalDate.now()
    private val note get() = period?.let { Vault.period(it, date) } ?: Vault.backlog
    private lateinit var name: TextView
    private lateinit var badge: TextView

    private fun edit(transform: (String) -> String) {
        period?.let { Store.editGoals(this, it, transform, date) } ?: Store.editBacklog(this, transform)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getStringExtra(PERIOD) ?: return finish()
        period = Period.entries.firstOrNull { it.name == target }
        // Edits apply to the cached notes first; make sure there is a cache when the widget never loaded.
        if (Store.state.value == null) runBlocking { Store.refresh(this@GoalSheetActivity) }

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
        name = text("", 14f, Ui.SUB)
        badge = text("", 14f, bold = true).apply { setPadding(dp(8), 0, 0, 0) }
        sheet.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(period?.title ?: "백로그", 20f, bold = true))
                addView(LinearLayout(context).apply {
                    setPadding(0, dp(6), 0, 0)
                    addView(name)
                    if (period != null) addView(badge)
                })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (period != null) listOf("‹" to -1L, "›" to 1L).forEach { (arrow, delta) ->
                addView(text(arrow, 26f, Ui.SUB, bold = true).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { move(delta) }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(16) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheet.addView(ScrollView(this).apply { addView(list) })
        val add = input(if (period == null) "할 일 추가" else "목표 추가").apply {
            background = round(Ui.SURFACE, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            // Enter adds and keeps focus for the next one; the IME action respects Korean composition.
            setOnEditorActionListener { v, action, _ ->
                if (action != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
                val goal = v.text.toString().trim()
                if (goal.isNotEmpty()) {
                    edit { checklist.add(it, goal) }
                    v.text = ""
                    render()
                }
                true
            }
        }
        sheet.addView(add, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        setContentView(FrameLayout(this).apply {
            setOnClickListener { finish() }
            addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })
        render()
        if (intent.getBooleanExtra(FOCUS_ADD, false) || goals().none { it.text.isNotEmpty() }) {
            add.requestFocus()
            add.post { getSystemService(InputMethodManager::class.java).showSoftInput(add, 0) }
        }
    }

    private fun move(delta: Long) {
        currentFocus?.clearFocus() // commits an edit in progress to the period it belongs to
        offset += delta
        render()
    }

    // Reads the optimistic copy, so it already includes edits whose writes are still running.
    private fun goals(): List<Goal> = Store.state.value?.note(note)?.let(checklist::parse)
        ?: Vault.read(note)?.let(checklist::parse).orEmpty()

    private fun render() {
        name.text = period?.name(date) ?: "기한 없는 할 일"
        period?.let {
            badge.text = it.relative(offset)
            badge.setTextColor(if (offset == 0L) Ui.BLUE else Ui.TEXT)
        }
        clearing = true
        list.removeAllViews() // a focused field loses focus here and commits its text first
        clearing = false
        goals().forEachIndexed { i, goal ->
            if (goal.text.isEmpty()) return@forEachIndexed // the template's blank "- [ ] " line
            if (period == null && goal.checked) return@forEachIndexed // finished backlog items stay in the file only
            list.addView(row(i, goal))
        }
    }

    private fun row(i: Int, goal: Goal): LinearLayout {
        var current = goal
        val box = TextView(this)
        val field = input("").apply { setText(goal.text) }
        fun paint() {
            box.background = round(if (current.checked) Ui.BLUE else 0xFF4E4E57.toInt(), 6)
            field.setTextColor(if (current.checked) Ui.SUB else Ui.TEXT)
            field.paintFlags = if (current.checked) field.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                else field.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
        fun commitText(rerender: Boolean = true) {
            val t = field.text.toString().trim()
            if (t == current.text) return
            if (t.isEmpty()) {
                edit { checklist.set(it, i, null) }
                if (rerender) render()
            } else {
                current = current.copy(text = t)
                edit { checklist.set(it, i, current) }
            }
        }
        paint()
        box.setOnClickListener {
            current = current.check(!current.checked)
            paint()
            edit { checklist.set(it, i, current) }
        }
        field.setOnEditorActionListener { v, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { commitText(); v.clearFocus() }
            action == EditorInfo.IME_ACTION_DONE
        }
        field.setOnFocusChangeListener { _, focused -> if (!focused) commitText(rerender = !clearing) }
        val delete = text("×", 22f, Ui.SUB).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                edit { checklist.set(it, i, null) }
                render()
            }
        }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            // Wide hit area around the 20dp box.
            addView(FrameLayout(context).apply {
                setOnClickListener { box.performClick() }
                addView(box, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(44), dp(52)))
            addView(field, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(delete, LinearLayout.LayoutParams(dp(44), dp(52)))
        }
    }

    private fun input(hint: String) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(Ui.SUB)
        setTextColor(Ui.TEXT)
        textSize = 16f
        background = null
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_DONE
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    override fun onPause() {
        currentFocus?.clearFocus() // commits an edit in progress
        super.onPause()
    }
}
