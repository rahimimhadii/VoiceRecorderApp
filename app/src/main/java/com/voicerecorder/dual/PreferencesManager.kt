package com.voicerecorder.dual.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.voicerecorder.dual.model.AudioFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Two independent limits, as discussed:
 *  - [autoStopMinutes]: the user's chosen recording length. When reached,
 *    recording stops automatically and the file is saved.
 *  - [hardCapMinutes]: a safety ceiling the user's own [autoStopMinutes]
 *    value can never exceed, even by mistake. Enforced in [setAutoStopMinutes].
 */
class PreferencesManager(private val context: Context) {

    private object Keys {
        val FORMAT = stringPreferencesKey("audio_format")
        val AUTO_STOP_MINUTES = intPreferencesKey("auto_stop_minutes")
        val HARD_CAP_MINUTES = intPreferencesKey("hard_cap_minutes")
    }

    companion object {
        const val DEFAULT_AUTO_STOP_MINUTES = 60
        const val DEFAULT_HARD_CAP_MINUTES = 180
        const val MIN_HARD_CAP_MINUTES = 5
        const val MAX_HARD_CAP_MINUTES = 720 // 12h absolute ceiling for the ceiling itself
    }

    val audioFormat: Flow<AudioFormat> = context.dataStore.data.map { prefs ->
        AudioFormat.valueOf(prefs[Keys.FORMAT] ?: AudioFormat.M4A.name)
    }

    val autoStopMinutes: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.AUTO_STOP_MINUTES] ?: DEFAULT_AUTO_STOP_MINUTES
    }

    val hardCapMinutes: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.HARD_CAP_MINUTES] ?: DEFAULT_HARD_CAP_MINUTES
    }

    suspend fun setAudioFormat(format: AudioFormat) {
        context.dataStore.edit { it[Keys.FORMAT] = format.name }
    }

    /**
     * Sets the safety ceiling itself. Clamped to a sane absolute range so the
     * ceiling can't be set to something meaningless either.
     */
    suspend fun setHardCapMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(MIN_HARD_CAP_MINUTES, MAX_HARD_CAP_MINUTES)
        context.dataStore.edit { prefs ->
            prefs[Keys.HARD_CAP_MINUTES] = clamped
            // If the existing auto-stop value is now above the new ceiling, pull it down too.
            val currentAutoStop = prefs[Keys.AUTO_STOP_MINUTES] ?: DEFAULT_AUTO_STOP_MINUTES
            if (currentAutoStop > clamped) {
                prefs[Keys.AUTO_STOP_MINUTES] = clamped
            }
        }
    }

    /**
     * Sets the user's desired auto-stop length. This is where the "second
     * value blocks a mistaken first value" rule actually gets enforced: the
     * result is always min(requested, current hard cap).
     */
    suspend fun setAutoStopMinutes(minutes: Int) {
        context.dataStore.edit { prefs ->
            val cap = prefs[Keys.HARD_CAP_MINUTES] ?: DEFAULT_HARD_CAP_MINUTES
            prefs[Keys.AUTO_STOP_MINUTES] = minutes.coerceIn(1, cap)
        }
    }
}
