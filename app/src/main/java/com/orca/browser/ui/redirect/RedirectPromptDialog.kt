package com.orca.browser.ui.redirect

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.orca.browser.databinding.DialogRedirectPromptBinding

class RedirectPromptDialog : DialogFragment() {

    private var _binding: DialogRedirectPromptBinding? = null
    private val binding get() = _binding!!

    private var countdownTimer: CountDownTimer? = null
    private var userDecision: Boolean? = null

    var onDecision: ((allowed: Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            setCanceledOnTouchOutside(false)
            setCancelable(false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogRedirectPromptBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val targetUrl = arguments?.getString(ARG_TARGET_URL).orEmpty()
        val sourceDomain = arguments?.getString(ARG_SOURCE_DOMAIN)
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"

        binding.tvRedirectUrl.text = formatDisplayUrl(targetUrl)
        binding.tvSourceDomain.text = "From: $sourceDomain"
        binding.progressCountdown.progress = 100
        binding.tvCountdown.text = "Auto-blocking in 4s"

        binding.btnAllow.setOnClickListener {
            resolveDecision(allowed = true)
        }

        binding.btnBlock.setOnClickListener {
            resolveDecision(allowed = false)
        }

        startAutoBlockCountdown()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.65f)
            val widthPx = (320 * resources.displayMetrics.density).toInt()
            setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        countdownTimer?.cancel()
        if (userDecision == null) {
            userDecision = false
            onDecision?.invoke(false)
        }
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        countdownTimer?.cancel()
        countdownTimer = null
        _binding = null
        super.onDestroyView()
    }

    private fun startAutoBlockCountdown() {
        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(AUTO_BLOCK_TIMEOUT_MS, COUNTDOWN_TICK_MS) {
            override fun onTick(millisUntilFinished: Long) {
                if (_binding == null || userDecision != null) return
                val secondsLeft = ((millisUntilFinished / 1000L) + 1L).coerceIn(1L, 4L)
                binding.tvCountdown.text = "Auto-blocking in ${secondsLeft}s"
                binding.progressCountdown.progress =
                    ((millisUntilFinished * 100L) / AUTO_BLOCK_TIMEOUT_MS).toInt().coerceIn(0, 100)
            }

            override fun onFinish() {
                if (userDecision != null) return
                _binding?.let {
                    it.progressCountdown.progress = 0
                    it.tvCountdown.text = "Auto-blocking in 0s"
                }
                userDecision = false
                val callback = onDecision
                dismissAllowingStateLoss()
                callback?.invoke(false)
            }
        }.start()
    }

    private fun resolveDecision(allowed: Boolean) {
        if (userDecision != null) return
        countdownTimer?.cancel()
        userDecision = allowed
        val callback = onDecision
        dismissAllowingStateLoss()
        callback?.invoke(allowed)
    }

    private fun formatDisplayUrl(rawUrl: String): String {
        if (rawUrl.isBlank()) return ""
        val parsedHost = try {
            Uri.parse(rawUrl).host
        } catch (_: Exception) {
            null
        }
        return if (rawUrl.length <= MAX_DISPLAY_URL_CHARS) {
            rawUrl
        } else if (!parsedHost.isNullOrBlank()) {
            "$parsedHost — ${rawUrl.take(MAX_DISPLAY_URL_CHARS)}..."
        } else {
            "${rawUrl.take(MAX_DISPLAY_URL_CHARS)}..."
        }
    }

    companion object {
        const val TAG = "redirect_prompt"
        private const val ARG_TARGET_URL = "arg_target_url"
        private const val ARG_SOURCE_DOMAIN = "arg_source_domain"
        private const val AUTO_BLOCK_TIMEOUT_MS = 4000L
        private const val COUNTDOWN_TICK_MS = 100L
        private const val MAX_DISPLAY_URL_CHARS = 120

        fun newInstance(targetUrl: String, sourceDomain: String): RedirectPromptDialog {
            return RedirectPromptDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TARGET_URL, targetUrl)
                    putString(ARG_SOURCE_DOMAIN, sourceDomain)
                }
            }
        }
    }
}
