package dev.daily.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val BED_CUTOFF_HOUR = 18
private val HABITS = listOf("독서", "운동", "금주")
private const val BACKLOG_ROWS = 4

// 취침 belongs to the next day's note so the dashboard can compute 기상 - 취침 within one note.
fun dateFor(key: String, now: LocalDateTime): LocalDate =
    if (key == "취침" && now.hour >= BED_CUTOFF_HOUR) now.toLocalDate().plusDays(1) else now.toLocalDate()

class DailyWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Store.refresh(context)
        provideContent {
            val s by Store.state.collectAsState()
            val flash by Store.flash.collectAsState()
            s?.let { Content(it, flash) }
        }
    }
}

class DailyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = DailyWidget()
}

private val KEY = ActionParameters.Key<String>("key")
private val VALUE = ActionParameters.Key<String>("value")
private val PERIOD = ActionParameters.Key<String>("period")
private val INDEX = ActionParameters.Key<Int>("index")
private val CHECKED = ActionParameters.Key<Boolean>("checked")
private val DELTA = ActionParameters.Key<Long>("delta")

class RecordAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val key = parameters[KEY] ?: return
        val now = LocalDateTime.now()
        val value = parameters[VALUE] ?: now.format(DateTimeFormatter.ofPattern("HH:mm"))
        val score = { Store.state.value?.let { Score.of(it.props, it.screenMinutes) } ?: 0.0 }
        val before = score()
        // Times land in a note that depends on the clock (취침 after 18:00 -> tomorrow), so say where.
        val job = Store.setProp(context, key, value, Store.dateFor(key, now),
            announce = parameters[VALUE] == null || key == Score.BONUS)
        val gain = score() - before
        Haptic.tap(context, gain > 0)
        if (gain > 0) {
            // Glance can't animate, so the reward is a brief blue "+N" flash on the 평가 cell.
            Store.flash.value = "+${Score.format(gain)}"
            DailyWidget().updateAll(context)
            delay(1500)
            Store.flash.value = null
            DailyWidget().updateAll(context)
        }
        job.join()
    }
}

class ShiftAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Haptic.tap(context, false)
        Store.shift(context, parameters[DELTA] ?: return)
        DailyWidget().updateAll(context)
    }
}

class GoalToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val target = parameters[PERIOD] ?: return
        val i = parameters[INDEX] ?: return
        val checked = parameters[CHECKED] ?: return
        val period = Period.entries.firstOrNull { it.name == target }
        val list: Checklist = if (period == null) Backlog else Goals
        val toggle = { text: String -> list.parse(text).getOrNull(i)?.let { list.set(text, i, it.check(checked)) } ?: text }
        Haptic.tap(context, checked)
        (if (period == null) Store.editBacklog(context, toggle) else Store.editGoals(context, period, toggle)).join()
    }
}

// value == null records the current time.
private fun record(key: String, value: String? = null): Action = actionRunCallback<RecordAction>(
    if (value == null) actionParametersOf(KEY to key) else actionParametersOf(KEY to key, VALUE to value)
)

// target: a Period name or GoalSheetActivity.BACKLOG.
private fun toggleItem(target: String, i: Int, checked: Boolean): Action = actionRunCallback<GoalToggleAction>(
    actionParametersOf(PERIOD to target, INDEX to i, CHECKED to checked)
)

// Completing taps play their reward in FxActivity, which also records them. `cell` is the tapped cell's
// widget-space rect; with the launcher's screen bounds for it, FxActivity finds the XP bar on screen.
private fun fx(ctx: Context, geo: Geo, cell: FloatArray, kind: String, key: String = "", index: Int = -1): Action = actionStartActivity(
    Intent(ctx, FxActivity::class.java)
        .setData(Uri.parse("daily://fx/$kind/${Uri.encode(key)}/$index"))
        .putExtra(FxActivity.KIND, kind)
        .putExtra(FxActivity.KEY, key)
        .putExtra(FxActivity.INDEX, index)
        .putExtra(FxActivity.CELL, cell)
        .putExtra(FxActivity.BAR, geo.bar)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
)

private fun obsidian(path: String) = Intent(
    Intent.ACTION_VIEW, Uri.parse("obsidian://open?vault=${Uri.encode(Vault.NAME)}&file=${Uri.encode(path)}"),
).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun listSheet(ctx: Context, target: String, focusAdd: Boolean = false): Action = actionStartActivity(
    Intent(ctx, GoalSheetActivity::class.java)
        .setData(Uri.parse("daily://list/$target/$focusAdd"))
        .putExtra(GoalSheetActivity.PERIOD, target)
        .putExtra(GoalSheetActivity.FOCUS_ADD, focusAdd)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
)

private fun open(ctx: Context, cls: Class<*>, extra: String, value: String, date: LocalDate? = null): Action = actionStartActivity(
    Intent(ctx, cls)
        .setData(Uri.parse("daily://${cls.simpleName}/${Uri.encode(value)}/$date"))
        .putExtra(extra, value)
        .putExtra(SheetActivity.DATE, date?.toString())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
)

// Dark palette in the spirit of Toss: near-black base, grey surfaces, one blue accent.
private val BASE = Color(0xFF17171C)
private val SURFACE = Color(0xFF2C2C35)
private val BLUE = ColorProvider(Color(0xFF3182F6))
private val BOX = Color(0xFF4E4E57)
private val TEXT = ColorProvider(Color(0xFFFFFFFF))
private val SUB = ColorProvider(Color(0xFF9E9EA4))
private val FAINT = ColorProvider(Color(0xFF6B6B73))
private val GOLD = ColorProvider(Color(0xFFFFC94D))
private val TRACK = ColorProvider(Color(0x24FFFFFF))

private enum class Tone { VALUE, CTA, EMPTY }

// One UI lays a widget out larger than it shows it and scales it down (hsResizeRatio ~0.71 for 5x3),
// so plain dp/sp come out small. Sizes here are written for the 362dp-wide mockup and scaled to the real
// width, which keeps the proportions tuned on the comparison page. A shorter-than-mockup widget scales
// by height instead so everything still fits; extra height goes to the goals box.
private const val MOCK_WIDTH = 362f
private const val MOCK_MIN_HEIGHT = 260f

// Widget-space rects (x, y, w, h in dp) mirroring Scaled's layout, for the cells that open FxActivity
// and for the XP bar. Keep in step with the layout below.
private class Geo(private val w: Float, h: Float) {
    val s = minOf(w / MOCK_WIDTH, h / MOCK_MIN_HEIGHT)
    private val bottomY = h - 118 * s // padding 6 + bar 4 + gap 5 + bottom row 103
    val bar = floatArrayOf(10 * s, h - 10 * s, w - 20 * s, 4 * s)
    fun value(i: Int): FloatArray { val cw = (w - 28 * s) / 5; return floatArrayOf(6 * s + i * (cw + 4 * s), 6 * s, cw, 53 * s) }
    fun habit(i: Int): FloatArray { val hh = 95 * s / 3; return floatArrayOf(6 * s, bottomY + i * (hh + 4 * s), 50 * s, hh) }
    fun goal(k: Int): FloatArray { val rh = (bottomY - 71 * s) / 3; return floatArrayOf(50 * s, 65 * s + k * rh, 30 * s, rh) }
    fun task(row: Int): FloatArray { val rh = 101 * s / 4; return floatArrayOf(60 * s, bottomY + s + row * rh, 34 * s, rh) }
}

private val LocalGeo = staticCompositionLocalOf { Geo(MOCK_WIDTH, MOCK_MIN_HEIGHT) }
private val Int.d: Dp @Composable get() = (this * LocalGeo.current.s).dp
private val Int.s: TextUnit @Composable get() = (this * LocalGeo.current.s).sp

@Composable
private fun Content(s: State, flash: String?) {
    val size = LocalSize.current
    CompositionLocalProvider(LocalGeo provides Geo(size.width.value, size.height.value)) { Scaled(s, flash) }
}

@Composable
private fun Scaled(s: State, flash: String?) {
    val ctx = LocalContext.current
    val opacity = 1f - s.transparency / 100f
    val base = ColorProvider(BASE.copy(alpha = opacity))
    // Fully transparent means no fill at all, like One UI's own widgets; blue fills stay for done states.
    val surface = ColorProvider(SURFACE.copy(alpha = opacity))
    // Dark grey boxes vanish on the wallpaper once their surface is gone; lighten them toward a white veil.
    val box = ColorProvider(lerp(BOX, Color.White.copy(alpha = 0.35f), 1f - opacity))

    Column(GlanceModifier.fillMaxSize().background(base).cornerRadius(20.d).padding(6.d)) {
        if (s.problem != null) {
            Cell("설정", s.problem, Tone.VALUE, actionStartActivity<MainActivity>(), surface, GlanceModifier.fillMaxSize())
            return@Column
        }
        // 5x3 (~271dp), layout P2: values 53 | goals (rest, ~86) | habits + backlog + add 103 | XP bar.
        Row(GlanceModifier.fillMaxWidth().height(53.d)) { Values(ctx, s, flash, surface) }
        Spacer(GlanceModifier.height(4.d))
        Bordered(surface, GlanceModifier.fillMaxWidth().defaultWeight()) {
            Column(GlanceModifier.fillMaxSize().padding(horizontal = 4.d, vertical = 2.d)) {
                Period.entries.forEachIndexed { k, p -> GoalRow(ctx, k, p, s.goals(p), s.today, box, GlanceModifier.defaultWeight()) }
            }
        }
        Spacer(GlanceModifier.height(4.d))
        Row(GlanceModifier.fillMaxWidth().height(103.d)) {
            // Two-letter habit labels need little width; the backlog takes the rest.
            Column(GlanceModifier.width(50.d).fillMaxHeight()) { Habits(ctx, s, surface) }
            Spacer(GlanceModifier.width(4.d))
            BacklogList(ctx, s.backlog, box, surface, GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(4.d))
            Column(GlanceModifier.width(38.d).fillMaxHeight()) {
                AddCell(ctx, s.backlog.count { !it.checked && it.text.isNotEmpty() }, surface)
                Spacer(GlanceModifier.height(4.d))
                DayNav(surface)
            }
        }
        // This week's XP; the reward overlay fills this same bar in place (Geo.bar).
        Spacer(GlanceModifier.height(5.d))
        Box(GlanceModifier.fillMaxWidth().height(4.d).padding(horizontal = 4.d)) {
            LinearProgressIndicator(minOf(1f, s.xp / Xp.TARGET.toFloat()),
                GlanceModifier.fillMaxSize().cornerRadius(2.dp), GOLD, TRACK)
        }
    }
}

@Composable
private fun ColumnScope.Habits(ctx: Context, s: State, surface: ColorProvider) {
    val props = s.props
    // Fill colour alone says done; no value text, so the label gets the space.
    HABITS.forEachIndexed { i, key ->
        if (i > 0) Spacer(GlanceModifier.height(4.d))
        val done = props[key] == "true"
        val geo = LocalGeo.current
        Toggle(key, done, if (done) record(key, "false") else fx(ctx, geo, geo.habit(i), FxActivity.HABIT, key), surface,
            GlanceModifier.fillMaxWidth().defaultWeight())
    }
}

@Composable
private fun RowScope.Values(ctx: Context, s: State, flash: String?, surface: ColorProvider) {
    val props = s.props
    val geo = LocalGeo.current
    // Empty time cell: one tap records now, so it says "기록". Recorded: open the sheet so a stray tap never overwrites.
    // Browsing another day: "now" means nothing there, so always the sheet, and the first caption names the day.
    val browsing = s.day != s.today
    val sheetDate = if (browsing) s.day else null
    // 수면 = last night's 취침 to this morning's 기상; fills in the moment 기상 is recorded. Browsing names the day here.
    val sleep = Score.sleep(props)
    Cell("수면", sleep?.let { "${it / 60}:%02d".format(it % 60) } ?: "—",
        if (sleep != null) Tone.VALUE else Tone.EMPTY, null, surface, GlanceModifier.defaultWeight())
    Spacer(GlanceModifier.width(4.d))
    val wake = Frontmatter.time(props["기상"])
    Cell(if (browsing) "${s.day.monthValue}/${s.day.dayOfMonth} 기상" else "오늘 기상", wake ?: "기록",
        if (wake != null) Tone.VALUE else Tone.CTA,
        if (wake == null && !browsing) fx(ctx, geo, geo.value(1), FxActivity.WAKE) else open(ctx, SheetActivity::class.java, SheetActivity.KEY, "기상", sheetDate),
        surface, GlanceModifier.defaultWeight(), captionColor = if (browsing) BLUE else SUB)
    Spacer(GlanceModifier.width(4.d))
    val bed = s.bed
    Cell("취침", bed ?: "기록", if (bed != null) Tone.VALUE else Tone.CTA,
        if (bed == null && !browsing) fx(ctx, geo, geo.value(2), FxActivity.BED) else open(ctx, SheetActivity::class.java, SheetActivity.KEY, "취침", sheetDate),
        surface, GlanceModifier.defaultWeight())
    Spacer(GlanceModifier.width(4.d))
    // Opens Digital Wellbeing's dashboard; the note is the fallback where it isn't installed.
    val wellbeing = ctx.packageManager.getLaunchIntentForPackage(ScreenTime.WELLBEING) ?: obsidian("${Vault.DAYS}/${Vault.name(s.day)}")
    val screen = s.screenMinutes
    Cell("스크린", screen?.let { "${it / 60}:%02d".format(it % 60) } ?: "권한", if (screen != null) Tone.VALUE else Tone.CTA,
        if (screen != null) actionStartActivity(wellbeing) else actionStartActivity<MainActivity>(), surface,
        GlanceModifier.defaultWeight())
    Spacer(GlanceModifier.width(4.d))
    // Computed live (today's screen time so far); tapping toggles the manual +0.5, shown in the caption.
    val bonus = props[Score.BONUS] == "true"
    Cell(flash ?: if (bonus) "평가 +½" else "평가", Score.format(Score.of(props, screen)), Tone.VALUE,
        record(Score.BONUS, if (bonus) "false" else "true"), surface, GlanceModifier.defaultWeight(),
        captionColor = if (flash != null) TEXT else if (bonus) BLUE else SUB, lit = flash != null)
}

// One period per row, like life-dashboard's collapsed mobile preview: the first non-blank goal with a live checkbox.
@Composable
private fun GoalRow(ctx: Context, k: Int, p: Period, goals: List<Goal>?, today: LocalDate, box: ColorProvider, modifier: GlanceModifier) {
    val sheet = listSheet(ctx, p.name)
    // Same split as the backlog: box toggles, text edits. The label opens the note itself once it exists.
    val label = if (goals != null) actionStartActivity(obsidian("${p.folder}/${p.name(today)}")) else sheet
    val i = goals?.indexOfFirst { it.text.isNotEmpty() } ?: -1
    val goal = goals?.getOrNull(i)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(GlanceModifier.fillMaxHeight().clickable(label), verticalAlignment = Alignment.CenterVertically) { Label(p) }
        if (goal == null) {
            Row(GlanceModifier.fillMaxSize().clickable(sheet), verticalAlignment = Alignment.CenterVertically) {
                Text("목표를 추가해 보세요", maxLines = 1, style = TextStyle(color = FAINT, fontSize = 12.s))
            }
            return@Row
        }
        Box(GlanceModifier.fillMaxHeight().padding(start = 2.d, end = 10.d)
            .clickable(if (goal.checked) toggleItem(p.name, i, false) else LocalGeo.current.let { fx(ctx, it, it.goal(k), FxActivity.GOAL, p.name, i) }), contentAlignment = Alignment.Center) {
            Box(GlanceModifier.size(18.d).background(if (goal.checked) BLUE else box).cornerRadius(5.d)) {}
        }
        val real = goals.filter { it.text.isNotEmpty() }
        Row(GlanceModifier.defaultWeight().fillMaxHeight().clickable(sheet), verticalAlignment = Alignment.CenterVertically) {
            Text(goal.text, maxLines = 1, modifier = GlanceModifier.defaultWeight(), style = TextStyle(
                color = if (goal.checked) SUB else TEXT, fontSize = 12.s,
                textDecoration = if (goal.checked) TextDecoration.LineThrough else TextDecoration.None))
            Text("${real.count { it.checked }}/${real.size}", maxLines = 1,
                modifier = GlanceModifier.padding(start = 8.d, end = 8.d),
                style = TextStyle(color = SUB, fontSize = 12.s))
        }
    }
}

@Composable
private fun Label(p: Period) {
    Text(p.label, maxLines = 1, modifier = GlanceModifier.width(40.d).padding(start = 8.d),
        style = TextStyle(color = SUB, fontSize = 12.s))
}

// Top undone backlog items; checking one completes it and the next slides up. Text opens the full list.
@Composable
private fun BacklogList(ctx: Context, items: List<Goal>, box: ColorProvider, surface: ColorProvider, modifier: GlanceModifier) {
    val open = listSheet(ctx, GoalSheetActivity.BACKLOG)
    val shown = items.withIndex().filter { !it.value.checked && it.value.text.isNotEmpty() }.take(BACKLOG_ROWS)
    Bordered(surface, modifier.fillMaxHeight()) {
        Column(GlanceModifier.fillMaxSize().padding(vertical = 1.d)) {
            if (shown.isEmpty()) {
                Row(GlanceModifier.fillMaxSize().clickable(listSheet(ctx, GoalSheetActivity.BACKLOG, focusAdd = true)),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("할 일을 추가해 보세요", maxLines = 1, modifier = GlanceModifier.padding(start = 12.d),
                        style = TextStyle(color = FAINT, fontSize = 12.s))
                }
                return@Column
            }
            val geo = LocalGeo.current
            shown.forEachIndexed { row, (i, item) ->
                Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                    Box(GlanceModifier.fillMaxHeight().padding(start = 10.d, end = 8.d)
                        .clickable(fx(ctx, geo, geo.task(row), FxActivity.TASK, index = i)), contentAlignment = Alignment.Center) {
                        Box(GlanceModifier.size(16.d).background(box).cornerRadius(8.d)) {}
                    }
                    Text(item.text, maxLines = 1, modifier = GlanceModifier.defaultWeight().clickable(open),
                        style = TextStyle(color = TEXT, fontSize = 12.s))
                }
            }
            // Keep row height fixed when fewer than BACKLOG_ROWS items remain.
            repeat(BACKLOG_ROWS - shown.size) { Spacer(GlanceModifier.defaultWeight()) }
        }
    }
}

@Composable
private fun AddCell(ctx: Context, remaining: Int, surface: ColorProvider) {
    Bordered(surface, GlanceModifier.fillMaxWidth().height(66.d), listSheet(ctx, GoalSheetActivity.BACKLOG, focusAdd = true)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
            Text("+", style = TextStyle(color = BLUE, fontSize = 19.s, fontWeight = FontWeight.Bold))
            Text("$remaining", maxLines = 1, style = TextStyle(color = SUB, fontSize = 12.s))
        }
    }
}

// Previous / next day as the two halves of one small cell; the shown day is named on the 기상 caption.
@Composable
private fun ColumnScope.DayNav(surface: ColorProvider) {
    Bordered(surface, GlanceModifier.fillMaxWidth().defaultWeight()) {
        Row(GlanceModifier.fillMaxSize()) {
            listOf("‹" to -1L, "›" to 1L).forEach { (arrow, delta) ->
                Box(GlanceModifier.defaultWeight().fillMaxHeight()
                    .clickable(actionRunCallback<ShiftAction>(actionParametersOf(DELTA to delta))),
                    contentAlignment = Alignment.Center) {
                    Text(arrow, style = TextStyle(color = SUB, fontSize = 15.s, fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

// Glance has no border modifier: a hollow stroke drawable (so a transparent `bg` stays see-through, One UI-style)
// laid over the fill. Fill, ring and the press ripple share this one box and its 14dp outline, so their
// corners always agree. A filled (done) cell drops the ring; the fill alone carries state.
@Composable
private fun Bordered(bg: ColorProvider, modifier: GlanceModifier, action: Action? = null, ring: Boolean = true,
                     content: @Composable () -> Unit) {
    val box = modifier.background(bg).cornerRadius(CELL_RADIUS)
    Box(if (action != null) box.clickable(action) else box) {
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
        if (ring) Box(GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.cell_ring))) {}
    }
}

// Unscaled on purpose: must equal cell_ring.xml's radius.
private val CELL_RADIUS = 14.dp

@Composable
private fun Toggle(label: String, done: Boolean, action: Action, surface: ColorProvider,
                   modifier: GlanceModifier) {
    Box(modifier) {
        Bordered(if (done) BLUE else surface, GlanceModifier.fillMaxSize(), action, ring = !done) {
            Text(label, maxLines = 1, style = TextStyle(
                color = if (done) TEXT else SUB, fontSize = 15.s, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
        }
    }
}

@Composable
private fun Cell(caption: String, value: String, tone: Tone, action: Action?, surface: ColorProvider,
                 modifier: GlanceModifier, captionColor: ColorProvider = SUB, lit: Boolean = false) {
    val valueStyle = when (tone) {
        Tone.VALUE -> TextStyle(color = TEXT, fontSize = 14.s, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Tone.CTA -> TextStyle(color = BLUE, fontSize = 14.s, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Tone.EMPTY -> TextStyle(color = FAINT, fontSize = 14.s, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
    Box(modifier.fillMaxHeight()) {
        Bordered(if (lit) BLUE else surface, GlanceModifier.fillMaxSize(), action, ring = !lit) {
            Column(verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(caption, maxLines = 1, style = TextStyle(color = captionColor, fontSize = 13.s, textAlign = TextAlign.Center))
                Text(value, maxLines = 1, style = valueStyle)
            }
        }
    }
}
