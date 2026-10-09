package com.example.timetableapp.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.timetableapp.MainActivity
import com.example.timetableapp.R
import com.example.timetableapp.data.repository.TimetableRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.timetableapp.widget.TimetableWidgets

// ponytail: one receiver does both jobs — fires class reminders, and re-arms
// alarms after a reboot (data reloaded first; goAsync holds the window).
class TimetableReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                val done = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val repo = TimetableRepository.getInstance(context)
                        repo.load()
                        ReminderScheduler.refresh(context, repo.snapshotSessions(), repo.snapshotCourses())
                        TimetableWidgets.refresh(context)
                    } catch (_: Exception) {
                    } finally {
                        done.finish()
                    }
                }
            }
            ReminderScheduler.ACTION_REMIND -> {
                // ponytail: stale alarms (deleted/edited sessions, pre-proof builds)
                // die here — the session must still exist with this exact start
                val done = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val sid = intent.getIntExtra("sid", 0)
                        val sms = intent.getLongExtra("sms", 0L)
                        var ok = false
                        if (sid != 0 && sms != 0L) {
                            val repo = TimetableRepository.getInstance(context)
                            repo.load()
                            val cal = java.util.Calendar.getInstance().apply { timeInMillis = sms }
                            val dow = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1
                            ok = repo.snapshotSessions().any { s ->
                                s.id == sid && s.dayOfWeek == dow &&
                                    s.startHour == cal.get(java.util.Calendar.HOUR_OF_DAY) &&
                                    s.startMinute == cal.get(java.util.Calendar.MINUTE)
                            }
                        }
                        if (ok) notifyNow(context, intent)
                        TimetableWidgets.refresh(context)
                    } catch (_: Exception) {
                    } finally {
                        done.finish()
                    }
                }
            }
        }
    }

    private fun notifyNow(context: Context, intent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL, "Class reminders", NotificationManager.IMPORTANCE_HIGH
        ))
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            intent.getIntExtra("nid", 0),
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.monosched_notif)
                .setContentTitle(intent.getStringExtra("title") ?: "Class")
                .setContentText(intent.getStringExtra("text"))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    companion object {
        private const val CHANNEL = "class_reminders"
    }
}
