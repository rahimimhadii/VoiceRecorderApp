package com.voicerecorder.dual.data

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.voicerecorder.dual.model.RecordingItem
import java.io.File

class RecordingRepository(private val context: Context) {
    private val resolver get() = context.contentResolver
    fun publish(file: File, displayName: String, format: RecordingFormat, sessionId: String): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Voice Recorder")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: error("Unable to create media item")
        try {
            resolver.openOutputStream(uri, "w")!!.use { output -> file.inputStream().use { it.copyTo(output) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            context.getSharedPreferences("recording_sessions", Context.MODE_PRIVATE).edit().putString(uri.toString(), sessionId).apply()
            true
        } catch (error: Throwable) { resolver.delete(uri, null, null); throw error }
    }.getOrDefault(false).also { file.delete() }

    fun list(): List<RecordingItem> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE)
        return runCatching {
            resolver.query(collection, projection, "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?", arrayOf("Music/Voice Recorder%"), "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(RecordingItem(
                        android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)), cursor.getString(1), cursor.getLong(2) * 1000,
                        cursor.getLong(3), cursor.getString(4) ?: "audio/*", cursor.getLong(5)
                    ))
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }
    fun delete(item: RecordingItem) = resolver.delete(item.uri, null, null) > 0
    fun rename(item: RecordingItem, requested: String): Boolean {
        val ext = item.name.substringAfterLast('.', "")
        val safe = requested.trim().replace(Regex("[\\/:*?\"<>|]"), "_").take(80)
        if (safe.isBlank()) return false
        val name = if (safe.endsWith(".$ext", true) || ext.isBlank()) safe else "$safe.$ext"
        return resolver.update(item.uri, ContentValues().apply { put(MediaStore.Audio.Media.DISPLAY_NAME, name) }, null, null) > 0
    }
}
