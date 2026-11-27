package com.orca.browser.tor

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.orca.browser.R
import com.orca.browser.databinding.DialogTorConnectBinding

class TorConnectDialog : DialogFragment() {

    private var _binding: DialogTorConnectBinding? = null
    private val binding get() = _binding!!

    var onTorConnected: (() -> Unit)? = null
    var onTorCancelled: (() -> Unit)? = null

    private var completedConnection = false
    private var cancelledExplicitly = false

    private val stateListener: (TorState) -> Unit = { state ->
        activity?.runOnUiThread {
            if (_binding == null || !isAdded) return@runOnUiThread
            when (state) {
                TorState.CONNECTING -> {
                    setControlsEnabled(false)
                    binding.layoutTorProgress.visibility = View.VISIBLE
                    binding.btnTorConnect.text = "Connecting..."
                    val progress = EmbeddedTorManager.getBootstrapProgress()
                    binding.progressTorBootstrap.progress = progress
                    binding.tvTorStatus.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.textPrimary)
                    )
                    binding.tvTorStatus.text = "Bootstrapping... $progress%"
                }

                TorState.CONNECTED -> {
                    completedConnection = true
                    binding.progressTorBootstrap.progress = 100
                    binding.tvTorStatus.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.colorTor)
                    )
                    binding.tvTorStatus.text = "Bootstrapping... 100%"
                    val callback = onTorConnected
                    dismissAllowingStateLoss()
                    callback?.invoke()
                }

                TorState.ERROR,
                TorState.NOT_SUPPORTED -> {
                    setControlsEnabled(true)
                    binding.btnTorConnect.text = "Connect"
                    binding.layoutTorProgress.visibility = View.VISIBLE
                    binding.tvTorStatus.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.colorError)
                    )
                    binding.tvTorStatus.text = EmbeddedTorManager.getLastError()
                        ?.takeIf { it.isNotBlank() }
                        ?: state.displayText
                }

                TorState.DISCONNECTED -> {
                    setControlsEnabled(true)
                    binding.btnTorConnect.text = "Connect"
                }
            }
        }
    }

    private val progressListener: (Int, String) -> Unit = { percent, statusSummary ->
        activity?.runOnUiThread {
            if (_binding == null || !isAdded) return@runOnUiThread
            binding.layoutTorProgress.visibility = View.VISIBLE
            binding.progressTorBootstrap.progress = percent.coerceIn(0, 100)
            binding.tvTorStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.textPrimary)
            )
            binding.tvTorStatus.text = if (statusSummary.isNotBlank()) {
                "Bootstrapping... $percent% ($statusSummary)"
            } else {
                "Bootstrapping... $percent%"
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            setCanceledOnTouchOutside(false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogTorConnectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (EmbeddedTorManager.isConnected()) {
            completedConnection = true
            val callback = onTorConnected
            dismissAllowingStateLoss()
            callback?.invoke()
            return
        }

        EmbeddedTorManager.addStateListener(stateListener)
        EmbeddedTorManager.addProgressListener(progressListener)

        if (EmbeddedTorManager.getState() == TorState.CONNECTING) {
            stateListener.invoke(TorState.CONNECTING)
        }

        binding.btnTorConnect.setOnClickListener {
            startTorConnection()
        }

        binding.btnTorCancel.setOnClickListener {
            cancelAndDismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val marginPx = (32 * resources.displayMetrics.density).toInt()
            val width = (resources.displayMetrics.widthPixels - marginPx).coerceAtLeast(
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onDestroyView() {
        EmbeddedTorManager.removeStateListener(stateListener)
        EmbeddedTorManager.removeProgressListener(progressListener)
        _binding = null
        super.onDestroyView()
    }

    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        handleCancellation()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        if (!completedConnection && !cancelledExplicitly) {
            handleCancellation()
        }
    }

    private fun startTorConnection() {
        val bridgeType = when (binding.rgTorBridge.checkedRadioButtonId) {
            R.id.rb_obfs4 -> "obfs4"
            R.id.rb_snowflake -> "snowflake"
            R.id.rb_meek -> "meek-azure"
            else -> "none"
        }

        setControlsEnabled(false)
        binding.btnTorConnect.text = "Connecting..."
        binding.layoutTorProgress.visibility = View.VISIBLE
        binding.progressTorBootstrap.progress = 0
        binding.tvTorStatus.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.textPrimary)
        )
        binding.tvTorStatus.text = "Bootstrapping... 0%"

        val appContext = requireContext().applicationContext
        val owner = (activity as? LifecycleOwner) ?: viewLifecycleOwner
        val scope = owner.lifecycleScope

        if (bridgeType == "none") {
            EmbeddedTorManager.start(appContext, scope, null)
        } else {
            EmbeddedTorManager.startWithBridge(appContext, scope, bridgeType)
        }
    }

    private fun cancelAndDismiss() {
        cancelledExplicitly = true
        EmbeddedTorManager.stop()
        val callback = onTorCancelled
        dismissAllowingStateLoss()
        callback?.invoke()
    }

    private fun handleCancellation() {
        cancelledExplicitly = true
        if (!completedConnection) {
            EmbeddedTorManager.stop()
            onTorCancelled?.invoke()
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.rbDirect.isEnabled = enabled
        binding.rbObfs4.isEnabled = enabled
        binding.rbSnowflake.isEnabled = enabled
        binding.rbMeek.isEnabled = enabled
        binding.btnTorConnect.isEnabled = enabled
    }

    companion object {
        const val TAG = "TorConnectDialog"

        fun newInstance(): TorConnectDialog = TorConnectDialog()
    }
}
