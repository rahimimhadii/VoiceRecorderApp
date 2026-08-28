package com.voicerecorder.dual.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.voicerecorder.dual.R
import com.voicerecorder.dual.data.SessionStore
import com.voicerecorder.dual.service.RecordingService

class StopWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = ids.forEach { id ->
        val intent = Intent(context, StopWidgetProvider::class.java).setAction(ACTION)
        manager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.widget_stop).apply { setOnClickPendingIntent(R.id.widgetStop, PendingIntent.getBroadcast(context, 11, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) })
    }
    override fun onReceive(context: Context, intent: Intent) { super.onReceive(context, intent); if (intent.action == ACTION && SessionStore(context).load() != null) RecordingService.stop(context) }
    companion object { private const val ACTION = "com.voicerecorder.dual.WIDGET_STOP" }
}
