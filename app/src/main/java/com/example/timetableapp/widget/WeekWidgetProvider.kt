package com.example.timetableapp.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.timetableapp.R

class WeekWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, views(context)) }
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.week_list)
    }

    companion object {
        fun views(context: Context): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_week)
            v.setRemoteAdapter(R.id.week_list, Intent(context, WeekWidgetService::class.java))
            v.setEmptyView(R.id.week_list, R.id.week_empty)
            v.setPendingIntentTemplate(R.id.week_list, TimetableWidgets.openApp(context))
            v.setOnClickPendingIntent(R.id.week_root, TimetableWidgets.openApp(context))
            return v
        }
    }
}
