package io.github.arturaragao.bancocbr

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.util.TypedValue
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

open class RefreshingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refresh(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        refresh(context)
    }

    private fun refresh(context: Context) {
        val pending = goAsync()
        RefreshCoordinator.enqueue(context, finished = { pending.finish() })
    }
}

class AddTaskWidget : RefreshingWidget()
class TasksWidget : RefreshingWidget()
class CalendarWidget : RefreshingWidget()

object WidgetRenderer {
    private val locale = Locale.forLanguageTag("pt-BR")
    private const val LIGHT = 0xfff1f5f9.toInt()
    private const val MUTED = 0xffcbd5e1.toInt()
    private const val BLUE = 0xff60a5fa.toInt()
    private const val YELLOW = 0xfffbbf24.toInt()
    private const val GREEN = 0xff4ade80.toInt()
    private const val RED = 0xfff87171.toInt()

    fun updateAll(context: Context, summary: Summary?) {
        val manager = AppWidgetManager.getInstance(context)
        fun ids(provider: Class<*>) = manager.getAppWidgetIds(ComponentName(context, provider))
        ids(AddTaskWidget::class.java).forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_add)
            views.setViewVisibility(R.id.add_hint, if (summary == null) View.VISIBLE else View.GONE)
            views.setOnClickPendingIntent(R.id.widget_root, AppIntents.quickAdd(context))
            manager.updateAppWidget(id, views)
        }
        val taskIds = ids(TasksWidget::class.java)
        taskIds.forEach { id ->
            val today = LocalDate.now()
            val day = summary?.days?.get(today)
            val views = RemoteViews(context.packageName, R.layout.widget_tasks)
            views.setTextViewText(R.id.widget_title, "Hoje · ${today.format(DateTimeFormatter.ofPattern("dd/MM", locale))}")
            val subtitle = if (summary == null) context.getString(R.string.no_data) else buildList {
                if (!day?.night.isNullOrEmpty()) add("noite ${day?.night}")
                addAll(day?.allEvents.orEmpty())
                if (summary.today != today) add("abra o app para atualizar tarefas de hoje")
            }.joinToString(" · ")
            views.setTextViewText(R.id.widget_subtitle, subtitle)
            views.setViewVisibility(R.id.widget_subtitle, if (subtitle.isEmpty()) View.GONE else View.VISIBLE)
            views.setOnClickPendingIntent(R.id.widget_root, AppIntents.pending(context))
            views.setOnClickPendingIntent(R.id.widget_title, AppIntents.pending(context))
            views.setOnClickPendingIntent(R.id.widget_subtitle, AppIntents.pending(context))
            val adapter = Intent(context, TasksRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse("bancocbr://widget/tarefas/$id")
            }
            views.setRemoteAdapter(R.id.task_list, adapter)
            views.setEmptyView(R.id.task_list, R.id.task_empty)
            val template = PendingIntent.getActivity(context, id, AppIntents.open(context),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            views.setPendingIntentTemplate(R.id.task_list, template)
            manager.updateAppWidget(id, views)
        }
        if (taskIds.isNotEmpty()) manager.notifyAppWidgetViewDataChanged(taskIds, R.id.task_list)
        ids(CalendarWidget::class.java).forEach { id ->
            manager.updateAppWidget(id, calendar(context, summary, manager.getAppWidgetOptions(id)))
        }
    }

    private fun calendar(context: Context, summary: Summary?, options: Bundle): RemoteViews {
        val today = LocalDate.now()
        val month = YearMonth.from(today)
        val views = RemoteViews(context.packageName, R.layout.widget_calendar)
        views.setTextViewText(R.id.widget_title, month.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)))
        views.setTextViewText(R.id.calendar_hint, if (summary == null) context.getString(R.string.no_data) else "BR / HSI · ● evento · questões feito/meta")
        views.setOnClickPendingIntent(R.id.widget_root, AppIntents.pending(context))
        views.removeAllViews(R.id.weekdays)
        listOf("seg", "ter", "qua", "qui", "sex", "sáb", "dom").forEach { weekday ->
            val cell = RemoteViews(context.packageName, R.layout.widget_calendar_cell)
            cell.setTextViewText(R.id.cell_text, weekday)
            cell.setTextColor(R.id.cell_text, MUTED)
            views.addView(R.id.weekdays, cell)
        }
        views.removeAllViews(R.id.calendar_grid)
        val offset = month.atDay(1).dayOfWeek.value - 1
        val weeks = (offset + month.lengthOfMonth() + 6) / 7
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 220).coerceAtLeast(180)
        val fontScale = context.resources.configuration.fontScale
        val cellSize = ((height - 66f) / weeks / 3.5f / fontScale).coerceIn(6f, 12f)
        repeat(weeks) { week ->
            val row = RemoteViews(context.packageName, R.layout.widget_calendar_row)
            repeat(7) { column ->
                val number = week * 7 + column - offset + 1
                val cell = RemoteViews(context.packageName, R.layout.widget_calendar_cell)
                cell.setTextViewTextSize(R.id.cell_text, TypedValue.COMPLEX_UNIT_SP, cellSize)
                if (number in 1..month.lengthOfMonth()) {
                    val date = month.atDay(number)
                    cell.setTextViewText(R.id.cell_text, calendarText(number, summary?.days?.get(date), date == today))
                    if (date == today) cell.setInt(R.id.cell_text, "setBackgroundResource", R.drawable.today_background)
                    cell.setOnClickPendingIntent(R.id.cell_text, AppIntents.pending(context))
                } else {
                    cell.setTextViewText(R.id.cell_text, "")
                }
                row.addView(R.id.calendar_row, cell)
            }
            views.addView(R.id.calendar_grid, row)
        }
        return views
    }

    private fun calendarText(number: Int, day: DayInfo?, today: Boolean): CharSequence {
        val text = SpannableStringBuilder()
        append(text, number.toString(), if (today) BLUE else LIGHT, today)
        if (day != null) {
            text.append('\n')
            if (day.night.isNotEmpty()) append(text, day.night, if (day.night == "BR") GREEN else RED, true)
            if (day.allEvents.isNotEmpty()) append(text, " ●", YELLOW)
            val start = text.length
            text.append("\n${day.completed}/${day.goal}")
            text.setSpan(RelativeSizeSpan(0.8f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    fun taskText(task: TaskItem): CharSequence {
        val color = when (task.type) {
            "estudo" -> BLUE
            "ferramenta" -> YELLOW
            else -> GREEN
        }
        val text = SpannableStringBuilder()
        append(text, "● ", color)
        append(text, task.text, if (task.urgent) color else LIGHT, task.urgent)
        return text
    }

    fun sectionText(title: String): CharSequence = SpannableString(title).apply {
        setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(MUTED), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun append(builder: SpannableStringBuilder, value: String, color: Int, bold: Boolean = false) {
        val start = builder.length
        builder.append(value)
        if (start == builder.length) return
        builder.setSpan(ForegroundColorSpan(color), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

class TasksRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = TaskFactory(applicationContext)
}

private class TaskFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var rows: List<CharSequence> = emptyList()
    override fun onCreate() { load() }
    override fun onDataSetChanged() { load() }
    override fun onDestroy() { rows = emptyList() }

    private fun load() {
        val summary = SummaryStore.read(context)
        val pending = PendingStore.tasks(context).map { TaskItem(it.id, it.text, "tarefas", false, false) }
        rows = if (summary == null) {
            if (pending.isEmpty()) listOf(context.getString(R.string.no_data))
            else listOf(WidgetRenderer.sectionText("Gerais (sem data)")) + pending.map { WidgetRenderer.taskText(it) }
        } else buildList {
            add(WidgetRenderer.sectionText("Hoje"))
            val today = LocalDate.now()
            val tasks = summary.tasksOn(today).filterNot { it.done }
            if (summary.today != today) add("abra o app para atualizar tarefas de hoje")
            else if (tasks.isEmpty()) add(context.getString(R.string.no_tasks))
            tasks.forEach { add(WidgetRenderer.taskText(it)) }
            add(WidgetRenderer.sectionText("Gerais (sem data)"))
            val general = summary.generalTasks.filterNot { it.done } + pending
            if (general.isEmpty()) add(context.getString(R.string.no_tasks))
            general.forEach { add(WidgetRenderer.taskText(it)) }
        }
    }

    override fun getCount(): Int = rows.size
    override fun getViewAt(position: Int): RemoteViews = RemoteViews(context.packageName, R.layout.widget_task_row).apply {
        setTextViewText(R.id.row_text, rows.getOrNull(position) ?: "")
        setOnClickFillInIntent(R.id.row_text, Intent())
    }
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
}
