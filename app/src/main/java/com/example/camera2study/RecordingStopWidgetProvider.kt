package com.example.camera2study

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews

class RecordingStopWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAll(context)
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            START -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastStart < 1_000 || RecordingRuntime.state.value?.active == true) return
                lastStart = now
                CameraForegroundService.requestQuickRecording(context, source = "widget")
            }
            STOP -> CameraForegroundService.requestStop(context)
            else -> super.onReceive(context, intent)
        }
    }
    companion object {
        private const val START = "com.example.camera2study.widget.START"
        private const val STOP = "com.example.camera2study.widget.STOP"
        private var lastStart = -1_000L
        fun updateAll(context: Context, state: RecordingState = RecordingRuntime.state.value ?: RecordingState()) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, RecordingStopWidgetProvider::class.java)).forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_stop_recording)
                views.setTextViewText(R.id.txtWidgetStatus, state.message)
                views.setTextViewText(R.id.btnWidgetStart, "녹화 시작")
                views.setTextViewText(R.id.btnWidgetStop, "중지")
                views.setBoolean(R.id.btnWidgetStart, "setEnabled", !state.active)
                for ((view, action) in listOf(R.id.btnWidgetStart to START, R.id.btnWidgetStop to STOP)) {
                    val intent = Intent(context, RecordingStopWidgetProvider::class.java).apply { this.action = action }
                    views.setOnClickPendingIntent(view, PendingIntent.getBroadcast(context, id * 2 + if (action == START) 0 else 1,
                        intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
                manager.updateAppWidget(id, views)
            }
        }
    }
}
