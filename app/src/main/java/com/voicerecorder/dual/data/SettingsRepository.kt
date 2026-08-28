package com.voicerecorder.dual.data

import android.content.Context

enum class RecordingFormat(val extension: String, val mimeType: String) {
    M4A("m4a", "audio/mp4"), WAV("wav", "audio/wav")
}

data class RecorderSettings(val format: RecordingFormat, val autoStopMinutes: Long, val safetyHours: Long) {
    val effectiveLimitMillis: Long get() = minOf(autoStopMinutes * 60_000L, safetyHours * 3_600_000L)
}

class SettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences("recorder_settings", Context.MODE_PRIVATE)
    fun load() = RecorderSettings(
        runCatching { RecordingFormat.valueOf(preferences.getString("format", null) ?: "M4A") }.getOrDefault(RecordingFormat.M4A),
        preferences.getLong("auto_minutes", 300L).coerceIn(1L, 10_080L),
        preferences.getLong("safety_hours", 6L).coerceIn(1L, 168L)
    )
    fun save(settings: RecorderSettings) = preferences.edit()
        .putString("format", settings.format.name).putLong("auto_minutes", settings.autoStopMinutes)
        .putLong("safety_hours", settings.safetyHours).apply()
}
