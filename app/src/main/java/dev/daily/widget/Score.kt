package dev.daily.widget

// 평가 is computed, not picked: habits, sleep, screen time and today's Big3. The parts add up to 6 but the
// score stops at 5, so one miss still allows a perfect day.
object Score {
    const val MAX = 5.0
    const val TODOS = 3 // Big3 per day, and the todos the widget shows

    // got / max points for one line of the breakdown sheet.
    data class Part(val label: String, val detail: String, val got: Double, val max: Double)

    // screen: minutes to use when the note has no 스크린타임 yet (today's live count), else null.
    fun parts(note: String?, screen: Long?): List<Part> {
        val props = Frontmatter.parse(note)
        fun habit(key: String, max: Double) = (props[key] == "true").let {
            Part(key, if (it) "완료" else "아직", if (it) max else 0.0, max)
        }
        val slept = sleep(props)
        val used = minutes(props["스크린타임"])?.toLong() ?: screen
        val done = minOf(TODOS, Todos.parse(note.orEmpty()).count { it.big && it.checked && it.text.isNotEmpty() })
        return listOf(
            habit("독서", 0.5), habit("운동", 1.0), habit("금주", 1.0),
            Part("수면", slept?.let { "${duration(it)} · 7시간 이상이면 1점" } ?: "기상·취침 기록 없음",
                if ((slept ?: 0) >= 7 * 60) 1.0 else 0.0, 1.0),
            Part("스크린", used?.let { "${duration(it.toInt())} · 2시간 이하 1점, 3시간 이하 0.5점" } ?: "기록 없음",
                if (used == null) 0.0 else if (used <= 120) 1.0 else if (used <= 180) 0.5 else 0.0, 1.0),
            Part("Big3", "$done/$TODOS 완료 · 1개당 0.5점", done * 0.5, TODOS * 0.5),
        )
    }

    fun of(note: String?, screen: Long?): Double = minOf(MAX, parts(note, screen).sumOf { it.got })

    fun format(score: Double) = if (score % 1 == 0.0) "${score.toInt()}" else "$score"

    fun duration(m: Int) = "${m / 60}시간" + if (m % 60 > 0) " ${m % 60}분" else ""

    // 취침 in a note is the night before its 기상 (18:00 rule), so both live in one note.
    fun sleep(props: Map<String, String>): Int? {
        val wake = minutes(props["기상"]) ?: return null
        val bed = minutes(props["취침"]) ?: return null
        return Math.floorMod(wake - bed, 1440)
    }

    // Derived fields, refreshed on every write to a daily note.
    fun apply(text: String, screen: Long?): String {
        val sleep = sleep(Frontmatter.parse(text))?.let { "%02d:%02d".format(it / 60, it % 60) }.orEmpty()
        val withSleep = Frontmatter.set(text, "수면", sleep)
        return Frontmatter.set(withSleep, "평가", format(of(withSleep, screen)))
    }

    private fun minutes(value: String?) = Frontmatter.time(value)?.split(":")?.let { (h, m) -> h.toInt() * 60 + m.toInt() }
}
