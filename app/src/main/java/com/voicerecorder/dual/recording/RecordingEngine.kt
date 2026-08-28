package com.voicerecorder.dual.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.voicerecorder.dual.data.RecordingFormat
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

interface RecordingEngine {
    val file: File
    val level: Int
    val audioSessionId: Int
    fun start()
    fun stop()
}

class AacRecordingEngine(override val file: File, private val onFailure: () -> Unit) : RecordingEngine {
    private var recorder: MediaRecorder? = null
    override val level: Int get() = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
    override val audioSessionId: Int get() = runCatching { recorder?.audioSessionId ?: -1 }.getOrDefault(-1)
    override fun start() {
        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setAudioEncodingBitRate(128_000); setAudioSamplingRate(44_100)
            setOnErrorListener { _, _, _ -> onFailure() }
            setOutputFile(file.absolutePath); prepare(); start()
        }
    }
    override fun stop() { val value = recorder ?: return; recorder = null; runCatching { value.stop() }; runCatching { value.reset() }; runCatching { value.release() } }
}

class WavRecordingEngine(override val file: File, private val onFailure: () -> Unit) : RecordingEngine {
    private val rate = 44_100
    private val running = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var job: Job? = null
    @Volatile private var currentLevel = 0
    override val level: Int get() = currentLevel
    override val audioSessionId: Int get() = audioRecord?.audioSessionId ?: -1

    @SuppressLint("MissingPermission")
    override fun start() {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0) { "Unsupported PCM configuration" }
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
        audioRecord = record; writeHeader(file, 0); record.startRecording(); running.set(true)
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val buffer = ShortArray(min)
            RandomAccessFile(file, "rw").use { output ->
                output.seek(44); var bytes = 0L
                while (running.get()) {
                    val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read <= 0) { if (running.get()) onFailure(); break }
                    var peak = 0
                    repeat(read) { val sample = buffer[it].toInt(); peak = maxOf(peak, kotlin.math.abs(sample)); output.write(sample and 0xff); output.write((sample shr 8) and 0xff) }
                    currentLevel = peak; bytes += read * 2L
                }
                updateHeader(output, bytes)
            }
        }
    }
    override fun stop() { running.set(false); runCatching { audioRecord?.stop() }; runBlocking { job?.join() }; audioRecord?.release(); audioRecord = null; job = null }
    private fun writeHeader(file: File, size: Long) = RandomAccessFile(file, "rw").use { updateHeader(it, size) }
    private fun updateHeader(out: RandomAccessFile, data: Long) { out.seek(0); fun text(s:String)=out.write(s.toByteArray(Charsets.US_ASCII)); fun le(v:Long,n:Int)=repeat(n){out.write(((v shr (8*it)) and 255).toInt())}; text("RIFF");le(data+36,4);text("WAVEfmt ");le(16,4);le(1,2);le(1,2);le(rate.toLong(),4);le(rate*2L,4);le(2,2);le(16,2);text("data");le(data,4) }
}

fun createEngine(context: Context, format: RecordingFormat, onFailure: () -> Unit): RecordingEngine {
    val file = File(context.cacheDir, "segment_${System.nanoTime()}.${format.extension}")
    return if (format == RecordingFormat.M4A) AacRecordingEngine(file, onFailure) else WavRecordingEngine(file, onFailure)
}
