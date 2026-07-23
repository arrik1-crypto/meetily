package com.meetily.mobile

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen widget: one-tap Record, plus an Import shortcut. */
class RecordWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_record)
            views.setOnClickPendingIntent(
                R.id.widgetRecord,
                PendingIntent.getActivity(
                    context, 9101,
                    Intent(context, RecordingActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            views.setOnClickPendingIntent(
                R.id.widgetImport,
                PendingIntent.getActivity(
                    context, 9102,
                    Intent(context, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_IMPORT_PICK)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
