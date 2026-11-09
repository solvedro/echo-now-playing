package io.music_assistant.nowplaying.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.music_assistant.nowplaying.databinding.ItemLyricLineBinding
import io.music_assistant.nowplaying.model.LyricLine
import io.music_assistant.nowplaying.model.TrackLyrics

class LyricsAdapter(
    private val onLyricClicked: (LyricLine) -> Unit
) : RecyclerView.Adapter<LyricsAdapter.LyricViewHolder>() {

    private var lyrics: TrackLyrics? = null
    var activeIndex: Int = -1
        private set

    fun setLyrics(newLyrics: TrackLyrics?) {
        lyrics = newLyrics
        activeIndex = -1
        notifyDataSetChanged()
    }

    /**
     * Updates active line. Returns true if active index changed.
     */
    fun setActiveIndex(newIndex: Int): Boolean {
        if (activeIndex == newIndex) return false
        val oldIndex = activeIndex
        activeIndex = newIndex
        if (oldIndex >= 0 && oldIndex < itemCount) {
            notifyItemChanged(oldIndex)
        }
        if (newIndex >= 0 && newIndex < itemCount) {
            notifyItemChanged(newIndex)
        }
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
        val binding = ItemLyricLineBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LyricViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
        val lines = lyrics?.lines ?: return
        if (position in lines.indices) {
            val line = lines[position]
            val isActive = (position == activeIndex)
            holder.bind(line, isActive, lyrics?.isSynced == true)
        }
    }

    override fun getItemCount(): Int = lyrics?.lines?.size ?: 0

    inner class LyricViewHolder(private val binding: ItemLyricLineBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(line: LyricLine, isActive: Boolean, isSynced: Boolean) {
            binding.txtLyricLine.text = line.text

            if (isActive) {
                binding.txtLyricLine.setTextColor(Color.WHITE)
                binding.txtLyricLine.textSize = 26f
                binding.txtLyricLine.setTypeface(null, Typeface.BOLD)
                binding.txtLyricLine.alpha = 1.0f
            } else {
                binding.txtLyricLine.setTextColor(Color.parseColor("#55FFFFFF"))
                binding.txtLyricLine.textSize = 21f
                binding.txtLyricLine.setTypeface(null, Typeface.NORMAL)
                binding.txtLyricLine.alpha = 0.6f
            }

            binding.root.setOnClickListener {
                if (isSynced && line.timeSeconds >= 0) {
                    onLyricClicked(line)
                }
            }
        }
    }
}
