package com.voicerecorder.dual.widget

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.voicerecorder.dual.R
import com.voicerecorder.dual.data.SessionStore
import com.voicerecorder.dual.service.RecordingService

class StartWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = ids.forEach { id ->
        val intent = Intent(context, StartWidgetProvider::class.java).setAction(ACTION)
        manager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.widget_start).apply { setOnClickPendingIntent(R.id.widgetStart, PendingIntent.getBroadcast(context, 10, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) })
    }
    override fun onReceive(context: Context, intent: Intent) { super.onReceive(context, intent); if (intent.action == ACTION && SessionStore(context).load() == null && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) RecordingService.start(context) }
    companion object { private const val ACTION = "com.voicerecorder.dual.WIDGET_START" }
}
