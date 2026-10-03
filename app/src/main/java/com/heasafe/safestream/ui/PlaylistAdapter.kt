package com.heasafe.safestream.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.heasafe.safestream.databinding.ItemVideoBinding
import com.heasafe.safestream.model.VideoItem
import java.util.Locale
import java.util.concurrent.TimeUnit

class PlaylistAdapter(
    private val onClick: (Int, VideoItem) -> Unit,
) : ListAdapter<VideoItem, PlaylistAdapter.VH>(DIFF) {

    var playingIndex: Int = RecyclerView.NO_POSITION
        set(value) {
            val old = field
            field = value
            if (old != RecyclerView.NO_POSITION) notifyItemChanged(old)
            if (value != RecyclerView.NO_POSITION) notifyItemChanged(value)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(position)

    inner class VH(private val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(position: Int) {
            val item = getItem(position)
            b.videoTitle.text = item.title
            b.videoMeta.text = metaLine(item)
            b.root.alpha = if (item.isPlayable) 1f else 0.5f
            b.root.isActivated = position == playingIndex
            b.videoTitle.setTypeface(
                b.videoTitle.typeface,
                if (position == playingIndex) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
            )
            b.root.setOnClickListener { onClick(position, item) }
        }

        private fun metaLine(item: VideoItem): String {
            val kind = when {
                !item.isPlayable -> "不可播放"
                item.url.contains(".m3u8") -> "HLS"
                item.url.contains(".mpd") -> "DASH"
                item.mimeType.contains("mpegurl") -> "HLS"
                else -> item.mimeType.substringAfterLast('/').uppercase(Locale.ROOT)
                    .ifBlank { "直链" }
            }
            val duration = if (item.durationMs > 0) {
                val ms = item.durationMs
                "%d:%02d".format(
                    Locale.ROOT,
                    TimeUnit.MILLISECONDS.toMinutes(ms),
                    TimeUnit.MILLISECONDS.toSeconds(ms) % 60,
                )
            } else {
                ""
            }
            return listOf(duration, kind).filter { it.isNotBlank() }.joinToString(" · ")
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<VideoItem>() {
            override fun areItemsTheSame(a: VideoItem, b: VideoItem) = a.url == b.url
            override fun areContentsTheSame(a: VideoItem, b: VideoItem) = a == b
        }
    }
}
