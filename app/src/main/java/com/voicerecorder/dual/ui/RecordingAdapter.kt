package com.voicerecorder.dual.ui

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.voicerecorder.dual.R
import com.voicerecorder.dual.data.RecordingRepository
import com.voicerecorder.dual.databinding.ItemRecordingBinding
import com.voicerecorder.dual.model.RecordingItem
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class RecordingAdapter(private val context: Context, private val repository: RecordingRepository, private val changed: () -> Unit) : RecyclerView.Adapter<RecordingAdapter.Holder>() {
    private var items = emptyList<RecordingItem>(); private var player: MediaPlayer? = null; private var playing: String? = null
    fun submit(value: List<RecordingItem>) { items = value; notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    inner class Holder(private val binding: ItemRecordingBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: RecordingItem) {
            binding.nameText.text = item.name
            val duration = String.format(Locale.getDefault(), "%02d:%02d", item.durationMillis / 60_000, item.durationMillis / 1_000 % 60)
            binding.detailsText.text = "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.dateAddedMillis))}  •  $duration  •  ${item.name.substringAfterLast('.').uppercase()}"
            binding.playButton.setText(if (playing == item.uri.toString()) R.string.pause else R.string.play)
            binding.playButton.setOnClickListener { toggle(item) }
            binding.moreButton.setOnClickListener { anchor -> PopupMenu(context, anchor).apply {
                menu.add(R.string.rename).setOnMenuItemClickListener { rename(item); true }
                menu.add(R.string.share).setOnMenuItemClickListener { share(item); true }
                menu.add(R.string.delete).setOnMenuItemClickListener { repository.delete(item); changed(); true }
                show()
            } }
        }
    }
    private fun toggle(item: RecordingItem) {
        if (playing == item.uri.toString()) { player?.pause(); playing = null; notifyDataSetChanged(); return }
        player?.release(); player = MediaPlayer().apply { setDataSource(context, item.uri); prepare(); start(); setOnCompletionListener { release(); player = null; playing = null; notifyDataSetChanged() } }
        playing = item.uri.toString(); notifyDataSetChanged()
    }
    private fun share(item: RecordingItem) = context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = item.mimeType; putExtra(Intent.EXTRA_STREAM, item.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, context.getString(R.string.share)))
    private fun rename(item: RecordingItem) { val input = TextInputEditText(context).apply { setText(item.name.substringBeforeLast('.')); setSelectAllOnFocus(true) }; AlertDialog.Builder(context).setTitle(R.string.rename_recording).setView(input).setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.save) { _, _ -> if (repository.rename(item, input.text?.toString().orEmpty())) changed() }.show() }
    fun release() { player?.release(); player = null }
}
