package com.orca.browser.ui.overview

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
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
import com.orca.browser.databinding.ItemTabCardBinding
import kotlin.math.abs

class TabCardAdapter : ListAdapter<Tab, TabCardAdapter.TabCardViewHolder>(TabCardDiffCallback) {

    var onTabClicked: ((String) -> Unit)? = null
    var onTabClosed: ((String) -> Unit)? = null

    var activeTabId: String = ""
        set(value) {
            if (field == value) return
            val previous = field
            field = value
            currentList.forEachIndexed { index, tab ->
                if (tab.id == previous || tab.id == value) {
                    notifyItemChanged(index)
                }
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabCardViewHolder {
        val binding = ItemTabCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TabCardViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TabCardViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TabCardViewHolder(
        private val binding: ItemTabCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(tab: Tab) {
            val context = binding.root.context
            val isActive = tab.id == activeTabId || (activeTabId.isEmpty() && tab.isActive)

            val strokeColor = ContextCompat.getColor(
                context,
                if (isActive) R.color.colorAccent else R.color.colorDivider
            )
            binding.cardRoot.strokeColor = strokeColor
            binding.cardRoot.strokeWidth = if (isActive) {
                (2 * context.resources.displayMetrics.density).toInt()
            } else {
                (1 * context.resources.displayMetrics.density).toInt()
            }

            val domain = extractDomain(tab.url)
            binding.cardPreview.setBackgroundColor(colorForDomain(domain))
            binding.tvCardPreviewHost.text = if (tab.url == "about:blank" || domain.isBlank()) {
                context.getString(R.string.app_name)
            } else {
                domain
            }

            binding.tvCardTitle.text = tab.title.ifBlank {
                tab.url.ifBlank { context.getString(R.string.new_tab) }
            }
            binding.tvCardRedirect.visibility = if (tab.isRedirect) View.VISIBLE else View.GONE

            val showRedirectSubtitle = tab.isRedirect && tab.sourceTabId != null
            binding.tvCardRedirectSubtitle.visibility = if (showRedirectSubtitle) {
                View.VISIBLE
            } else {
                View.GONE
            }

            val closeTint = if (tab.isRedirect) {
                COLOR_REDIRECT_ORANGE
            } else {
                ContextCompat.getColor(context, R.color.textPrimary)
            }
            ImageViewCompat.setImageTintList(binding.btnCardClose, ColorStateList.valueOf(closeTint))

            val faviconBytes = tab.favicon
            val decoded = if (faviconBytes != null && faviconBytes.isNotEmpty()) {
                BitmapFactory.decodeByteArray(faviconBytes, 0, faviconBytes.size)
            } else {
                null
            }

            if (decoded != null) {
                ImageViewCompat.setImageTintList(binding.ivCardFavicon, null)
                binding.ivCardFavicon.setImageBitmap(decoded)
            } else {
                binding.ivCardFavicon.setImageResource(R.drawable.ic_normal)
                val fallbackColor = ContextCompat.getColor(context, R.color.colorTextSecondary)
                ImageViewCompat.setImageTintList(
                    binding.ivCardFavicon,
                    ColorStateList.valueOf(fallbackColor)
                )
            }

            binding.root.setOnClickListener {
                onTabClicked?.invoke(tab.id)
            }

            binding.btnCardClose.setOnClickListener {
                onTabClosed?.invoke(tab.id)
            }
        }
    }

    private object TabCardDiffCallback : DiffUtil.ItemCallback<Tab>() {
        override fun areItemsTheSame(oldItem: Tab, newItem: Tab): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Tab, newItem: Tab): Boolean = oldItem == newItem
    }

    companion object {
        private val COLOR_REDIRECT_ORANGE = Color.parseColor("#FF9500")

        private val PREVIEW_PALETTE = intArrayOf(
            Color.parseColor("#1A2A3A"),
            Color.parseColor("#2A1A1A"),
            Color.parseColor("#1A2A1A"),
            Color.parseColor("#241A2E"),
            Color.parseColor("#2A241A"),
            Color.parseColor("#1A262A"),
            Color.parseColor("#2A1A26"),
            Color.parseColor("#1E222E"),
            Color.parseColor("#24281E"),
            Color.parseColor("#201E28")
        )

        private fun extractDomain(url: String): String {
            if (url.isBlank() || url == "about:blank") return ""
            return Uri.parse(url).host?.removePrefix("www.") ?: url
        }

        private fun colorForDomain(domain: String): Int {
            if (domain.isBlank()) return PREVIEW_PALETTE[0]
            val index = abs(domain.hashCode()) % PREVIEW_PALETTE.size
            return PREVIEW_PALETTE[index]
        }
    }
}
