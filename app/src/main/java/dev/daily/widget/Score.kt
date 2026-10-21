package dev.daily.widget

// What each part is worth and where the timed parts' thresholds sit (minutes), editable in the 평가 sheet.
// Changing them only affects scores computed from then on; notes keep the 평가 they were written with.
data class Rules(
    val points: Map<String, Double> = DEFAULT_POINTS,
    val wakeBy: Int = 8 * 60,
    val sleepAtLeast: Int = 7 * 60,
    val screenFull: Int = 2 * 60, // at most this: full points
    val screenHalf: Int = 3 * 60, // at most this: half points
) {
    fun pts(key: String) = points[key] ?: DEFAULT_POINTS.getValue(key)

    companion object {
        // Big3 is per completed item.
        val DEFAULT_POINTS = mapOf("독서" to .5, "운동" to 1.0, "금주" to 1.0, "기상" to .5, "수면" to .5, "스크린" to 1.0, "Big3" to .5)
    }
}

// 평가 is computed, not picked: habits, sleep, screen time and today's Big3. By default the parts add up to 6
// but the score stops at 5, so one miss still allows a perfect day.
object Score {
    const val MAX = 5.0
    const val TODOS = 3 // Big3 per day, and the todos the widget shows

    // Loaded from Prefs on every Store refresh.
    @Volatile var rules = Rules()

    // got / max points for one line of the breakdown sheet.
    data class Part(val label: String, val detail: String, val got: Double, val max: Double)

    // screen: minutes to use when the note has no 스크린타임 yet (today's live count), else null.
    fun parts(note: String?, screen: Long?, r: Rules = rules): List<Part> {
        val props = Frontmatter.parse(note)
        fun habit(key: String) = (props[key] == "true").let {
            Part(key, if (it) "완료" else "아직", if (it) r.pts(key) else 0.0, r.pts(key))
        }
        val slept = sleep(props)
        val wake = minutes(props["기상"])
        val used = minutes(props["스크린타임"])?.toLong() ?: screen
        val done = minOf(TODOS, Todos.parse(note.orEmpty()).count { it.big && it.checked && it.text.isNotEmpty() })
        val wakePts = r.pts("기상"); val sleepPts = r.pts("수면"); val screenPts = r.pts("스크린"); val bigPts = r.pts("Big3")
        return listOf(
            habit("독서"), habit("운동"), habit("금주"),
            Part("기상", wake?.let { "${Frontmatter.time(props["기상"])} · ${clock(r.wakeBy)}까지 일어나면 ${format(wakePts)}점" } ?: "기록 없음",
                if (wake != null && wake <= r.wakeBy) wakePts else 0.0, wakePts),
            Part("수면", slept?.let { "${duration(it)} · ${duration(r.sleepAtLeast)} 이상이면 ${format(sleepPts)}점" } ?: "기상·취침 기록 없음",
                if ((slept ?: 0) >= r.sleepAtLeast) sleepPts else 0.0, sleepPts),
            Part("스크린", used?.let { "${duration(it.toInt())} · ${duration(r.screenFull)} 이하 ${format(screenPts)}점, " +
                "${duration(r.screenHalf)} 이하 ${format(screenPts / 2)}점" } ?: "기록 없음",
                if (used == null) 0.0 else if (used <= r.screenFull) screenPts else if (used <= r.screenHalf) screenPts / 2 else 0.0, screenPts),
            Part("Big3", "$done/$TODOS 완료 · 1개당 ${format(bigPts)}점", done * bigPts, TODOS * bigPts),
        )
    }

    fun of(note: String?, screen: Long?): Double = minOf(MAX, parts(note, screen).sumOf { it.got })

    fun format(score: Double) = if (score % 1 == 0.0) "${score.toInt()}" else "$score"

    fun clock(m: Int) = "%d:%02d".format(m / 60, m % 60)

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
