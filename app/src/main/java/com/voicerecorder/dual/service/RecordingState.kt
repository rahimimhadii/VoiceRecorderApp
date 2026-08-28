package com.voicerecorder.dual.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecorderStatus { IDLE, RECORDING, WAITING_FOR_MIC }
data class RecordingUiState(val status: RecorderStatus = RecorderStatus.IDLE, val sessionStartedAt: Long = 0, val elapsedMillis: Long = 0, val level: Int = 0, val message: String? = null)
object RecordingState {
    private val mutable = MutableStateFlow(RecordingUiState())
    val flow = mutable.asStateFlow()
    fun set(value: RecordingUiState) { mutable.value = value }
}
