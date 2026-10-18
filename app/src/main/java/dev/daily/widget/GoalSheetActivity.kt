package dev.daily.widget

import android.app.Activity
import android.graphics.Paint
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.daily.widget.Ui.dp
import dev.daily.widget.Ui.round
import dev.daily.widget.Ui.text
import dev.daily.widget.Ui.topRound
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

// Full list for one period's goals, a day's todos or the backlog: toggle, edit, delete, add. Every change shows at once and is written
// in the background through Store, so closing the sheet never loses or waits on a write.
class GoalSheetActivity : Activity() {
    companion object {
        const val PERIOD = "period" // a Period name, BACKLOG or TODAY
        const val BACKLOG = "BACKLOG"
        const val TODAY = "TODAY"
        const val DAY = "day" // TODAY's date; the day the widget shows
        const val FOCUS_ADD = "focusAdd"
    }

    private var period: Period? = null
    private var day: LocalDate? = null // set for TODAY
    private val checklist: Checklist get() = if (day != null) Todos else if (period == null) Backlog else Goals
    private lateinit var list: LinearLayout
    private var clearing = false
    // Periods away from the current one, moved with the header arrows.
    private var offset = 0L
    private val date get() = period?.shift(LocalDate.now(), offset) ?: LocalDate.now()
    private val note get() = day?.let(Vault::daily) ?: period?.let { Vault.period(it, date) } ?: Vault.backlog
    private lateinit var name: TextView
    private lateinit var badge: TextView
    private lateinit var add: EditText
    private lateinit var deferAll: TextView

    private fun edit(transform: (String) -> String) {
        day?.let { Store.editTodos(this, it, transform) }
            ?: period?.let { Store.editGoals(this, it, transform, date) } ?: Store.editBacklog(this, transform)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getStringExtra(PERIOD) ?: return finish()
        period = Period.entries.firstOrNull { it.name == target }
        if (target == TODAY) day = intent.getStringExtra(DAY)?.let(LocalDate::parse) ?: LocalDate.now()
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
                addView(text(if (day != null) "오늘 할 일" else period?.title ?: "백로그", 20f, bold = true))
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
        deferAll = text("남은 ${if (day != null) "일" else "목표"} 모두 ${next}로 →", 15f, Ui.BLUE, bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                currentFocus?.clearFocus()
                defer(goals().withIndex().filter { !it.value.checked && it.value.text.isNotEmpty() }.map { it.index })
            }
        }
        if (deferrable) sheet.addView(deferAll, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
        add = input(if (period == null) "할 일 추가" else "목표 추가").apply {
            background = round(Ui.SURFACE, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            // Enter adds and keeps focus for the next one; the IME action respects Korean composition.
            setOnEditorActionListener { v, action, _ ->
                if (action != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
                val goal = v.text.toString().trim()
                if (goal.isNotEmpty()) {
                    // Todos fill the Big3 first; star or unstar them in the list.
                    val big = day != null && goals().count { it.big && it.text.isNotEmpty() } < Score.TODOS
                    edit { checklist.add(it, goal, big) }
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
        name.text = day?.let { "${Store.dayLabel(it)} · ★ Big3 1개당 평가 0.5점" } ?: period?.name(date) ?: "기한 없는 할 일"
        period?.let {
            badge.text = it.relative(offset)
            badge.setTextColor(if (offset == 0L) Ui.BLUE else Ui.TEXT)
        }
        clearing = true
        list.removeAllViews() // a focused field loses focus here and commits its text first
        clearing = false
        val shown = goals().withIndex()
            .filter { it.value.text.isNotEmpty() } // the template's blank "- [ ] " line
            .filter { day != null || period != null || !it.value.checked } // finished backlog items stay in the file only
        (if (day != null) shown.bigFirst() else shown).forEach { (i, goal) -> list.addView(row(i, goal).apply { tag = i }) }
        deferAll.visibility = if (shown.any { !it.value.checked }) View.VISIBLE else View.GONE
    }

    // Todos, weekly and monthly goals can be put off; a year's goals can't.
    private val deferrable get() = day != null || period == Period.WEEK || period == Period.MONTH
    private val next get() = if (day != null) "내일" else if (period == Period.WEEK) "다음 주" else "다음 달"

    // Undone items i go to the next day's / week's / month's note (made from the template if missing) as plain
    // items, and stay here as "- [>]".
    private fun defer(which: List<Int>) {
        val items = goals()
        val texts = which.mapNotNull { items.getOrNull(it)?.text }
        if (texts.isEmpty()) return
        val addAll = { text: String -> texts.fold(text) { t, g -> checklist.add(t, g) } }
        val to = day?.let { d ->
            Store.editTodos(this, d.plusDays(1), addAll)
            Store.editTodos(this, d) { Todos.defer(it, which) }
            Store.dayLabel(d.plusDays(1))
        } ?: period!!.let { p ->
            val from = date
            Store.editGoals(this, p, addAll, p.shift(from, 1))
            Store.editGoals(this, p, { Goals.defer(it, which) }, from)
            "$next(${p.name(p.shift(from, 1))})"
        }
        Toast.makeText(this, "${to}로 미뤘어요", Toast.LENGTH_SHORT).show()
        render()
    }

    // Drag by the handle: the row follows the finger and the rows it passes slide aside, all by translation only
    // (adding or removing views mid-touch crashed the list's draw). On release the new order is written.
    private fun handle(row: View) = text("≡", 20f, Ui.SUB).apply {
        gravity = Gravity.CENTER
        var startY = 0f
        var from = 0
        var to = 0
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    currentFocus?.clearFocus()
                    if (row.parent != list) return@setOnTouchListener false // a cleared edit re-rendered the list
                    list.parent.requestDisallowInterceptTouchEvent(true)
                    startY = e.rawY
                    from = list.indexOfChild(row)
                    to = from
                    row.background = round(Ui.SURFACE, 12)
                    row.elevation = dp(6).toFloat()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - startY
                    row.translationY = dy
                    to = (from + Math.round(dy / row.height)).coerceIn(0, list.childCount - 1)
                    for (k in 0 until list.childCount) if (k != from) list.getChildAt(k).translationY = when {
                        k in from + 1..to -> -row.height.toFloat()
                        k in to until from -> row.height.toFloat()
                        else -> 0f
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val order = (0 until list.childCount).map { list.getChildAt(it).tag as Int }.toMutableList()
                    order.add(to, order.removeAt(from))
                    if (to != from) edit { checklist.reorder(it, order) }
                    list.post { render() }
                }
            }
            true
        }
    }

    private fun row(i: Int, goal: Goal): LinearLayout {
        var current = goal
        val box = TextView(this)
        val field = input("").apply { setText(goal.text) }
        val star = text("★", 18f).apply { gravity = Gravity.CENTER }
        fun paint() {
            // Todos: Big3 square, the rest round, as on the widget.
            box.background = round(if (current.checked) Ui.BLUE else 0xFF4E4E57.toInt(), if (day != null && !current.big) 10 else 6)
            star.setTextColor(if (current.big) Ui.BLUE else 0xFF4E4E57.toInt())
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
            if (day != null) render() // a finished Big3 drops out of the pinned top
        }
        star.setOnClickListener {
            if (!current.big && goals().count { it.big && it.text.isNotEmpty() } >= Score.TODOS) {
                Toast.makeText(this, "Big3는 ${Score.TODOS}개까지", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            current = current.copy(big = !current.big)
            edit { checklist.set(it, i, current) }
            render()
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
            addView(handle(this), LinearLayout.LayoutParams(dp(32), dp(52)))
            // Wide hit area around the 20dp box.
            addView(FrameLayout(context).apply {
                setOnClickListener { box.performClick() }
                addView(box, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(40), dp(52)))
            addView(field, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            // Undone items can move (↑ out of the backlog, → elsewhere); done ones keep the slot so the columns line up.
            addView(text(if (current.checked) "" else if (day == null && period == null) "↑" else "→", 20f, Ui.SUB).apply {
                gravity = Gravity.CENTER
                // Commit a pending edit first, while index i still points at this item; an emptied one is gone instead.
                if (!current.checked) setOnClickListener { v -> currentFocus?.clearFocus(); if (v.isAttachedToWindow) send(v, i, current.text) }
            }, LinearLayout.LayoutParams(dp(30), dp(52)))
            if (day != null) addView(star, LinearLayout.LayoutParams(dp(30), dp(52)))
            addView(delete, LinearLayout.LayoutParams(dp(36), dp(52)))
        }
    }

    // Moves item i to another list: the next day / week / month (defer), today's or tomorrow's todos, the backlog,
    // or this week's / month's / year's goals. Missing notes are made from their templates. A backlog item leaves
    // the backlog; a todo or goal stays behind as "- [>]".
    private fun send(anchor: View, i: Int, goal: String) {
        val ctx = this
        val today = Store.state.value?.today ?: LocalDate.now()
        val targets = mutableListOf<Pair<String, () -> Unit>>()
        if (deferrable) targets += (if (day != null) "내일 할 일" else next) to { defer(listOf(i)) }
        if (day == null) targets += "오늘 할 일" to { Store.editTodos(ctx, today) { Todos.add(it, goal) } }
        if (day == null && period == null) targets += "내일 할 일" to { Store.editTodos(ctx, today.plusDays(1)) { Todos.add(it, goal) } }
        if (day != null || period != null) targets += "백로그" to { Store.editBacklog(ctx) { Backlog.add(it, goal) } }
        Period.entries.filter { it != period }.forEach { p -> targets += p.title to { Store.editGoals(ctx, p, { Goals.add(it, goal) }) } }
        PopupMenu(this, anchor).apply {
            targets.forEachIndexed { k, (label, _) -> menu.add(0, k, k, label) }
            setOnMenuItemClickListener { item ->
                val (label, write) = targets[item.itemId]
                write()
                if (deferrable && item.itemId == 0) return@setOnMenuItemClickListener true // defer() did the rest
                day?.let { d -> Store.editTodos(ctx, d) { Todos.defer(it, listOf(i)) } }
                    ?: period?.let { p -> Store.editGoals(ctx, p, { Goals.defer(it, listOf(i)) }, date) }
                    ?: Store.editBacklog(ctx) { Backlog.set(it, i, null) }
                Toast.makeText(ctx, "${label}(으)로 옮겼어요", Toast.LENGTH_SHORT).show()
                render()
                true
            }
        }.show()
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
