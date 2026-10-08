package dev.daily.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class XpTest {
    private val tue = LocalDate.of(2026, 10, 6) // week of Sun 10-04
    private fun m(d: LocalDate, id: String) = Xp.rarity(d, id).mult

    @Test
    fun doneStampRoundTrips() {
        val t = Goals.set("### Goal\n- [ ] 러닝", 0, Goal(false, "러닝").check(true, tue))
        assertEquals("### Goal\n- [x] 러닝 ✅ 2026-10-06", t)
        assertEquals(Goal(true, "러닝", tue), Goals.parse(t)[0])
        assertEquals("### Goal\n- [ ] 러닝", Goals.set(t, 0, Goals.parse(t)[0].check(false)))
    }

    @Test
    fun weekSumsHabitsGoalsTasksAndBonuses() {
        val mon = LocalDate.of(2026, 10, 5)
        val notes = mapOf(
            Vault.daily(tue) to "---\n독서: true\n운동: true\n금주: true\n취침: 23:00\n기상: 06:30\n---",
            Vault.daily(mon) to "---\n운동: true\nbackfill: true\n---", // imputed: ignored
            Vault.period(Period.WEEK, tue) to "### Goal\n- [x] a ✅ 2026-10-06\n- [x] b ✅ 2026-10-05",
            Vault.period(Period.MONTH, tue) to "### Goal\n- [x] c ✅ 2026-09-30\n- [ ] d",
            Vault.backlog to "- [x] t ✅ 2026-10-03\n- [x] u ✅ 2026-10-04\n- [x] old",
        ).mapKeys { it.key.path }
        val expected = 10 * m(tue, "독서") + 20 * m(tue, "운동") + 20 * m(tue, "금주") + Xp.DAY_BONUS + 20 * m(tue, "기상") +
            100 * m(tue, "WEEK:a") + 100 * m(mon, "WEEK:b") + Xp.WEEK_BONUS + 10 * m(LocalDate.of(2026, 10, 4), "task:u")
        assertEquals(expected, Xp.week(tue) { notes[it.path] })
    }

    @Test
    fun rarityIsMostlyNormal() {
        val rolls = (0 until 2000).map { Xp.rarity(tue.plusDays(it.toLong()), "독서") }
        val normal = rolls.count { it == Xp.Rarity.NORMAL } / 2000.0
        assert(normal in .70..0.80) { "normal share $normal" }
    }
}
