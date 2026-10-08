package dev.daily.widget

import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

// Weekly XP, derived from the notes alone (nothing stored): habits and 기상 from daily notes, goals and
// backlog items by their "✅ date" stamp. Resets every Sunday, like the week notes.
object Xp {
    const val TARGET = 1000
    const val TASK = 10
    const val WAKE = 20
    const val DAY_BONUS = 50
    const val WEEK_BONUS = 200
    const val MONTH_BONUS = 500
    val HABIT = mapOf("독서" to 10, "운동" to 20, "금주" to 20)

    fun goal(p: Period) = when (p) { Period.WEEK -> 100; Period.MONTH -> 300; Period.YEAR -> 1000 }

    enum class Rarity(val mult: Int) { NORMAL(1), CRIT(2), JACKPOT(5) }

    // Looks random per tap but is fixed by (date, item), so it never needs saving and undo/redo is stable.
    fun rarity(date: LocalDate, id: String): Rarity = Math.floorMod("$date|$id".hashCode(), 100).let {
        if (it >= 95) Rarity.JACKPOT else if (it >= 75) Rarity.CRIT else Rarity.NORMAL
    }

    fun goalId(p: Period, text: String) = "${p.name}:$text"
    fun taskId(text: String) = "task:$text"

    private fun week(today: LocalDate) = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).let { start ->
        (0L..6L).map { start.plusDays(it) }
    }

    private fun periodFiles(today: LocalDate) =
        Period.entries.flatMap { p -> week(today).map { p to Vault.period(p, it) } }.distinct()

    // Every note week() reads; Store loads them so the bar follows optimistic edits.
    fun files(today: LocalDate): List<File> =
        week(today).map(Vault::daily) + Vault.backlog + periodFiles(today).map { it.second }

    fun week(today: LocalDate, note: (File) -> String?): Int {
        val days = week(today)
        val inWeek = { d: LocalDate? -> d != null && d in days }
        var xp = 0
        for (d in days) {
            val props = Frontmatter.parse(note(Vault.daily(d)))
            if (props["backfill"] == "true") continue // imputed days were never really done
            HABIT.forEach { (k, v) -> if (props[k] == "true") xp += v * rarity(d, k).mult }
            if (HABIT.keys.all { props[it] == "true" }) xp += DAY_BONUS
            if ((Score.sleep(props) ?: 0) >= 7 * 60) xp += WAKE * rarity(d, "기상").mult
        }
        for ((p, f) in periodFiles(today)) {
            val goals = Goals.parse(note(f) ?: continue).filter { it.text.isNotEmpty() }
            goals.filter { it.checked && inWeek(it.done) }.forEach { xp += goal(p) * rarity(it.done!!, goalId(p, it.text)).mult }
            if (p != Period.YEAR && goals.isNotEmpty() && goals.all { it.checked } && inWeek(goals.mapNotNull { it.done }.maxOrNull()))
                xp += if (p == Period.WEEK) WEEK_BONUS else MONTH_BONUS
        }
        Backlog.parse(note(Vault.backlog).orEmpty()).filter { it.checked && inWeek(it.done) }
            .forEach { xp += TASK * rarity(it.done!!, taskId(it.text)).mult }
        return xp
    }
}
