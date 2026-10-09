package com.example.timetableapp.widget

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.timetableapp.R
import com.example.timetableapp.data.model.Course
import com.example.timetableapp.data.model.Session
import com.example.timetableapp.ui.screen.isNowAt

class WeekWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(val context: Context) : RemoteViewsFactory {
        private sealed interface Row
        private data class Day(val label: String, val isToday: Boolean) : Row
        private data class Lesson(val session: Session, val course: Course?, val isNow: Boolean, val upcomingMin: Int?) : Row
        private object EmptyDay : Row
        private var rows: List<Row> = emptyList()

        override fun onCreate() {}
        override fun onDestroy() {}
        override fun getCount() = rows.size
        override fun getViewTypeCount() = 3
        override fun getItemId(p: Int) = p.toLong()
        override fun hasStableIds() = true
        override fun getLoadingView() = null

        override fun onDataSetChanged() {
            val (courses, sessions) = TimetableWidgets.load(context)
            val byId = courses.associateBy { it.id }
            val out = mutableListOf<Row>()
            val today = TimetableWidgets.todayDow()
            val now = TimetableWidgets.nowMinutes()
            val lead = com.example.timetableapp.notifications.ReminderScheduler.loadLeadMin(context)
            for (day in 1..7) {
                out += Day(TimetableWidgets.DAYS[day], day == today)
                val list = sessions.filter { it.dayOfWeek == day }
                    .sortedWith(compareBy({ it.startHour }, { it.startMinute }))
                if (list.isEmpty()) out += EmptyDay
                else list.forEach {
                    val live = isNowAt(it, today, now)
                    val diff = it.startHour * 60 + it.startMinute - now
                    val up = if (!live && it.dayOfWeek == today && diff in 1..(lead * 60)) (diff + 59) / 60 else null
                    out += Lesson(it, byId[it.courseId], live, up)
                }
            }
            rows = out
        }

        override fun getViewAt(p: Int): RemoteViews? {
            if (p < 0 || p >= rows.size) return null
            return when (val r = rows[p]) {
                is Day -> RemoteViews(context.packageName, R.layout.widget_week_day).apply {
                    // ponytail: today wears the app's white day-chip; others stay grey on grey
                    setTextViewText(R.id.week_day_label, r.label)
                    if (r.isToday) {
                        setTextColor(R.id.week_day_label, 0xFF000000.toInt())
                        setInt(R.id.week_day_label, "setBackgroundColor", 0xFFFFFFFF.toInt())
                    } else {
                        setTextColor(R.id.week_day_label, 0xFF9E9E9E.toInt())
                        setInt(R.id.week_day_label, "setBackgroundColor", 0x00000000)
                    }
                }
                is EmptyDay -> RemoteViews(context.packageName, R.layout.widget_week_ghost).apply {
                    setOnClickFillInIntent(R.id.week_ghost_root, Intent())
                }
                is Lesson -> RemoteViews(context.packageName, R.layout.widget_week_session).apply {
                    // ponytail: only the live class is white; the rest sit muted grey
                    val live = r.isNow
                    setInt(
                        R.id.week_card_bar, "setBackgroundColor",
                        if (live) r.course?.color ?: 0xFF1A73E8.toInt() else 0xFF616161.toInt()
                    )
                    val fg = if (live) 0xFFFFFFFF.toInt() else 0xFF9E9E9E.toInt()
                    setTextColor(R.id.week_card_name, fg)
                    setTextColor(R.id.week_card_when, fg)
                    if (r.isNow) {
                        setViewVisibility(R.id.week_card_status, View.VISIBLE)
                        setTextViewText(R.id.week_card_status, "\u2022 Started")
                        setTextColor(R.id.week_card_status, 0xFF4CAF50.toInt())
                    } else if (r.upcomingMin != null) {
                        setViewVisibility(R.id.week_card_status, View.VISIBLE)
                        setTextViewText(R.id.week_card_status, "\u2022 Upcoming " + r.upcomingMin + " min")
                        setTextColor(R.id.week_card_status, 0xFFFFFFFF.toInt())
                    } else {
                        setViewVisibility(R.id.week_card_status, View.GONE)
                    }
                    setTextViewText(
                        R.id.week_card_name,
                        r.course?.name?.takeIf { it.isNotBlank() } ?: r.course?.code ?: "Class"
                    )
                    setTextViewText(
                        R.id.week_card_when,
                        TimetableWidgets.timeRange(r.session) +
                            (if (r.session.room.isNotBlank()) "  |  " + r.session.room else "")
                    )
                    setOnClickFillInIntent(R.id.week_session_root, Intent())
                }
            }
        }
    }
}
