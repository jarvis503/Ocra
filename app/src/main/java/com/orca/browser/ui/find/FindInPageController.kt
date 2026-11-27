package com.orca.browser.ui.find

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import com.orca.browser.R

class FindInPageController(
    private val findBar: View,
    private val getActiveWebView: () -> WebView?
) {

    private val etFind: EditText = findBar.findViewById(R.id.et_find)
    private val tvFindCount: TextView = findBar.findViewById(R.id.tv_find_count)
    private val btnFindUp: ImageButton = findBar.findViewById(R.id.btn_find_up)
    private val btnFindDown: ImageButton = findBar.findViewById(R.id.btn_find_down)
    private val btnFindClose: ImageButton = findBar.findViewById(R.id.btn_find_close)

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSearchRunnable: Runnable? = null

    init {
        setupListeners()
    }

    fun show() {
        findBar.visibility = View.VISIBLE
        getActiveWebView()?.let { attachFindListener(it) }
        etFind.requestFocus()
        showKeyboard(etFind)

        val currentQuery = etFind.text?.toString().orEmpty()
        if (currentQuery.isNotEmpty()) {
            scheduleSearch(currentQuery)
        }
    }

    fun hide() {
        pendingSearchRunnable?.let { handler.removeCallbacks(it) }
        pendingSearchRunnable = null
        findBar.visibility = View.GONE
        etFind.text.clear()
        tvFindCount.text = ""
        hideKeyboard(etFind)
        getActiveWebView()?.clearMatches()
    }

    fun isVisible(): Boolean = findBar.visibility == View.VISIBLE

    fun attachFindListener(webView: WebView) {
        webView.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
            if (isDoneCounting) {
                tvFindCount.text = if (numberOfMatches <= 0) {
                    "0 of 0"
                } else {
                    "${activeMatchOrdinal + 1} of $numberOfMatches"
                }
            }
        }
    }

    fun release() {
        pendingSearchRunnable?.let { handler.removeCallbacks(it) }
        pendingSearchRunnable = null
    }

    private fun setupListeners() {
        etFind.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                scheduleSearch(s?.toString().orEmpty())
            }
        })

        etFind.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE
            ) {
                getActiveWebView()?.findNext(true)
                true
            } else {
                false
            }
        }

        btnFindUp.setOnClickListener {
            getActiveWebView()?.findNext(false)
        }

        btnFindDown.setOnClickListener {
            getActiveWebView()?.findNext(true)
        }

        btnFindClose.setOnClickListener {
            hide()
        }
    }

    private fun scheduleSearch(query: String) {
        pendingSearchRunnable?.let { handler.removeCallbacks(it) }
        if (query.isEmpty()) {
            tvFindCount.text = ""
            getActiveWebView()?.clearMatches()
            return
        }

        val runnable = Runnable {
            val webView = getActiveWebView() ?: return@Runnable
            attachFindListener(webView)
            webView.findAllAsync(query)
        }
        pendingSearchRunnable = runnable
        handler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
    }

    private fun showKeyboard(target: View) {
        val imm = target.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        target.post {
            imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard(target: View) {
        val imm = target.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        imm.hideSoftInputFromWindow(target.windowToken, 0)
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
    }
}
