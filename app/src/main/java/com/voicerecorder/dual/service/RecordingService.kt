package com.voicerecorder.dual.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.voicerecorder.dual.MainActivity
import com.voicerecorder.dual.R
import com.voicerecorder.dual.data.*
import com.voicerecorder.dual.recording.RecordingEngine
import com.voicerecorder.dual.recording.createEngine
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class RecordingService : Service() {
    companion object {
        const val ACTION_START = "com.voicerecorder.dual.START"
        const val ACTION_STOP = "com.voicerecorder.dual.STOP"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 41
        fun start(context: Context) = context.startForegroundService(Intent(context, RecordingService::class.java).setAction(ACTION_START))
        fun stop(context: Context) = context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sessions: SessionStore
    private lateinit var repository: RecordingRepository
    private lateinit var audioManager: AudioManager
    private var session: StoredSession? = null
    private var engine: RecordingEngine? = null
    private var monitorJob: Job? = null
    private var retryJob: Job? = null
    private var limitJob: Job? = null
    private var retryAttempt = 0
    private var handlingLoss = false
    private val audioCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            val id = engine?.audioSessionId ?: return
            val own = configs?.firstOrNull { it.clientAudioSessionId == id }
            if (own?.isClientSilenced == true) scope.launch { microphoneLost() }
        }
    }

    override fun onCreate() {
        super.onCreate(); sessions = SessionStore(this); repository = RecordingRepository(this)
        audioManager = getSystemService(AudioManager::class.java); audioManager.registerAudioRecordingCallback(audioCallback, null)
        createChannel()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> scope.launch { finishSession() }
            ACTION_START -> scope.launch { beginOrRestore() }
            else -> if (sessions.load() != null) scope.launch { beginOrRestore() } else stopSelf()
        }
        return START_STICKY
    }

    private suspend fun beginOrRestore() {
        if (session != null || engine != null) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { sessions.clear(); stopSelf(); return }
        session = sessions.load() ?: sessions.create(SettingsRepository(this).load())
        startForegroundNow(false)
        if (expired()) { finishSession(); return }
        limitJob?.cancel()
        limitJob = scope.launch {
            val current = session ?: return@launch
            delay((current.limitMillis - (System.currentTimeMillis() - current.startedAt)).coerceAtLeast(1L))
            scope.launch { finishSession() }
        }
        startSegment()
    }

    private fun startForegroundNow(waiting: Boolean) {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(waiting), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private suspend fun startSegment() {
        if (engine != null || session == null) return
        try {
            val newEngine = createEngine(this, session!!.format) { scope.launch { microphoneLost() } }
            engine = newEngine
            newEngine.start(); retryAttempt = 0; handlingLoss = false
            monitorJob?.cancel(); monitorJob = scope.launch {
                while (isActive && engine != null) {
                    val current = session ?: break; val elapsed = System.currentTimeMillis() - current.startedAt
                    if (elapsed >= current.limitMillis) { scope.launch { finishSession() }; break }
                    RecordingState.set(RecordingUiState(RecorderStatus.RECORDING, current.startedAt, elapsed, engine?.level ?: 0))
                    updateNotification(false); delay(1_000)
                }
            }
        } catch (error: Throwable) { enterWaiting(error.message) }
    }

    private suspend fun microphoneLost() {
        if (handlingLoss || session == null) return
        handlingLoss = true; monitorJob?.cancel(); monitorJob = null
        val old = engine; engine = null
        val callRelated = audioManager.mode == AudioManager.MODE_IN_CALL || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
        withContext(Dispatchers.IO) { old?.stop(); old?.let { publish(it, if (callRelated) "beforecall" else "recording") } }
        sessions.markInterrupted(callRelated); session = sessions.load(); enterWaiting(null)
    }

    private fun enterWaiting(message: String?) {
        if (session == null) return
        engine?.let { runCatching { it.stop() }; it.file.delete() }; engine = null
        val current = session!!
        RecordingState.set(RecordingUiState(RecorderStatus.WAITING_FOR_MIC, current.startedAt, System.currentTimeMillis() - current.startedAt, message))
        startForegroundNow(true); scheduleRetry()
    }

    private fun scheduleRetry() {
        retryJob?.cancel(); retryJob = scope.launch {
            val delayMillis = min(60_000L, 2_000L * (1L shl min(retryAttempt++, 5))); delay(delayMillis)
            if (expired()) scope.launch { finishSession() } else startSegment()
        }
    }

    private suspend fun finishSession() {
        retryJob?.cancel(); monitorJob?.cancel(); limitJob?.cancel(); retryJob = null; monitorJob = null; limitJob = null
        val current = session ?: sessions.load()
        session = null; sessions.clear()
        val old = engine; engine = null
        withContext(Dispatchers.IO) {
            old?.stop()
            if (old != null && current != null) publish(old, if (current.afterCall) "aftercall" else "recording", current)
        }
        RecordingState.set(RecordingUiState()); ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun publish(value: RecordingEngine, prefix: String, current: StoredSession = session ?: return) {
        if (value.file.length() <= 44) { value.file.delete(); return }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        repository.publish(value.file, "${prefix}_${stamp}.${current.format.extension}", current.format, current.id)
    }
    private fun expired(): Boolean { val current = session ?: return true; return System.currentTimeMillis() - current.startedAt >= current.limitMillis }
    private fun notification(waiting: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_mic).setContentTitle(getString(if (waiting) R.string.notification_waiting else R.string.notification_recording))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setWhen(session?.startedAt ?: System.currentTimeMillis()).setUsesChronometer(!waiting).addAction(R.drawable.ic_stop, getString(R.string.notification_stop), stop).build()
    }
    private fun updateNotification(waiting: Boolean) = getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(waiting))
    private fun createChannel() = getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW).apply { description = getString(R.string.recording_channel_description) })
    override fun onDestroy() { audioManager.unregisterAudioRecordingCallback(audioCallback); engine?.stop(); scope.cancel(); super.onDestroy() }
}
