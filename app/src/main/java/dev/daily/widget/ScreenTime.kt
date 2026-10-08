package dev.daily.widget

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId

object ScreenTime {
    fun granted(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    // ponytail: sums foreground time of one app at a time, launcher excluded. Close to Digital Wellbeing,
    // not identical (split-screen counted once, a session already open at midnight starts at its next event).
    fun minutes(ctx: Context, date: LocalDate): Long {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
        val launcher = ctx.packageManager
            .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName
        // Apps picked as productive in settings don't count, same as the launcher.
        val skip = Prefs.excluded(ctx) + listOfNotNull(launcher)

        val events = ctx.getSystemService(UsageStatsManager::class.java).queryEvents(start, end)
        val e = UsageEvents.Event()
        var pkg: String? = null
        var since = 0L
        var total = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (pkg != null) total += e.timeStamp - since
                    pkg = e.packageName.takeIf { it !in skip }
                    since = e.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> if (e.packageName == pkg) {
                    total += e.timeStamp - since
                    pkg = null
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    if (pkg != null) total += e.timeStamp - since
                    pkg = null
                }
            }
        }
        if (pkg != null) total += end - since
        return total / 60_000
    }

    // Samsung Digital Wellbeing's own screen-time dashboard.
    const val WELLBEING = "com.samsung.android.forest"

    fun format(minutes: Long) = "%02d:%02d".format(minutes / 60, minutes % 60)
}
