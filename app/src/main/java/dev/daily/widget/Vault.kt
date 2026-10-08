package dev.daily.widget

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object Vault {
    val root = File("/storage/emulated/0/Documents/Remote Vault/Remote Vault")
    const val NAME = "Remote Vault"
    const val DAYS = "Area/Daily/Days"

    private val nameFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE", Locale.ENGLISH)
    private val createFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE HH:mm:ss", Locale.ENGLISH)

    // Always the English weekday: mobile Obsidian sometimes made "2026-07-04 토.md" duplicates.
    fun name(date: LocalDate): String = date.format(nameFmt)

    fun daily(date: LocalDate) = File(root, "$DAYS/${name(date)}.md")

    fun period(p: Period, date: LocalDate) = File(root, "${p.folder}/${p.name(date)}.md")

    val backlog get() = File(root, "Area/Backlog.md")

    fun read(f: File): String? = if (f.exists()) f.readText() else null

    // Archive/Template/<name>.md with its only Templater tag (tp.file.creation_date) filled in;
    // Templater does not run on files created outside Obsidian. An empty name means an empty file.
    fun template(name: String, now: LocalDateTime = LocalDateTime.now()): String =
        if (name.isEmpty()) "" else read(File(root, "Archive/Template/$name.md"))
            ?.replace("\r\n", "\n")
            ?.replace(Regex("<%.*?%>")) { now.format(createFmt) }
            ?: "---\n---\n"

    // Read-modify-write; creates the note from its template when missing. Returns what was written.
    fun write(f: File, template: String, transform: (String) -> String): String {
        val out = transform(read(f) ?: template(template))
        f.parentFile?.mkdirs()
        f.writeText(out)
        return out
    }
}

object Frontmatter {
    // Index of the closing "---", or null when the text has no frontmatter.
    private fun end(lines: List<String>): Int? {
        if (lines.firstOrNull()?.trim() != "---") return null
        val i = lines.drop(1).indexOfFirst { it.trim() == "---" }
        return if (i < 0) null else i + 1
    }

    fun parse(text: String?): Map<String, String> {
        val lines = text?.lines() ?: return emptyMap()
        val end = end(lines) ?: return emptyMap()
        return lines.subList(1, end).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0 || line[0].isWhitespace()) null
            else line.substring(0, i).trim() to line.substring(i + 1).trim().trim('"', '\'')
        }.toMap()
    }

    fun set(text: String, key: String, value: String): String {
        val lines = text.lines().toMutableList()
        val end = end(lines) ?: run { lines.addAll(0, listOf("---", "---")); 1 }
        val line = if (value.isEmpty()) "$key:" else "$key: $value"
        val i = (1 until end).firstOrNull { lines[it].substringBefore(':').trim() == key }
        if (i != null) lines[i] = line else lines.add(end, line)
        return lines.joinToString("\n")
    }

    // "07:00", "\"07:00\"" or ISO "2026-02-03T07:00:00.000+09:00" (written by the month dashboard) -> "07:00".
    fun time(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val t = if ('T' in value) value.substringAfter('T') else value
        return Regex("""^(\d{1,2}):(\d{2})""").find(t)?.let { "%02d:%s".format(it.groupValues[1].toInt(), it.groupValues[2]) }
    }
}
