package dev.daily.widget

import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

// What the widget shows. Notes are kept as raw text (path -> content, null = no file) so an optimistic
// edit and the real file write run the exact same transform.
data class State(
    val today: LocalDate,
    val day: LocalDate, // the day the widget shows; today unless moved with the arrows
    val bedDate: LocalDate,
    val notes: Map<String, String?>,
    val screenMinutes: Long?,
    val problem: String?,
    val transparency: Int,
) {
    fun note(f: File) = notes[f.path]
    val props get() = Frontmatter.parse(note(Vault.daily(day)))
    val bed get() = Frontmatter.time(Frontmatter.parse(note(Vault.daily(bedDate)))["취침"])
    fun goals(p: Period) = note(Vault.period(p, today))?.let(Goals::parse)
    val backlog get() = Backlog.parse(note(Vault.backlog).orEmpty())
    val xp get() = Xp.week(today) { notes[it.path] }
}

// Optimistic pipeline modelled on life-dashboard: change the screen first, write in the background,
// skip disk refreshes while writes are pending, roll back with a toast if a write fails.
object Store {
    val state = MutableStateFlow<State?>(null)
    // Short-lived reward text on the 평가 cell ("+1") after a tap raises the score.
    val flash = MutableStateFlow<String?>(null)
    // XP the widget bar shows instead of the live total while FxActivity's overlay fills it in place.
    val xpHold = MutableStateFlow<Int?>(null)
    private var offset = 0L
    private var shiftedAt = 0L
    private val pending = AtomicInteger()
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val day = DateTimeFormatter.ofPattern("M월 d일(E)", Locale.KOREAN)

    fun dayLabel(date: LocalDate): String = date.format(day)

    // Lets the widget bar catch up once the overlay is gone; outlives the activity that held it.
    fun releaseXp(ctx: Context) {
        if (xpHold.value == null) return
        xpHold.value = null
        val app = ctx.applicationContext
        scope.launch { DailyWidget().updateAll(app) }
    }

    suspend fun shift(ctx: Context, delta: Long) {
        offset = if (delta == 0L) 0 else offset + delta
        shiftedAt = System.currentTimeMillis()
        state.value = withContext(Dispatchers.IO) { load(ctx) }
    }

    // Note a widget tap writes to: the shown day when browsing, else the clock rule (취침 after 18:00 -> tomorrow).
    fun dateFor(key: String, now: LocalDateTime): LocalDate =
        state.value?.takeIf { it.day != it.today }?.day ?: dev.daily.widget.dateFor(key, now)

    suspend fun refresh(ctx: Context) {
        if (pending.get() > 0 && state.value != null) return
        state.value = withContext(Dispatchers.IO) { load(ctx) }
    }

    // Applies `transform` to the cached note now, then writes it to disk. Returns the write job.
    fun commit(ctx: Context, file: File, template: String, name: String, success: String?, transform: (String) -> String): Job {
        val app = ctx.applicationContext
        pending.incrementAndGet()
        var before: String? = null
        val optimistic = state.value != null
        state.value?.let { s ->
            // Notes outside the cache (another week's goals from the sheet) start from disk, not the template.
            before = if (file.path in s.notes) s.note(file) else Vault.read(file)
            val base = before ?: Vault.template(template)
            state.value = s.copy(notes = s.notes + (file.path to transform(base)))
        }
        return scope.launch {
            DailyWidget().updateAll(app)
            val ok = lock.withLock {
                runCatching { Vault.write(file, template, transform) == Vault.read(file) }.getOrDefault(false)
            }
            pending.decrementAndGet()
            if (!ok) {
                if (optimistic) state.update { it?.copy(notes = it.notes + (file.path to before)) }
                toast(app, if (!Environment.isExternalStorageManager()) "저장 실패: 파일 접근 권한이 필요해요"
                    else "저장 실패: $name 업데이트 중 오류가 발생했습니다.")
            } else if (success != null) {
                toast(app, success)
            }
            refresh(app) // reconcile with disk once the queue is empty; also picks up edits made in Obsidian
            DailyWidget().updateAll(app)
        }
    }

    fun setProp(ctx: Context, key: String, value: String, date: LocalDate, announce: Boolean = false): Job {
        val msg = if (announce) "${describe(key, value)} · ${dayLabel(date)} 노트에 저장했어요" else null
        // Today's note has no 스크린타임 until tomorrow, so its score uses the live count meanwhile.
        val live = state.value?.takeIf { it.day == date }?.screenMinutes
        return commit(ctx, Vault.daily(date), "Daily", key, msg) { Score.apply(Frontmatter.set(it, key, value), live) }
    }

    fun editGoals(ctx: Context, p: Period, transform: (String) -> String, date: LocalDate = LocalDate.now()): Job =
        commit(ctx, Vault.period(p, date), p.template, p.title, null, transform)

    fun editBacklog(ctx: Context, transform: (String) -> String): Job =
        commit(ctx, Vault.backlog, "", "백로그", null, transform)

    private fun describe(key: String, value: String) = when {
        value.isEmpty() -> "$key 기록 지움"
        key == Score.BONUS -> if (value == "true") "좋은 하루 +0.5" else "+0.5 취소"
        else -> "$key $value"
    }

    private suspend fun toast(ctx: Context, msg: String) = withContext(Dispatchers.Main) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    private fun load(ctx: Context): State {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        // ponytail: browsing snaps back to today after 5 idle minutes so taps don't land on an old day unnoticed.
        if (System.currentTimeMillis() - shiftedAt > 5 * 60_000) offset = 0
        val day = today.plusDays(offset)
        // Browsing shows the day's own note; today shows tonight's 취침 after 18:00.
        val bedDate = if (offset == 0L) dev.daily.widget.dateFor("취침", now) else day
        val transparency = Prefs.transparency(ctx)
        val problem = when {
            !Environment.isExternalStorageManager() -> "파일 접근 권한이 필요해요 · 눌러서 설정"
            !Vault.root.exists() -> "vault를 찾지 못했어요 · 눌러서 확인"
            else -> null
        }
        if (problem != null) return State(today, day, bedDate, emptyMap(), null, problem, transparency)

        val usage = ScreenTime.granted(ctx)
        if (usage) {
            val yesterday = today.minusDays(1)
            if (Frontmatter.parse(Vault.read(Vault.daily(yesterday)))["스크린타임"].isNullOrBlank()) {
                val value = ScreenTime.format(ScreenTime.minutes(ctx, yesterday))
                Vault.write(Vault.daily(yesterday), "Daily") { Score.apply(Frontmatter.set(it, "스크린타임", value), null) }
            }
        }
        val files = listOf(Vault.daily(day), Vault.daily(bedDate), Vault.backlog) + Period.entries.map { Vault.period(it, today) } + Xp.files(today)
        return State(
            today = today,
            day = day,
            bedDate = bedDate,
            notes = files.associate { it.path to Vault.read(it) },
            screenMinutes = if (usage && !day.isAfter(today)) ScreenTime.minutes(ctx, day) else null,
            problem = null,
            transparency = transparency,
        )
    }
}

object Prefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // 0 = opaque, 100 = fully transparent background.
    fun transparency(ctx: Context) = prefs(ctx).getInt("transparency", 10)

    fun setTransparency(ctx: Context, value: Int) = prefs(ctx).edit().putInt("transparency", value).apply()

    // Packages left out of screen time.
    fun excluded(ctx: Context): Set<String> = prefs(ctx).getStringSet("excluded", emptySet())!!

    fun setExcluded(ctx: Context, value: Set<String>) = prefs(ctx).edit().putStringSet("excluded", value).apply()
}
