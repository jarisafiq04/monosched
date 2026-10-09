package com.example.timetableapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.timetableapp.MainActivity
import com.example.timetableapp.R
import com.example.timetableapp.data.model.Course
import com.example.timetableapp.data.model.Session
import com.example.timetableapp.data.repository.TimetableRepository
import java.io.File
import java.util.Calendar

// ponytail: classic RemoteViews, no new dependency — one helper drives both widgets
object TimetableWidgets {
    val DAYS = arrayOf("", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")

    fun todayDow(): Int {
        val c = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return (c + 5) % 7 + 1
    }

    fun nowMinutes(): Int =
        Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    fun load(context: Context): Pair<List<Course>, List<Session>> {
        return try {
            val f = File(context.filesDir, "timetable.json")
            if (!f.exists()) emptyList<Course>() to emptyList()
            else TimetableRepository.parseTimetableJson(f.readText())
        } catch (_: Exception) {
            emptyList<Course>() to emptyList()
        }
    }

    fun timeRange(s: Session): String =
        "%02d:%02d - %02d:%02d".format(s.startHour, s.startMinute, s.endHour, s.endMinute)



    // ponytail: next class starting within leadMin, today only — backs the Upcoming status
    fun nextUpcoming(sessions: List<Session>, today: Int, now: Int, leadMin: Int): Session? =
        sessions.filter { it.dayOfWeek == today }
            .map { it to (it.startHour * 60 + it.startMinute - now) }
            .filter { it.second in 1..(leadMin * 60) }
            .minByOrNull { it.second }
            ?.first

    fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    // ponytail: single entry — data writes, class-start alarms and boot all funnel here
    fun refresh(context: Context) {
        try {
            val mgr = AppWidgetManager.getInstance(context)
            mgr.getAppWidgetIds(ComponentName(context, WeekWidgetProvider::class.java)).let { ids ->
                if (ids.isNotEmpty()) {
                    ids.forEach { mgr.updateAppWidget(it, WeekWidgetProvider.views(context)) }
                    mgr.notifyAppWidgetViewDataChanged(ids, R.id.week_list)
                }
            }
            mgr.getAppWidgetIds(ComponentName(context, CurrentWidgetProvider::class.java)).let { ids ->
                if (ids.isNotEmpty()) {
                    val (courses, sessions) = load(context)
                    val byId = courses.associateBy { it.id }
                    ids.forEach { mgr.updateAppWidget(it, CurrentWidgetProvider.views(context, byId, sessions)) }
                }
            }
        } catch (_: Exception) {
        }
    }
}
