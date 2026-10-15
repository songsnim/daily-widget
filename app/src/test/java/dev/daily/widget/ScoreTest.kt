package dev.daily.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreTest {
    private fun note(props: String, todos: String = "") = "---\n$props\n---\n\n### Todo\n$todos\n### Journal\n"

    @Test
    fun addsUpHabitsSleepScreenAndTodos() {
        val habits = "독서: true\n운동: true\n금주: true\n취침: 23:30\n기상: 06:30\n스크린타임: 01:59"
        assertEquals(4.5, Score.of(note(habits), null), 0.0)
        // 6 points of parts, capped at 5; an old manual 보너스 no longer counts
        assertEquals(5.0, Score.of(note("$habits\n보너스: true", "- [x] a\n- [x] b\n- [ ] c\n"), null), 0.0)
        // 6h59m sleep, 2h30m screen
        assertEquals(0.5, Score.of(note("취침: 23:31\n기상: 06:30\n스크린타임: 02:30"), null), 0.0)
        // note value wins over the live count; live count used when the note has none
        assertEquals(0.0, Score.of(note("스크린타임: 03:01"), 10), 0.0)
        assertEquals(1.0, Score.of(null, 120), 0.0)
        assertEquals("---\n독서: true\n수면:\n평가: 0.5\n---", Score.apply("---\n독서: true\n---", null))
        assertEquals("---\n취침: 23:40\n기상: 07:10\n수면: 07:30\n평가: 1\n---", Score.apply("---\n취침: 23:40\n기상: 07:10\n---", null))
    }

    @Test
    fun todosScoreHalfEachUpToThree() {
        assertEquals(1.0, Score.of(note("", "- [x] a\n- [x] b\n- [ ] \n"), null), 0.0)
        assertEquals(1.5, Score.of(note("", "- [x] a\n- [x] b\n- [x] c\n- [x] d\n"), null), 0.0)
        // checkboxes outside ### Todo don't count
        assertEquals(0.0, Score.of("---\n---\n### Journal\n- [x] a\n", null), 0.0)
        assertEquals("---\n수면:\n평가: 0.5\n---\n### Todo\n- [x] a ✅ 2026-10-15",
            Score.apply(Todos.set("---\n---\n### Todo\n- [ ] a", 0, Goal(true, "a", java.time.LocalDate.of(2026, 10, 15))), null))
    }
}
