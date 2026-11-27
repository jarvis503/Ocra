package com.orca.browser.ui.tabstrip

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.orca.browser.R
import com.orca.browser.data.model.Tab
import com.orca.browser.databinding.ItemTabChipBinding

class TabStripAdapter : ListAdapter<Tab, TabStripAdapter.TabChipViewHolder>(TabDiffCallback) {

    var onTabSelected: ((String) -> Unit)? = null
    var onTabClosed: ((String) -> Unit)? = null

    var activeTabId: String = ""
        set(value) {
            if (field == value) return
            val previousId = field
            field = value
            currentList.forEachIndexed { index, tab ->
                if (tab.id == previousId || tab.id == value) {
                    notifyItemChanged(index)
                }
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabChipViewHolder {
        val binding = ItemTabChipBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TabChipViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TabChipViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TabChipViewHolder(
        private val binding: ItemTabChipBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(tab: Tab) {
            val context = binding.root.context
            val isActive = tab.id == activeTabId

            binding.root.setBackgroundResource(
                if (isActive) R.drawable.bg_tab_chip_active else R.drawable.bg_tab_chip_inactive
            )

            binding.tvTitle.text = tab.title.ifBlank {
                tab.url.ifBlank { context.getString(R.string.new_tab) }
            }
            binding.tvTitle.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isActive) R.color.textPrimary else R.color.colorTextSecondary
                )
            )

            val faviconBytes = tab.favicon
            val decoded = if (faviconBytes != null && faviconBytes.isNotEmpty()) {
                BitmapFactory.decodeByteArray(faviconBytes, 0, faviconBytes.size)
            } else {
                null
            }

            if (decoded != null) {
                ImageViewCompat.setImageTintList(binding.ivFavicon, null)
                binding.ivFavicon.setImageBitmap(decoded)
            } else {
                binding.ivFavicon.setImageResource(R.drawable.ic_normal)
                val fallbackTint = ContextCompat.getColor(
                    context,
                    if (isActive) R.color.textPrimary else R.color.colorTextSecondary
                )
                ImageViewCompat.setImageTintList(
                    binding.ivFavicon,
                    ColorStateList.valueOf(fallbackTint)
                )
            }

            val closeTint = if (tab.isRedirect) {
                COLOR_REDIRECT_ORANGE
            } else {
                ContextCompat.getColor(context, R.color.colorTextSecondary)
            }
            binding.tvRedirectBadge.visibility = if (tab.isRedirect) View.VISIBLE else View.GONE
            ImageViewCompat.setImageTintList(binding.ivClose, ColorStateList.valueOf(closeTint))

            binding.root.setOnClickListener {
                onTabSelected?.invoke(tab.id)
            }
            binding.ivClose.setOnClickListener {
                onTabClosed?.invoke(tab.id)
            }
        }
    }

    private object TabDiffCallback : DiffUtil.ItemCallback<Tab>() {
        override fun areItemsTheSame(oldItem: Tab, newItem: Tab): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Tab, newItem: Tab): Boolean = oldItem == newItem
    }

    companion object {
        private val COLOR_REDIRECT_ORANGE = Color.parseColor("#FF9500")
    }
}
