package dev.daily.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class GoalsTest {
    private val week = "---\ngoal:\n---\n\n### Goal\n- [ ] \nmemo line\n\n### Journal\ntext"

    @Test
    fun addFillsTemplateBlankThenAppends() {
        var t = Goals.add(week, "이력서 3곳")
        t = Goals.add(t, "운동 3회")
        assertEquals(listOf(Goal(false, "이력서 3곳"), Goal(false, "운동 3회")), Goals.parse(t))
        assertEquals("---\ngoal:\n---\n\n### Goal\n- [ ] 이력서 3곳\n- [ ] 운동 3회\nmemo line\n\n### Journal\ntext", t)
    }

    @Test
    fun toggleEditDeleteTouchOnlyThatLine() {
        val year = "### Goal\n- [ ] 이직\n- [x] 순산 ✅ 2026-07-04\n\n\n### Journal"
        val toggled = Goals.set(year, 1, Goal(false, "순산 ✅ 2026-07-04"))
        assertEquals("### Goal\n- [ ] 이직\n- [ ] 순산 ✅ 2026-07-04\n\n\n### Journal", toggled)
        assertEquals("### Goal\n- [x] 순산 ✅ 2026-07-04\n\n\n### Journal", Goals.set(year, 0, null))
        assertEquals(year, Goals.set(year, 5, null))
    }

    @Test
    fun addCreatesSectionWhenMissing() {
        assertEquals("---\n---\n\n### Goal\n- [ ] a\n", Goals.add("---\n---\n", "a"))
    }

    @Test
    fun periodNamesMatchPeriodicNotes() {
        assertEquals("2026 Week 09", Period.WEEK.name(LocalDate.of(2026, 2, 22))) // Sunday starts the week
        assertEquals("2026 Week 08", Period.WEEK.name(LocalDate.of(2026, 2, 21)))
        assertEquals("2026 Week 01", Period.WEEK.name(LocalDate.of(2026, 12, 28)))
        assertEquals("2026-10", Period.MONTH.name(LocalDate.of(2026, 10, 5)))
        assertEquals("2026", Period.YEAR.name(LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun backlogIsTheWholeFile() {
        var t = Backlog.add("", "엔진오일 교체")
        t = Backlog.add(t, "여권 갱신")
        assertEquals("- [ ] 엔진오일 교체\n- [ ] 여권 갱신\n", t)
        t = Backlog.set(t, 0, Goal(true, "엔진오일 교체"))
        assertEquals(listOf(Goal(true, "엔진오일 교체"), Goal(false, "여권 갱신")), Backlog.parse(t))
        // QuickAdd appends "- [ ] x" lines at the end; the widget keeps appending after the last item.
        assertEquals("- [ ] a\n- [ ] b\n", Backlog.add("- [ ] a\n", "b"))
    }

    @Test
    fun browsingPeriodsShiftsAndLabels() {
        val d = LocalDate.of(2026, 10, 6)
        assertEquals("2026 Week 38", Period.WEEK.name(Period.WEEK.shift(d, -3)))
        assertEquals("2026-11", Period.MONTH.name(Period.MONTH.shift(d, 1)))
        assertEquals(listOf("이번 주", "-3주", "+1달", "올해"),
            listOf(Period.WEEK.relative(0), Period.WEEK.relative(-3), Period.MONTH.relative(1), Period.YEAR.relative(0)))
    }
}
