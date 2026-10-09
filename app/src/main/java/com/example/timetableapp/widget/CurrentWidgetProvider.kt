package com.example.timetableapp.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import android.view.View
import com.example.timetableapp.R
import com.example.timetableapp.data.model.Course
import com.example.timetableapp.data.model.Session
import com.example.timetableapp.ui.screen.isNowAt

class CurrentWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        Thread {
            try {
                val (courses, sessions) = TimetableWidgets.load(context)
                val byId = courses.associateBy { it.id }
                ids.forEach { mgr.updateAppWidget(it, views(context, byId, sessions)) }
            } catch (_: Exception) {
            }
        }.start()
    }

    companion object {
        // ponytail: first live class wins; empty timetable or gap hours read "No class"

        // ponytail: live reads Started green, lead-window reads Upcoming N min, else No class
        fun views(context: Context, byId: Map<Int, Course>, sessions: List<Session>): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_current)
            val today = TimetableWidgets.todayDow()
            val now = TimetableWidgets.nowMinutes()
            val byStart = compareBy<Session>({ it.startHour }, { it.startMinute })
            val cur = sessions.sortedWith(byStart).firstOrNull { isNowAt(it, today, now) }
            val lead = com.example.timetableapp.notifications.ReminderScheduler.loadLeadMin(context)
            val next = if (cur == null) TimetableWidgets.nextUpcoming(sessions, today, now, lead) else null
            val shown = cur ?: next
            val course = shown?.let { byId[it.courseId] }
            v.setInt(R.id.cur_root, "setBackgroundColor", 0xFF212121.toInt())
            if (shown == null) {
                v.setInt(R.id.cur_bar, "setBackgroundColor", 0xFF938F99.toInt())
                v.setTextViewText(R.id.cur_name, "No class")
                v.setTextViewText(R.id.cur_when, "--:--")
                v.setViewVisibility(R.id.cur_status, View.GONE)
            } else {
                val live = cur != null
                v.setInt(R.id.cur_bar, "setBackgroundColor", course?.color ?: 0xFF1A73E8.toInt())
                v.setTextViewText(
                    R.id.cur_name,
                    course?.name?.takeIf { it.isNotBlank() } ?: course?.code ?: "Class"
                )
                v.setTextViewText(
                    R.id.cur_when,
                    TimetableWidgets.timeRange(shown) +
                        (if (shown.room.isNotBlank()) "  |  " + shown.room else "")
                )
                v.setViewVisibility(R.id.cur_status, View.VISIBLE)
                if (live) {
                    v.setTextViewText(R.id.cur_status, "• Started")
                    v.setTextColor(R.id.cur_status, 0xFF4CAF50.toInt())
                } else {
                    val mins = (shown.startHour * 60 + shown.startMinute - now + 59) / 60
                    v.setTextViewText(R.id.cur_status, "• Upcoming " + mins + " min")
                    v.setTextColor(R.id.cur_status, 0xFFFFFFFF.toInt())
                }
            }
            v.setOnClickPendingIntent(R.id.cur_root, TimetableWidgets.openApp(context))
            return v
        }
    }
}
