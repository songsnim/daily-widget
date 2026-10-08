package dev.daily.widget

// 평가 is computed, not picked: habits, sleep and screen time, plus one manual +0.5 for a good day. Max 5.
object Score {
    const val BONUS = "보너스"

    // screen: minutes to use when the note has no 스크린타임 yet (today's live count), else null.
    fun of(props: Map<String, String>, screen: Long?): Double {
        var score = 0.0
        if (props["독서"] == "true") score += 0.5
        if (props["운동"] == "true") score += 1
        if (props["금주"] == "true") score += 1
        if (props[BONUS] == "true") score += 0.5
        if ((sleep(props) ?: 0) >= 7 * 60) score += 1
        val used = minutes(props["스크린타임"])?.toLong() ?: screen
        if (used != null) score += if (used <= 120) 1.0 else if (used <= 180) 0.5 else 0.0
        return score
    }

    fun format(score: Double) = if (score % 1 == 0.0) "${score.toInt()}" else "$score"

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
        return Frontmatter.set(withSleep, "평가", format(of(Frontmatter.parse(withSleep), screen)))
    }

    private fun minutes(value: String?) = Frontmatter.time(value)?.split(":")?.let { (h, m) -> h.toInt() * 60 + m.toInt() }
}
