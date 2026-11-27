package com.orca.browser.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.RecyclerView
import com.orca.browser.databinding.ItemHomeShortcutBinding

class ShortcutAdapter(
    private var shortcuts: List<HomeShortcut> = HomeShortcut.DEFAULT_SHORTCUTS
) : RecyclerView.Adapter<ShortcutAdapter.ShortcutViewHolder>() {

    var onShortcutClicked: ((String) -> Unit)? = null

    fun submitShortcuts(items: List<HomeShortcut>) {
        shortcuts = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShortcutViewHolder {
        val binding = ItemHomeShortcutBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ShortcutViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ShortcutViewHolder, position: Int) {
        holder.bind(shortcuts[position])
    }

    override fun getItemCount(): Int = shortcuts.size

    inner class ShortcutViewHolder(
        private val binding: ItemHomeShortcutBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(shortcut: HomeShortcut) {
            binding.tvShortcutLetter.text = shortcut.letter
            binding.tvShortcutName.text = shortcut.name

            val circleDrawable = binding.viewShortcutCircle.background?.mutate()
            if (circleDrawable != null) {
                DrawableCompat.setTint(circleDrawable, shortcut.color)
                binding.viewShortcutCircle.background = circleDrawable
            }

            binding.root.setOnClickListener {
                onShortcutClicked?.invoke(shortcut.url)
            }
        }
    }
}
