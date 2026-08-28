package com.voicerecorder.dual.model

import java.io.File

/**
 * Represents one saved recording on disk.
 *
 * [part] distinguishes files that were split because a phone call interrupted
 * a recording in progress: part = 1 for the segment before the call,
 * part = 2 for the segment started right after the call ended, etc.
 * A normal, uninterrupted recording always has part = 1.
 */
data class RecordingFile(
    val file: File,
    val displayName: String,
    val createdAtMillis: Long,
    val durationMillis: Long,
    val format: AudioFormat,
    val part: Int,
    val interruptedByCall: Boolean
)

enum class AudioFormat(val extension: String, val label: String) {
    M4A("m4a", "M4A"),
    MP3("mp3", "MP3"),
    WAV("wav", "WAV")
}
