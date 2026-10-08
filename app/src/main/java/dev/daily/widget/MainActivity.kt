package dev.daily.widget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.glance.appwidget.updateAll
import dev.daily.widget.Ui.button
import dev.daily.widget.Ui.dp
import dev.daily.widget.Ui.round
import dev.daily.widget.Ui.text
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

// Settings: the two permissions, screen-time exclusions and background transparency. Also the widget's configure screen.
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var files: TextView
    private lateinit var usage: TextView
    private lateinit var excluded: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opened as the widget's configure activity: accept immediately so adding the widget never fails.
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            .takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }
            ?.let { setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, it)) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(dp(24), dp(24) + bars.top, dp(24), dp(24) + bars.bottom)
                insets
            }
        }
        root.addView(text("위젯 설정", 24f, bold = true).apply { setPadding(0, dp(16), 0, dp(32)) })

        root.addView(section("권한"))
        files = text("", 14f, Ui.SUB)
        usage = text("", 14f, Ui.SUB)
        root.addView(row("파일 접근", "노트를 읽고 기록해요", files) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(row("사용 정보 접근", "스크린타임을 계산해요", usage) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })

        root.addView(section("스크린타임"))
        excluded = text("", 14f, Ui.SUB)
        root.addView(row("제외할 앱", "공부·업무 앱은 빼고 계산해요", excluded) { pickExcluded() })

        root.addView(section("배경"))
        val value = text("", 16f, bold = true)
        val seek = SeekBar(this).apply {
            max = 100
            progress = Prefs.transparency(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    value.text = "$p%"
                    Prefs.setTransparency(this@MainActivity, p)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) = refreshWidget()
            })
        }
        value.text = "${seek.progress}%"
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Ui.SURFACE, 16)
            setPadding(dp(20), dp(18), dp(20), dp(12))
            addView(LinearLayout(context).apply {
                addView(text("투명도", 16f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(value)
            })
            addView(seek, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(text("손을 떼면 위젯에 바로 반영돼요", 13f, Ui.SUB).apply { setPadding(0, dp(8), 0, 0) })
        })

        root.addView(LinearLayout(this), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(button("완료", Ui.BLUE) { finish() })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        files.text = if (Environment.isExternalStorageManager()) "허용됨" else "허용 필요"
        usage.text = if (ScreenTime.granted(this)) "허용됨" else "허용 필요"
        excluded.text = "${Prefs.excluded(this).size}개"
        refreshWidget()
    }

    private fun refreshWidget() {
        scope.launch { Store.refresh(this@MainActivity); DailyWidget().updateAll(this@MainActivity) }
    }

    // Launchable apps, already-excluded ones first so they're easy to review.
    private fun pickExcluded() {
        val current = Prefs.excluded(this)
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .distinctBy { it.first }
            .filter { it.first != packageName }
            .sortedWith(compareBy({ it.first !in current }, { it.second }))
        val checked = BooleanArray(apps.size) { apps[it].first in current }
        AlertDialog.Builder(this)
            .setTitle("스크린타임에서 제외할 앱")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                Prefs.setExcluded(this, apps.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet())
                excluded.text = "${Prefs.excluded(this).size}개"
                refreshWidget()
            }
            .show()
    }

    private fun section(title: String) = text(title, 14f, Ui.SUB, bold = true).apply { setPadding(dp(4), dp(8), 0, dp(10)) }

    private fun row(title: String, desc: String, status: TextView, onClick: () -> Unit) = LinearLayout(this).apply {
        background = round(Ui.SURFACE, 16)
        setPadding(dp(20), dp(16), dp(20), dp(16))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 16f, bold = true))
            addView(text(desc, 13f, Ui.SUB).apply { setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(status)
        gravity = android.view.Gravity.CENTER_VERTICAL
        setOnClickListener { onClick() }
    }
}
