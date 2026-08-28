package com.voicerecorder.dual.model

import android.net.Uri

data class RecordingItem(val uri: Uri, val name: String, val dateAddedMillis: Long, val durationMillis: Long, val mimeType: String, val size: Long)
