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

    // Matches Periodic Notes, not life-dashboard: "YYYY [Week] ww" is a Sunday-start locale week
    // (week 1 contains Jan 1) prefixed with the calendar year, so 2026-12-28 is "2026 Week 01".
    fun name(date: LocalDate): String = when (this) {
        WEEK -> "%d Week %02d".format(date.year, date.get(WeekFields.of(DayOfWeek.SUNDAY, 1).weekOfWeekBasedYear()))
        MONTH -> "%d-%02d".format(date.year, date.monthValue)
        YEAR -> "${date.year}"
    }
}

// done: the Tasks plugin's "✅ YYYY-MM-DD" completion stamp, kept off `text` so edits and the widget never show it.
data class Goal(val checked: Boolean, val text: String, val done: LocalDate? = null) {
    fun check(on: Boolean, today: LocalDate = LocalDate.now()) = copy(checked = on, done = if (on) done ?: today else null)
}

private val DONE = Regex("""\s*✅\s*(\d{4}-\d{2}-\d{2})\s*$""")

// A markdown checklist edited one line at a time so every other byte of the note survives
// (life-dashboard rewrites the whole section, dropping non-checkbox lines and piling up blank lines).
// `section` picks the lines the list lives in.
open class Checklist(private val section: (List<String>) -> IntRange?) {
    private val ITEM = Regex("""^(\s*- \[)([ xX])\]\s?(.*)$""")

    private fun items(lines: List<String>) = section(lines)?.filter { ITEM.matches(lines[it]) }.orEmpty()

    // Includes blank "- [ ] " lines so indices line up with set(); callers hide blanks.
    fun parse(text: String): List<Goal> {
        val lines = text.lines()
        return items(lines).map { ITEM.find(lines[it])!!.groupValues.let { g ->
            val stamp = DONE.find(g[3])
            Goal(g[2] != " ", g[3].replace(DONE, "").trim(), stamp?.let { LocalDate.parse(it.groupValues[1]) })
        } }
    }

    // goal == null deletes the i-th item.
    fun set(text: String, i: Int, goal: Goal?): String {
        val lines = text.lines().toMutableList()
        val at = items(lines).getOrNull(i) ?: return text
        if (goal == null) lines.removeAt(at)
        else lines[at] = ITEM.find(lines[at])!!.groupValues[1] + (if (goal.checked) "x" else " ") + "] " + goal.text +
            (if (goal.checked && goal.done != null) " ✅ ${goal.done}" else "")
        return lines.joinToString("\n")
    }

    // Fills a blank "- [ ] " first, otherwise appends after the last item.
    fun add(text: String, goal: String): String {
        val lines = text.lines().toMutableList()
        val items = items(lines)
        val line = "- [ ] $goal"
        val blank = items.firstOrNull { ITEM.find(lines[it])!!.groupValues[3].isBlank() }
        val section = section(lines)
        when {
            blank != null -> lines[blank] = line
            items.isNotEmpty() -> lines.add(items.last() + 1, line)
            section != null -> lines.add(section.first, line)
            else -> {
                if (lines.last().isNotEmpty()) lines.add("")
                lines.addAll(listOf("### Goal", line, ""))
            }
        }
        return lines.joinToString("\n")
    }
}

private val GOAL_HEADING = Regex("""^###\s+Goal\s*$""")

// The "### Goal" section of a weekly / monthly / yearly note.
object Goals : Checklist({ lines ->
    val h = lines.indexOfFirst { GOAL_HEADING.matches(it) }
    if (h < 0) null
    else h + 1 until ((h + 1 until lines.size).firstOrNull { lines[it].startsWith("###") } ?: lines.size)
})

// Area/Backlog.md: the whole file is the list.
object Backlog : Checklist({ lines -> lines.indices })
