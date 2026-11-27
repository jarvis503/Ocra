package com.orca.browser.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.orca.browser.MainActivity
import com.orca.browser.R
import com.orca.browser.data.db.HistoryDao
import com.orca.browser.data.db.OrcaDatabase
import com.orca.browser.data.model.HistoryEntry
import com.orca.browser.data.prefs.PrefsManager
import com.orca.browser.databinding.FragmentHistoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryFragment : Fragment() {

    interface HistoryHost {
        fun onHistoryUrlClicked(url: String)
        fun onHistoryBackClicked()
    }

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var historyDao: HistoryDao
    private lateinit var prefs: PrefsManager
    private val adapter = HistoryAdapter()

    var onEntrySelected: ((String) -> Unit)? = null
    var onBackClicked: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireContext()
        historyDao = OrcaDatabase.getInstance(context).historyDao()
        prefs = PrefsManager.getInstance(context)

        setupRecyclerView()
        setupActions()
        observeHistory()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun setupRecyclerView() {
        adapter.onEntryClicked = { url ->
            val callback = onEntrySelected
            if (callback != null) {
                callback.invoke(url)
            } else {
                (parentFragment as? HistoryHost)?.onHistoryUrlClicked(url)
                    ?: (activity as? MainActivity)?.openUrlFromHistory(url)
            }
        }

        adapter.onEntryDeleted = { entry ->
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                historyDao.deleteEntry(entry)
            }
        }

        binding.rvHistory.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@HistoryFragment.adapter
        }
    }

    private fun setupActions() {
        binding.btnHistoryBack.setOnClickListener {
            val callback = onBackClicked
            if (callback != null) {
                callback.invoke()
            } else {
                (parentFragment as? HistoryHost)?.onHistoryBackClicked()
            }
        }

        binding.btnClearAll.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Clear Browsing History")
                .setMessage("Delete all saved browsing history?")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("Clear All") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        historyDao.clearAllHistory()
                    }
                }
                .show()
        }
    }

    private fun observeHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                historyDao.getAllEntries().collect { entries ->
                    renderHistory(entries)
                }
            }
        }
    }

    private fun renderHistory(entries: List<HistoryEntry>) {
        val currentBinding = _binding ?: return
        val historyEnabled = prefs.isHistoryEnabled

        if (entries.isEmpty()) {
            currentBinding.rvHistory.visibility = View.GONE
            currentBinding.historyEmpty.visibility = View.VISIBLE
            currentBinding.tvHistoryEmptyMessage.text = if (!historyEnabled) {
                "History saving is disabled. Enable it in Settings."
            } else {
                "No browsing history"
            }
            adapter.submitList(emptyList())
            return
        }

        currentBinding.historyEmpty.visibility = View.GONE
        currentBinding.rvHistory.visibility = View.VISIBLE
        adapter.submitList(buildGroupedItems(entries, historyEnabled))
    }

    private fun buildGroupedItems(
        entries: List<HistoryEntry>,
        historyEnabled: Boolean
    ): List<HistoryItem> {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStartMs = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayStartMs = calendar.timeInMillis

        val dateFormatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        val result = mutableListOf<HistoryItem>()

        if (!historyEnabled) {
            result.add(HistoryItem.DateHeader("History saving is disabled. Enable it in Settings."))
        }

        var lastHeader: String? = null
        for (entry in entries) {
            val header = when {
                entry.visitedAt >= todayStartMs -> "Today"
                entry.visitedAt >= yesterdayStartMs -> "Yesterday"
                else -> dateFormatter.format(Date(entry.visitedAt))
            }
            if (header != lastHeader) {
                result.add(HistoryItem.DateHeader(header))
                lastHeader = header
            }
            result.add(HistoryItem.Entry(entry))
        }

        return result
    }
}
