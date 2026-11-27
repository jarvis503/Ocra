package com.orca.browser.ui.history

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.orca.browser.R
import com.orca.browser.data.model.HistoryEntry
import com.orca.browser.databinding.ItemHistoryDateHeaderBinding
import com.orca.browser.databinding.ItemHistoryEntryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class HistoryItem {
    data class DateHeader(val date: String) : HistoryItem()
    data class Entry(val historyEntry: HistoryEntry) : HistoryItem()
}

class HistoryAdapter : ListAdapter<HistoryItem, RecyclerView.ViewHolder>(HistoryDiffCallback) {

    var onEntryClicked: ((String) -> Unit)? = null
    var onEntryDeleted: ((HistoryEntry) -> Unit)? = null

    private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is HistoryItem.DateHeader -> VIEW_TYPE_HEADER
            is HistoryItem.Entry -> VIEW_TYPE_ENTRY
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(
                ItemHistoryDateHeaderBinding.inflate(inflater, parent, false)
            )

            else -> EntryViewHolder(
                ItemHistoryEntryBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is HistoryItem.DateHeader -> (holder as HeaderViewHolder).bind(item)
            is HistoryItem.Entry -> (holder as EntryViewHolder).bind(item.historyEntry)
        }
    }

    inner class HeaderViewHolder(
        private val binding: ItemHistoryDateHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HistoryItem.DateHeader) {
            binding.tvDateHeader.text = item.date
        }
    }

    inner class EntryViewHolder(
        private val binding: ItemHistoryEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.ivHistoryFavicon.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            binding.ivHistoryFavicon.clipToOutline = true
        }

        fun bind(entry: HistoryEntry) {
            val context = binding.root.context
            binding.tvHistoryTitle.text = entry.title.ifBlank { entry.url }
            binding.tvHistoryUrl.text = entry.url
            binding.tvHistoryTime.text = timeFormatter.format(Date(entry.visitedAt))

            val faviconBytes = entry.favicon
            val decoded = if (faviconBytes != null && faviconBytes.isNotEmpty()) {
                BitmapFactory.decodeByteArray(faviconBytes, 0, faviconBytes.size)
            } else {
                null
            }

            if (decoded != null) {
                binding.ivHistoryFavicon.setPadding(0, 0, 0, 0)
                ImageViewCompat.setImageTintList(binding.ivHistoryFavicon, null)
                binding.ivHistoryFavicon.setImageBitmap(decoded)
            } else {
                val pad = (4 * context.resources.displayMetrics.density).toInt()
                binding.ivHistoryFavicon.setPadding(pad, pad, pad, pad)
                binding.ivHistoryFavicon.setImageResource(R.drawable.ic_normal)
                val fallbackTint = ContextCompat.getColor(context, R.color.colorTextSecondary)
                ImageViewCompat.setImageTintList(
                    binding.ivHistoryFavicon,
                    ColorStateList.valueOf(fallbackTint)
                )
            }

            binding.root.setOnClickListener {
                onEntryClicked?.invoke(entry.url)
            }

            binding.btnHistoryDelete.setOnClickListener {
                onEntryDeleted?.invoke(entry)
            }
        }
    }

    private object HistoryDiffCallback : DiffUtil.ItemCallback<HistoryItem>() {
        override fun areItemsTheSame(oldItem: HistoryItem, newItem: HistoryItem): Boolean {
            return when {
                oldItem is HistoryItem.DateHeader && newItem is HistoryItem.DateHeader ->
                    oldItem.date == newItem.date
                oldItem is HistoryItem.Entry && newItem is HistoryItem.Entry ->
                    oldItem.historyEntry.id == newItem.historyEntry.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: HistoryItem, newItem: HistoryItem): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ENTRY = 1
    }
}
