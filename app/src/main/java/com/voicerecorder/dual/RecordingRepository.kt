
package com.voicerecorder.dual.data

import android.content.Context
import android.media.MediaMetadataRetriever
import com.voicerecorder.dual.model.AudioFormat
import com.voicerecorder.dual.model.RecordingFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Reads/writes recordings under the app's own external files directory
 * (no storage permission needed on API 26+, and the files are removed
 * automatically if the app is uninstalled).
 */
class RecordingRepository(private val context: Context) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    fun recordingsDir(): File {
        val dir = File(context.getExternalFilesDir(null), "Recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Builds the destination file for a new recording segment.
     * [part] > 1 and [interrupted] = true mark a segment that continues after
     * a phone call cut the previous one off, per the naming scheme discussed:
     * rec_<timestamp>_part1.<ext>, rec_<timestamp>_part2.<ext>, ...
     */
    fun newRecordingFile(format: AudioFormat, sessionTimestamp: String, part: Int): File {
        val name = "rec_${sessionTimestamp}_part$part.${format.extension}"
        return File(recordingsDir(), name)
    }

    fun currentSessionTimestamp(): String = dateFormat.format(System.currentTimeMillis())

    fun listRecordings(): List<RecordingFile> {
        val files = recordingsDir().listFiles { f -> f.isFile } ?: emptyArray()
        return files.mapNotNull { file -> toRecordingFile(file) }
            .sortedByDescending { it.createdAtMillis }
    }

    fun delete(recording: RecordingFile): Boolean = recording.file.delete()

    private fun toRecordingFile(file: File): RecordingFile? {
        val format = AudioFormat.entries.find { file.extension.equals(it.extension, true) }
            ?: return null

        // Parse "rec_<timestamp>_partN.ext"
        val nameNoExt = file.nameWithoutExtension
        val partMatch = Regex("_part(\\d+)$").find(nameNoExt)
        val part = partMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val duration = try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            0L
        }

        return RecordingFile(
            file = file,
            displayName = file.name,
            createdAtMillis = file.lastModified(),
            durationMillis = duration,
            format = format,
            part = part,
            interruptedByCall = part > 1
        )
    }
}

private inline fun <T : AutoCloseable?, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        this?.close()
    }
}
