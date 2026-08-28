package com.voicerecorder.dual

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.voicerecorder.dual.data.RecordingRepository
import com.voicerecorder.dual.data.SessionStore
import com.voicerecorder.dual.databinding.ActivityMainBinding
import com.voicerecorder.dual.service.RecorderStatus
import com.voicerecorder.dual.service.RecordingService
import com.voicerecorder.dual.service.RecordingState
import com.voicerecorder.dual.ui.RecordingAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding; private lateinit var adapter: RecordingAdapter
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) RecordingService.start(this)
        else Snackbar.make(binding.root, R.string.microphone_permission_required, Snackbar.LENGTH_LONG).show()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); binding = ActivityMainBinding.inflate(layoutInflater); setContentView(binding.root)
        val repository = RecordingRepository(this); adapter = RecordingAdapter(this, repository, ::loadRecordings)
        binding.recordingsList.layoutManager = LinearLayoutManager(this); binding.recordingsList.adapter = adapter
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.recordButton.setOnClickListener { if (SessionStore(this).load() != null) RecordingService.stop(this) else requestAndStart() }
        lifecycleScope.launch { repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { RecordingState.flow.collect { state ->
            val persistedActive = SessionStore(this@MainActivity).load() != null
            val status = if (state.status == RecorderStatus.IDLE && persistedActive) RecorderStatus.RECORDING else state.status
            binding.statusText.setText(when (status) { RecorderStatus.IDLE -> R.string.ready; RecorderStatus.RECORDING -> R.string.recording; RecorderStatus.WAITING_FOR_MIC -> R.string.waiting_for_microphone })
            val elapsed = if (state.elapsedMillis > 0) state.elapsedMillis else SessionStore(this@MainActivity).load()?.let { System.currentTimeMillis() - it.startedAt } ?: 0
            binding.timerText.text = String.format(Locale.getDefault(), "%02d:%02d:%02d", elapsed / 3_600_000, elapsed / 60_000 % 60, elapsed / 1_000 % 60)
            val active = status != RecorderStatus.IDLE; binding.recordButton.setText(if (active) R.string.stop_recording else R.string.start_recording); binding.recordButton.setIconResource(if (active) R.drawable.ic_stop else R.drawable.ic_mic)
            binding.levelView.animate().scaleX(if (active) (0.25f + (state.level / 32767f).coerceIn(0f, 1f) * 2.5f) else 1f).setDuration(350).start()
        } } }
    }
    override fun onResume() { super.onResume(); loadRecordings() }
    private fun requestAndStart() {
        val required = buildList { if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO); if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS) }
        if (required.isEmpty()) RecordingService.start(this) else permissions.launch(required.toTypedArray())
    }
    private fun loadRecordings() { lifecycleScope.launch { val items = withContext(Dispatchers.IO) { RecordingRepository(this@MainActivity).list() }; adapter.submit(items); binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE } }
    override fun onDestroy() { adapter.release(); super.onDestroy() }
}
