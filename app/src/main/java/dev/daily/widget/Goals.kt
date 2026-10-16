package dev.daily.widget

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields

enum class Period(val label: String, val title: String, val folder: String, val template: String,
                  private val current: String, private val unit: String) {
    WEEK("주간", "주간 목표", "Area/Daily/Weeks", "Weekly", "이번 주", "주"),
    MONTH("월간", "월간 목표", "Area/Daily/Months", "Monthly", "이번 달", "달"),
    YEAR("올해", "올해 목표", "Area/Daily/Years", "Yearly", "올해", "년");

    fun shift(date: LocalDate, n: Long): LocalDate = when (this) {
        WEEK -> date.plusWeeks(n)
        MONTH -> date.plusMonths(n)
        YEAR -> date.plusYears(n)
    }

    // How far a browsed period is from now: "이번 주", "-3주", "+1달".
    fun relative(n: Long) = if (n == 0L) current else "%+d%s".format(n, unit)

    // Matches Periodic Notes, not life-dashboard: "YYYY [Week] ww" is a Monday-start locale week (calendar
    // plugin weekStart=monday keeps moment's doy 6, so week 1 contains Jan 2) prefixed with the calendar
    // year, so 2026-12-28 is "2026 Week 01".
    fun name(date: LocalDate): String = when (this) {
        WEEK -> "%d Week %02d".format(date.year, date.get(WeekFields.of(DayOfWeek.MONDAY, 2).weekOfWeekBasedYear()))
        MONTH -> "%d-%02d".format(date.year, date.monthValue)
        YEAR -> "${date.year}"
    }
}

// done: the Tasks plugin's "✅ YYYY-MM-DD" completion stamp, kept off `text` so edits and the widget never show it.
// big: a Big3 todo, marked with the Tasks plugin's highest priority "🔺" (kept off `text` the same way).
data class Goal(val checked: Boolean, val text: String, val done: LocalDate? = null, val big: Boolean = false) {
    fun check(on: Boolean, today: LocalDate = LocalDate.now()) = copy(checked = on, done = if (on) done ?: today else null)
}

private val DONE = Regex("""\s*✅\s*(\d{4}-\d{2}-\d{2})\s*$""")
private val BIG = Regex("""\s*🔺""")

// Unfinished Big3 first, everything else in note order: how the todo sheet and the widget list a day.
fun List<IndexedValue<Goal>>.bigFirst() = sortedBy { if (it.value.big && !it.value.checked) 0 else 1 }

// A markdown checklist edited one line at a time so every other byte of the note survives
// (life-dashboard rewrites the whole section, dropping non-checkbox lines and piling up blank lines).
// `section` picks the lines the list lives in; `heading` starts one when the note has none.
open class Checklist(private val heading: String, private val section: (List<String>) -> IntRange?) {
    private val ITEM = Regex("""^(\s*- \[)([ xX])\]\s?(.*)$""")

    private fun items(lines: List<String>) = section(lines)?.filter { ITEM.matches(lines[it]) }.orEmpty()

    // Includes blank "- [ ] " lines so indices line up with set(); callers hide blanks.
    fun parse(text: String): List<Goal> {
        val lines = text.lines()
        return items(lines).map { ITEM.find(lines[it])!!.groupValues.let { g ->
            val stamp = DONE.find(g[3])
            val rest = g[3].replace(DONE, "")
            Goal(g[2] != " ", rest.replace(BIG, "").trim(), stamp?.let { LocalDate.parse(it.groupValues[1]) }, BIG.containsMatchIn(rest))
        } }
    }

    // goal == null deletes the i-th item.
    fun set(text: String, i: Int, goal: Goal?): String {
        val lines = text.lines().toMutableList()
        val at = items(lines).getOrNull(i) ?: return text
        if (goal == null) lines.removeAt(at)
        else lines[at] = ITEM.find(lines[at])!!.groupValues[1] + (if (goal.checked) "x" else " ") + "] " + line(goal)
        return lines.joinToString("\n")
    }

    private fun line(goal: Goal) = goal.text + (if (goal.big) " 🔺" else "") +
        (if (goal.checked && goal.done != null) " ✅ ${goal.done}" else "")

    // Puts the items `order` names (item indices) into the lines those same items occupy, in that order;
    // every other line, hidden items included, stays where it is.
    fun reorder(text: String, order: List<Int>): String {
        val lines = text.lines().toMutableList()
        val items = items(lines)
        val at = order.map { items.getOrNull(it) ?: return text }
        val moved = at.map { lines[it] }
        at.sorted().forEachIndexed { k, line -> lines[line] = moved[k] }
        return lines.joinToString("\n")
    }

    // Fills a blank "- [ ] " first, otherwise appends after the last item.
    fun add(text: String, goal: String, big: Boolean = false): String {
        val lines = text.lines().toMutableList()
        val items = items(lines)
        val line = "- [ ] " + line(Goal(false, goal, big = big))
        val blank = items.firstOrNull { ITEM.find(lines[it])!!.groupValues[3].isBlank() }
        val section = section(lines)
        when {
            blank != null -> lines[blank] = line
            items.isNotEmpty() -> lines.add(items.last() + 1, line)
            section != null -> lines.add(section.first, line)
            else -> {
                if (lines.last().isNotEmpty()) lines.add("")
                lines.addAll(listOf("### $heading", line, ""))
            }
        }
        return lines.joinToString("\n")
    }
}

// The lines under "### <heading>", up to the next "###".
private fun under(heading: String): (List<String>) -> IntRange? {
    val re = Regex("""^###\s+$heading\s*$""")
    return { lines ->
        val h = lines.indexOfFirst { re.matches(it) }
        if (h < 0) null
        else h + 1 until ((h + 1 until lines.size).firstOrNull { lines[it].startsWith("###") } ?: lines.size)
    }
}

// The "### Goal" section of a weekly / monthly / yearly note.
object Goals : Checklist("Goal", under("Goal"))

// The "### Todo" section of a daily note: up to three Big3 (scored, pinned on top) and any number of others.
object Todos : Checklist("Todo", under("Todo"))

// Area/Backlog.md: the whole file is the list.
object Backlog : Checklist("", { lines -> lines.indices })
