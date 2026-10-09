package com.example.timetableapp.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.timetableapp.data.model.Course
import com.example.timetableapp.data.model.Session
import java.util.Calendar
import com.example.timetableapp.widget.TimetableWidgets

// ponytail: exact one-shot alarms for today's classes (15-min heads-up + at start),
// refreshed on every app open/data change and on boot; no server, no extra deps.
object ReminderScheduler {
    const val ACTION_REMIND = "com.example.timetableapp.action.REMIND_CLASS"
    const val DEFAULT_LEAD_MIN = 15

    // ponytail: the upcoming-heads-up comes from Settings (same file the UI writes)
    private const val SETTINGS_PREFS = "timetable_settings"

    // ponytail: exposed so the UI writes the same file the scheduler reads
    fun saveLeadMin(context: Context, mins: Int) {
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit().putInt("lead_min", mins).apply()
    }

    fun loadLeadMin(context: Context): Int =
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).getInt("lead_min", DEFAULT_LEAD_MIN)

    private fun leadMinOf(context: Context): Long = loadLeadMin(context).toLong()

    private const val PREFS = "reminder_alarms"
    private const val KEY = "codes"

    // ponytail: arms each session's next two occurrences (this week + same weekday
    // next week), so reminders survive days without opening the app; boot re-arms
    // the same way through this one entry point
    fun refresh(context: Context, sessions: List<Session>, courses: List<Course>) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY, emptySet())?.forEach { code ->
            code.toIntOrNull()?.let { am.cancel(pendingFor(context, it)) }
        }
        val now = System.currentTimeMillis()
        val today = mondayDow()
        val byId = courses.associateBy { it.id }
        val dayFmt = java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault())
        val codes = mutableSetOf<String>()
        sessions.forEach { s ->
            for (offset in 0..7) {
                if ((today - 1 + offset) % 7 + 1 != s.dayOfWeek) continue
                val cal = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, offset)
                    set(Calendar.HOUR_OF_DAY, s.startHour)
                    set(Calendar.MINUTE, s.startMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startMs = cal.timeInMillis
                val course = byId[s.courseId]
                val label = listOfNotNull(
                    course?.code?.takeIf { it.isNotBlank() } ?: course?.name,
                    if (s.room.isNotBlank()) s.room else null
                ).joinToString(" · ").ifEmpty { "Class" }
                val time = "%02d:%02d".format(s.startHour, s.startMinute)
                val suffix = if (offset == 0) "" else " (${dayFmt.format(cal.time)})"
                val preMs = startMs - leadMinOf(context) * 60_000L
                if (preMs > now + 30_000) {
                    val code = s.id * 100 + offset * 2
                    set(am, code, preMs, context, code, s.id, startMs,
                        "Upcoming class", "$label at $time$suffix")
                    codes += code.toString()
                }
                if (startMs > now + 30_000) {
                    val code = s.id * 100 + offset * 2 + 1
                    set(am, code, startMs, context, code, s.id, startMs,
                        "Class starting now", "$label at $time$suffix")
                    codes += code.toString()
                }
            }
        }
        prefs.edit().putStringSet(KEY, codes).apply()
        TimetableWidgets.refresh(context)
    }

    private fun set(
        am: AlarmManager, code: Int, atMs: Long, context: Context,
        nid: Int, sid: Int, sms: Long, title: String, text: String
    ) {
        val pi = pendingFor(context, code, nid, sid, sms, title, text)
        val canExact = if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
        if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
    }

    private fun pendingFor(context: Context, code: Int): PendingIntent {
        val i = Intent(context, TimetableReminderReceiver::class.java).setAction(ACTION_REMIND)
        return PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun pendingFor(context: Context, code: Int, nid: Int, sid: Int, sms: Long, title: String, text: String): PendingIntent {
        val i = Intent(context, TimetableReminderReceiver::class.java).setAction(ACTION_REMIND)
            .putExtra("nid", nid).putExtra("sid", sid).putExtra("sms", sms)
            .putExtra("title", title).putExtra("text", text)
        return PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_IMMUTABLE)
    }

    // ponytail: Calendar.SUNDAY=1 → Mon=1..Sun=7
    private fun mondayDow(): Int {
        val c = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return (c + 5) % 7 + 1
    }
}
