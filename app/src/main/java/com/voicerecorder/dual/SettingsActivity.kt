package com.voicerecorder.dual

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.voicerecorder.dual.data.RecorderSettings
import com.voicerecorder.dual.data.RecordingFormat
import com.voicerecorder.dual.data.SettingsRepository
import com.voicerecorder.dual.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); binding = ActivitySettingsBinding.inflate(layoutInflater); setContentView(binding.root)
        val repository = SettingsRepository(this); val settings = repository.load()
        binding.formatGroup.check(if (settings.format == RecordingFormat.M4A) R.id.m4aButton else R.id.wavButton)
        binding.minutesInput.setText(settings.autoStopMinutes.toString()); binding.hoursInput.setText(settings.safetyHours.toString())
        binding.saveButton.setOnClickListener {
            val minutes = binding.minutesInput.text?.toString()?.toLongOrNull(); val hours = binding.hoursInput.text?.toString()?.toLongOrNull()
            binding.minutesLayout.error = if (minutes == null || minutes !in 1..10_080) getString(R.string.invalid_minutes) else null
            binding.hoursLayout.error = if (hours == null || hours !in 1..168) getString(R.string.invalid_hours) else null
            if (minutes != null && hours != null && minutes in 1..10_080 && hours in 1..168) {
                repository.save(RecorderSettings(if (binding.formatGroup.checkedButtonId == R.id.wavButton) RecordingFormat.WAV else RecordingFormat.M4A, minutes, hours))
                Snackbar.make(binding.root, R.string.settings_saved, Snackbar.LENGTH_SHORT).show()
            }
        }
    }
}
