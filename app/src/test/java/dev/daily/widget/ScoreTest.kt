package dev.daily.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreTest {
    @Test
    fun addsUpHabitsSleepScreenAndBonus() {
        val all = mapOf("독서" to "true", "운동" to "true", "금주" to "true", "보너스" to "true",
            "취침" to "23:30", "기상" to "06:30", "스크린타임" to "01:59")
        assertEquals(5.0, Score.of(all, null), 0.0)
        // 6h59m sleep, 2h30m screen
        assertEquals(0.5, Score.of(mapOf("취침" to "23:31", "기상" to "06:30", "스크린타임" to "02:30"), null), 0.0)
        // note value wins over the live count; live count used when the note has none
        assertEquals(0.0, Score.of(mapOf("스크린타임" to "03:01"), 10), 0.0)
        assertEquals(1.0, Score.of(emptyMap(), 120), 0.0)
        assertEquals("---\n독서: true\n수면:\n평가: 0.5\n---", Score.apply("---\n독서: true\n---", null))
        assertEquals("---\n취침: 23:40\n기상: 07:10\n수면: 07:30\n평가: 1\n---", Score.apply("---\n취침: 23:40\n기상: 07:10\n---", null))
    }
}
