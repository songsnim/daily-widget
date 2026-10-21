package dev.daily.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreTest {
    private fun note(props: String, todos: String = "") = "---\n$props\n---\n\n### Todo\n$todos\n### Journal\n"

    @Test
    fun addsUpHabitsSleepScreenAndTodos() {
        val habits = "독서: true\n운동: true\n금주: true\n취침: 23:30\n기상: 06:30\n스크린타임: 01:59"
        assertEquals(4.0, Score.of(note(habits), null), 0.0)
        // 6 points of parts, capped at 5; an old manual 보너스 no longer counts
        assertEquals(5.0, Score.of(note("$habits\n보너스: true", "- [x] a 🔺\n- [x] b 🔺\n- [ ] c 🔺\n"), null), 0.0)
        // in bed after 22:45 (0) but up by 8 (0.5), 2h30m screen (0.5)
        assertEquals(1.0, Score.of(note("취침: 23:31\n기상: 06:30\n스크린타임: 02:30"), null), 0.0)
        // 22:45 and 8:00 still count, a minute later doesn't; past midnight is later than 22:45
        assertEquals(1.0, Score.of(note("취침: 22:45\n기상: 08:00"), null), 0.0)
        assertEquals(0.0, Score.of(note("취침: 22:46\n기상: 08:01"), null), 0.0)
        assertEquals(0.0, Score.of(note("취침: 00:30"), null), 0.0)
        assertEquals(0.5, Score.of(note("취침: 21:00"), null), 0.0)
        // note value wins over the live count; live count used when the note has none
        assertEquals(0.0, Score.of(note("스크린타임: 03:01"), 10), 0.0)
        assertEquals(1.0, Score.of(null, 120), 0.0)
        assertEquals("---\n독서: true\n수면:\n평가: 0.5\n---", Score.apply("---\n독서: true\n---", null))
        assertEquals("---\n취침: 23:40\n기상: 07:10\n수면: 07:30\n평가: 0.5\n---", Score.apply("---\n취침: 23:40\n기상: 07:10\n---", null))
    }

    @Test
    fun customRulesMoveThresholdsAndPoints() {
        val r = Rules(points = Rules.DEFAULT_POINTS + ("기상" to 1.0) + ("Big3" to 1.0),
            wakeBy = 7 * 60, bedBy = 60, screenFull = 60, screenHalf = 90)
        fun of(props: String, todos: String = "") = Score.parts(note(props, todos), null, r).sumOf { it.got }
        assertEquals(1.5, of("취침: 01:00\n기상: 07:00"), 0.0) // up by 7:00 (1) + in bed by 01:00 (0.5)
        assertEquals(0.5, of("취침: 01:00\n기상: 07:01"), 0.0)
        assertEquals(1.0, of("스크린타임: 01:00"), 0.0)
        assertEquals(0.5, of("스크린타임: 01:30"), 0.0)
        assertEquals(0.0, of("스크린타임: 01:31"), 0.0)
        assertEquals(2.0, of("", "- [x] a 🔺\n- [x] b 🔺\n"), 0.0)
    }

    @Test
    fun big3ScoreHalfEachUpToThree() {
        // only Big3 (🔺) count; plain todos don't
        assertEquals(1.0, Score.of(note("", "- [x] a 🔺\n- [x] b 🔺\n- [x] plain\n- [ ] \n"), null), 0.0)
        assertEquals(1.5, Score.of(note("", "- [x] a 🔺\n- [x] b 🔺\n- [x] c 🔺\n- [x] d 🔺\n"), null), 0.0)
        // checkboxes outside ### Todo don't count
        assertEquals(0.0, Score.of("---\n---\n### Journal\n- [x] a 🔺\n", null), 0.0)
        assertEquals("---\n수면:\n평가: 0.5\n---\n### Todo\n- [x] a 🔺 ✅ 2026-10-15",
            Score.apply(Todos.set("---\n---\n### Todo\n- [ ] a 🔺", 0,
                Goal(true, "a", java.time.LocalDate.of(2026, 10, 15), big = true)), null))
    }
}
