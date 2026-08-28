package com.voicerecorder.dual.data

import android.content.Context
import java.util.UUID

data class StoredSession(val id: String, val startedAt: Long, val format: RecordingFormat, val limitMillis: Long, val afterCall: Boolean)

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("active_session", Context.MODE_PRIVATE)
    fun create(settings: RecorderSettings): StoredSession = StoredSession(UUID.randomUUID().toString(), System.currentTimeMillis(), settings.format, settings.effectiveLimitMillis, false).also(::save)
    fun load(): StoredSession? {
        if (!prefs.getBoolean("active", false)) return null
        val id = prefs.getString("id", null) ?: return null
        return StoredSession(id, prefs.getLong("started", 0), runCatching { RecordingFormat.valueOf(prefs.getString("format", "M4A")!!) }.getOrDefault(RecordingFormat.M4A), prefs.getLong("limit", 0), prefs.getBoolean("after_call", false))
    }
    fun save(session: StoredSession) = prefs.edit().putBoolean("active", true).putString("id", session.id).putLong("started", session.startedAt).putString("format", session.format.name).putLong("limit", session.limitMillis).putBoolean("after_call", session.afterCall).apply()
    fun markInterrupted(callRelated: Boolean) { load()?.let { save(it.copy(afterCall = callRelated)) } }
    fun clear() = prefs.edit().clear().apply()
}
