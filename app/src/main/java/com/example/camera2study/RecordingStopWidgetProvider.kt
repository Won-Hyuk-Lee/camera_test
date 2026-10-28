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
        val action = intent.action
        if (action !in setOf(TOGGLE, START, STOP)) { super.onReceive(context, intent); return }
        val now = SystemClock.elapsedRealtime()
        if (now - lastAction < 1_000L) return
        val active = RecordingRuntime.state.value?.active == true
        val stop = action == STOP || (action == TOGGLE && active)
        if (!stop && (active || now < blockStartUntil)) return
        lastAction = now
        if (stop) {
            blockStartUntil = now + 1_500L
            if (active) CameraForegroundService.requestStop(context) else updateAll(context)
        } else {
            CameraForegroundService.requestQuickRecording(context, source = "widget")
        }
    }
    companion object {
        private const val TOGGLE = "com.example.camera2study.widget.TOGGLE"
        private const val START = "com.example.camera2study.widget.START"
        private const val STOP = "com.example.camera2study.widget.STOP"
        private var lastAction = -2_000L
        private var blockStartUntil = 0L
        fun updateAll(context: Context, state: RecordingState = RecordingRuntime.state.value ?: RecordingState()) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, RecordingStopWidgetProvider::class.java)).forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_stop_recording)
                views.setContentDescription(R.id.btnWidgetToggle, if (state.active) "녹화 중지 · " + state.message else "녹화 시작 · " + state.message)
                val intent = Intent(context, RecordingStopWidgetProvider::class.java).apply { action = TOGGLE }
                views.setOnClickPendingIntent(R.id.btnWidgetToggle, PendingIntent.getBroadcast(context, id, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                manager.updateAppWidget(id, views)
            }
        }
    }
}
