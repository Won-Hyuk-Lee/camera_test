package com.example.camera2study

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class RecordingStopWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP_FROM_WIDGET) {
            val stopIntent = Intent(context, CameraForegroundService::class.java).apply {
                action = CameraForegroundService.ACTION_STOP_RECORDING
            }
            try {
                context.startService(stopIntent)
            } catch (_: IllegalStateException) {
                // If the recording service is not already alive, there is nothing to stop.
            }
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val ACTION_STOP_FROM_WIDGET =
            "com.example.camera2study.ACTION_STOP_FROM_WIDGET"

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_stop_recording)
            val intent = Intent(context, RecordingStopWidgetProvider::class.java).apply {
                action = ACTION_STOP_FROM_WIDGET
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btnWidgetStop, pendingIntent)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
