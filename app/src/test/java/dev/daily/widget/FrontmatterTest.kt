package dev.daily.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FrontmatterTest {
    private val note = "---\ncreate: 2026-08-09 Sun 21:28:45\n독서:\n취침: 01:00\n---\n\nbody: not frontmatter\n"

    @Test
    fun setReplacesFillsAndAppends() {
        var t = Frontmatter.set(note, "독서", "true")
        t = Frontmatter.set(t, "취침", "23:40")
        t = Frontmatter.set(t, "평가", "4")
        val p = Frontmatter.parse(t)
        assertEquals("true", p["독서"])
        assertEquals("23:40", p["취침"])
        assertEquals("4", p["평가"])
        assertEquals("2026-08-09 Sun 21:28:45", p["create"])
        assertEquals(null, p["body"])
        assert(t.endsWith("---\n\nbody: not frontmatter\n"))
    }

    @Test
    fun timeAcceptsDashboardFormats() {
        assertEquals("07:00", Frontmatter.time("07:00"))
        assertEquals("00:53", Frontmatter.time("0:53"))
        assertEquals("01:30", Frontmatter.time("2026-02-03T01:30:00.000+09:00"))
        assertEquals(null, Frontmatter.time(""))
    }

    @Test
    fun bedtimeGoesToNextDayAfterCutoff() {
        val d = LocalDate.of(2026, 10, 5)
        assertEquals(d.plusDays(1), dateFor("취침", d.atTime(23, 40)))
        assertEquals(d, dateFor("취침", d.atTime(1, 30)))
        assertEquals(d, dateFor("기상", d.atTime(23, 40)))
        assertEquals("2026-10-05 Mon", Vault.name(d))
    }
}
