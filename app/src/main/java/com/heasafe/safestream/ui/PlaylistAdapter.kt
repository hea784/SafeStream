package com.heasafe.safestream.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.heasafe.safestream.R
import com.heasafe.safestream.databinding.ItemVideoBinding
import com.heasafe.safestream.model.VideoItem
import java.util.Locale
import java.util.concurrent.TimeUnit

class PlaylistAdapter(
    private val onClick: (Int, VideoItem) -> Unit,
    /** 条目备注覆盖（播放中 / 看过），返回 null 走默认文案。 */
    private val metaOverride: ((VideoItem) -> String?)? = null,
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
            val ctx = b.root.context
            val active = position == playingIndex

            b.videoTitle.text = item.title
            b.videoMeta.text = metaLine(item)
            b.videoIndex.text = if (item.isEpisode) item.episodeNo.toString()
            else (position + 1).toString()
            b.playingBadge.visibility = if (active) View.VISIBLE else View.GONE

            val accent = ContextCompat.getColor(ctx, R.color.brand)
            val muted = ContextCompat.getColor(ctx, R.color.text_secondary)
            b.videoTitle.setTextColor(if (active) accent else
                ContextCompat.getColor(ctx, R.color.text_primary))
            b.videoMeta.setTextColor(muted)
            b.videoIndex.setTextColor(if (active) accent else muted)
            b.videoTitle.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)

            // 不可播放的条目弱化但仍可见，标明原因而不是直接消失
            b.root.alpha = if (item.isPlayable) 1f else 0.55f
            b.videoCard.setStrokeColor(
                ContextCompat.getColor(
                    ctx,
                    if (active) R.color.brand else R.color.divider,
                ),
            )
            b.root.setOnClickListener { onClick(position, item) }
        }

        private fun metaLine(item: VideoItem): String {
            metaOverride?.invoke(item)?.let { return it }
            if (item.isEpisode) return "整集"
            val kind = when {
                !item.isPlayable -> item.contextLabel()
                item.url.contains(".m3u8") -> "HLS"
                item.url.contains(".mpd") -> "DASH"
                item.mimeType.contains("mpegurl") -> "HLS"
                item.mimeType.isBlank() || item.mimeType == "video/unknown" ->
                    EXT_LABEL[item.url.substringBefore('?').substringBefore('#')
                        .substringAfterLast('.').lowercase(Locale.ROOT)] ?: "直链"
                else -> item.mimeType.substringAfterLast('/').uppercase(Locale.ROOT)
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

        private fun VideoItem.contextLabel() = "网页内播放"
    }

    private companion object {
        val EXT_LABEL = mapOf(
            "mp4" to "MP4", "m4v" to "MP4", "webm" to "WEBM",
            "mov" to "MOV", "mkv" to "MKV", "ts" to "MPEG-TS", "flv" to "FLV",
        )
        val DIFF = object : DiffUtil.ItemCallback<VideoItem>() {
            override fun areItemsTheSame(a: VideoItem, b: VideoItem) = a.url == b.url
            override fun areContentsTheSame(a: VideoItem, b: VideoItem) = a == b
        }
    }
}
