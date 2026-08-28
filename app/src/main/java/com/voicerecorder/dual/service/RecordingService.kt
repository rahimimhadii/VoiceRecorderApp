package com.voicerecorder.dual.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.voicerecorder.dual.MainActivity
import com.voicerecorder.dual.data.PreferencesManager
import com.voicerecorder.dual.data.RecordingRepository
import com.voicerecorder.dual.model.AudioFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.voicerecorder.dual.action.START"
        const val ACTION_STOP = "com.voicerecorder.dual.action.STOP"
        const val ACTION_CALL_STARTED = "com.voicerecorder.dual.action.CALL_STARTED"
        const val ACTION_CALL_ENDED = "com.voicerecorder.dual.action.CALL_ENDED"

        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1001

        // Simple app-wide observable state the UI collects. Good enough for
        // this app's scope; swap for a proper repository/DI setup if the
        // project grows.
        private val _uiState = MutableStateFlow(RecordingUiState())
        val uiState = _uiState.asStateFlow()
    }

    data class RecordingUiState(
        val isRecording: Boolean = false,
        val isPausedForCall: Boolean = false,
        val elapsedMillis: Long = 0L,
        val currentFileName: String? = null
    )

    private lateinit var prefs: PreferencesManager
    private lateinit var repository: RecordingRepository
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())

    private var mediaRecorder: MediaRecorder? = null
    private var wavRecorder: WavRecorder? = null
    private var countDownTimer: CountDownTimer? = null

    private var sessionTimestamp: String = ""
    private var currentPart: Int = 1
    private var currentFormat: AudioFormat = AudioFormat.M4A
    private var wasInterruptedByCall: Boolean = false
    private var elapsedBeforeThisPartMillis: Long = 0L
    private var partStartedAtMillis: Long = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(applicationContext)
        repository = RecordingRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startNewSession()
            ACTION_STOP -> stopSession()
            ACTION_CALL_STARTED -> pauseForCall()
            ACTION_CALL_ENDED -> resumeAfterCall()
        }
        return START_STICKY
    }

    // ---- Session lifecycle -------------------------------------------------

    private fun startNewSession() {
        if (_uiState.value.isRecording) return

        serviceScope.launch {
            val format = prefs.audioFormat.first()
            val autoStopMinutes = prefs.autoStopMinutes.first()

            currentFormat = resolveActualFormat(format)
            sessionTimestamp = repository.currentSessionTimestamp()
            currentPart = 1
            wasInterruptedByCall = false
            elapsedBeforeThisPartMillis = 0L

            startForegroundNotification()
            beginRecordingPart(autoStopMinutes)
        }
    }

    private fun beginRecordingPart(autoStopMinutes: Int) {
        val file = repository.newRecordingFile(currentFormat, sessionTimestamp, currentPart)
        partStartedAtMillis = System.currentTimeMillis()

        when (currentFormat) {
            AudioFormat.WAV -> startWavRecording(file)
            else -> startMediaRecorderRecording(file) // M4A, and MP3-fallback-to-M4A
        }

        _uiState.value = RecordingUiState(
            isRecording = true,
            isPausedForCall = false,
            elapsedMillis = elapsedBeforeThisPartMillis,
            currentFileName = file.name
        )

        startAutoStopTimer(autoStopMinutes)
    }

    private fun startMediaRecorderRecording(file: File) {
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
    }

    private fun startWavRecording(file: File) {
        wavRecorder = WavRecorder(file).also { it.start() }
    }

    private fun stopCurrentPartRecorder() {
        mediaRecorder?.apply {
            try { stop() } catch (e: Exception) { /* no-op: nothing was captured */ }
            release()
        }
        mediaRecorder = null

        wavRecorder?.stopAndFinalize()
        wavRecorder = null
    }

    private fun stopSession() {
        countDownTimer?.cancel()
        stopCurrentPartRecorder()
        _uiState.value = RecordingUiState()
        stopForegroundCompat()
        stopSelf()
    }

    // ---- Phone call handling ------------------------------------------------

    /** Called by [com.voicerecorder.dual.receiver.CallStateReceiver] on RINGING/OFFHOOK. */
    private fun pauseForCall() {
        if (!_uiState.value.isRecording || _uiState.value.isPausedForCall) return

        countDownTimer?.cancel()
        elapsedBeforeThisPartMillis += System.currentTimeMillis() - partStartedAtMillis
        stopCurrentPartRecorder()
        wasInterruptedByCall = true

        _uiState.value = _uiState.value.copy(isPausedForCall = true)
    }

    /** Called by [com.voicerecorder.dual.receiver.CallStateReceiver] when the call ends. */
    private fun resumeAfterCall() {
        if (!_uiState.value.isRecording || !_uiState.value.isPausedForCall) return

        serviceScope.launch {
            val autoStopMinutes = prefs.autoStopMinutes.first()
            currentPart += 1
            beginRecordingPart(remainingMinutes(autoStopMinutes))
        }
    }

    private fun remainingMinutes(autoStopMinutes: Int): Int {
        val remainingMillis =
            (autoStopMinutes * 60_000L - elapsedBeforeThisPartMillis).coerceAtLeast(60_000L)
        return (remainingMillis / 60_000L).toInt().coerceAtLeast(1)
    }

    // ---- Auto-stop timer ------------------------------------------------

    private fun startAutoStopTimer(autoStopMinutes: Int) {
        val totalMillis = autoStopMinutes * 60_000L
        val alreadyElapsed = elapsedBeforeThisPartMillis
        val remaining = (totalMillis - alreadyElapsed).coerceAtLeast(0L)

        countDownTimer = object : CountDownTimer(remaining, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val elapsed = elapsedBeforeThisPartMillis +
                    (System.currentTimeMillis() - partStartedAtMillis)
                _uiState.value = _uiState.value.copy(elapsedMillis = elapsed)
            }

            override fun onFinish() {
                // Auto-stop limit reached: stop and save, per the spec.
                stopSession()
            }
        }.start()
    }

    /**
     * Resolves the format the recorder will actually use. Android has no
     * built-in MP3 encoder (MediaRecorder only supports AAC/AMR/etc). Real
     * MP3 output needs a third-party encoder (e.g. a LAME binding) that
     * isn't bundled in this project. Until one is added, MP3 selection
     * falls back to M4A so the app never produces a mislabeled/broken file.
     */
    private fun resolveActualFormat(requested: AudioFormat): AudioFormat =
        if (requested == AudioFormat.MP3) AudioFormat.M4A else requested

    // ---- Notification -----------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice recording",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("در حال ضبط صدا")
            .setContentText("برای مدیریت ضبط، اپ را باز کنید")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        stopCurrentPartRecorder()
        super.onDestroy()
    }
}

/**
 * Minimal raw-PCM-to-WAV recorder using AudioRecord, since MediaRecorder
 * cannot produce WAV directly. Records 16-bit mono PCM at 44.1kHz and writes
 * a standard 44-byte WAV header once recording stops (the header needs the
 * final data size, so it's patched in after the fact).
 */
private class WavRecorder(private val outputFile: File) {
    private val sampleRate = 44_100
    private val channelConfig = AndroidAudioFormat.CHANNEL_IN_MONO
    private val audioEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false

    fun start() {
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, channelConfig, audioEncoding, minBufferSize * 2
        )

        // Reserve 44 bytes at the top of the file for the header, patched in on stop.
        outputFile.outputStream().use { it.write(ByteArray(44)) }

        isRecording = true
        audioRecord?.startRecording()

        recordingThread = Thread {
            val buffer = ByteArray(minBufferSize)
            RandomAccessFile(outputFile, "rw").use { raf ->
                raf.seek(44)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) raf.write(buffer, 0, read)
                }
            }
        }.also { it.start() }
    }

    fun stopAndFinalize() {
        isRecording = false
        recordingThread?.join(1000)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        writeWavHeader()
    }

    private fun writeWavHeader() {
        val dataSize = outputFile.length() - 44
        if (dataSize < 0) return

        RandomAccessFile(outputFile, "rw").use { raf ->
            raf.seek(0)
            val byteRate = sampleRate * 2 // mono * 16-bit
            raf.write("RIFF".toByteArray())
            raf.write(intToLE((36 + dataSize).toInt()))
            raf.write("WAVE".toByteArray())
            raf.write("fmt ".toByteArray())
            raf.write(intToLE(16))
            raf.write(shortToLE(1)) // PCM
            raf.write(shortToLE(1)) // mono
            raf.write(intToLE(sampleRate))
            raf.write(intToLE(byteRate))
            raf.write(shortToLE(2)) // block align
            raf.write(shortToLE(16)) // bits per sample
            raf.write("data".toByteArray())
            raf.write(intToLE(dataSize.toInt()))
        }
    }

    private fun intToLE(v: Int) = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()
    )

    private fun shortToLE(v: Int) = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte()
    )
}
