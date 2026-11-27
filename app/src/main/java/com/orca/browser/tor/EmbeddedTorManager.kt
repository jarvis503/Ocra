package com.orca.browser.tor

import IPtProxy.Controller
import IPtProxy.IPtProxy as PtConstants
import IPtProxy.OnTransportEvents
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.orca.browser.data.prefs.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.freehaven.tor.control.RawEventListener
import net.freehaven.tor.control.TorControlCommands
import net.freehaven.tor.control.TorControlConnection
import org.torproject.jni.TorService
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.coroutines.cancellation.CancellationException

object EmbeddedTorManager {

    private const val TAG = "EmbeddedTorManager"
    private const val LOCALHOST = "127.0.0.1"
    private const val BOOTSTRAP_TIMEOUT_MS = 45_000L
    private const val POLL_INTERVAL_MS = 300L
    private const val DEFAULT_SNOWFLAKE_BROKER = "https://snowflake-broker.torproject.net/"
    private const val DEFAULT_SNOWFLAKE_FRONT = "www.cdn77.org"
    private const val DEFAULT_SNOWFLAKE_ICE =
        "stun:stun.l.google.com:19302,stun:stun.ekiga.net:3478"

    data class TorBridge(
        val type: String,
        val address: String
    )

    private var torProcess: Process? = null
    private var socksPort: Int = 0
    private var controlPort: Int = 0

    @Volatile
    private var state: TorState = TorState.DISCONNECTED

    @Volatile
    private var bootstrapProgress: Int = 0

    @Volatile
    private var lastErrorMessage: String? = null

    var onStateChanged: ((TorState) -> Unit)? = null
    var onProgressChanged: ((Int, String) -> Unit)? = null

    private var currentBridge: TorBridge? = null

    private val stateListeners = CopyOnWriteArraySet<(TorState) -> Unit>()
    private val progressListeners = CopyOnWriteArraySet<(Int, String) -> Unit>()

    private val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    private val controlProgressRegex = Regex("""PROGRESS=(\d+)""")
    private val controlSummaryRegex = Regex("""SUMMARY="?([^"\r\n]+)"?""")
    private val stdoutBootstrapRegex = Regex("""Bootstrapped\s+(\d+)%""")

    private var appContext: Context? = null
    private var bootstrapJob: Job? = null

    private var torService: TorService? = null
    private var serviceBound = false
    private var statusReceiverRegistered = false
    private var attachedControlConnection: TorControlConnection? = null
    private var controlEventListener: RawEventListener? = null

    private var ptController: Controller? = null
    private var activePtTransport: String? = null

    @Volatile
    private var serviceErrorFromBroadcast: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? TorService.LocalBinder
            torService = binder?.service
            serviceBound = torService != null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            torService = null
            serviceBound = false
        }
    }

    private val torStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                TorService.ACTION_ERROR -> {
                    val errorMsg = intent.getStringExtra(Intent.EXTRA_TEXT)
                        ?: intent.getStringExtra(TorService.EXTRA_STATUS)
                        ?: "Embedded Tor service reported an error"
                    serviceErrorFromBroadcast = errorMsg
                    Log.e(TAG, "TorService error: $errorMsg")
                }

                TorService.ACTION_STATUS -> {
                    val serviceStatus = intent.getStringExtra(TorService.EXTRA_STATUS)
                    Log.d(TAG, "TorService status: $serviceStatus")
                    if (serviceStatus == TorService.STATUS_ON &&
                        state == TorState.CONNECTING &&
                        bootstrapProgress >= 100
                    ) {
                        torService?.getSocksPort()?.takeIf { it > 0 }?.let { port ->
                            socksPort = port
                        }
                    }
                }
            }
        }
    }

    fun startWithBridge(context: Context, scope: CoroutineScope, bridgeType: String) {
        val bridge = if (bridgeType.isBlank() || bridgeType.equals("none", ignoreCase = true)) {
            null
        } else {
            val firstBridge = TorBridgeManager.getBridgesForType(bridgeType).firstOrNull().orEmpty()
            if (firstBridge.isEmpty()) {
                lastErrorMessage = "No bridges configured for $bridgeType"
                updateState(TorState.ERROR)
                return
            }
            TorBridge(bridgeType, firstBridge)
        }
        start(context, scope, bridge)
    }

    fun start(context: Context, scope: CoroutineScope, bridge: TorBridge? = null) {
        val ctx = context.applicationContext
        appContext = ctx

        if (state == TorState.CONNECTED) {
            onStateChanged?.invoke(TorState.CONNECTED)
            stateListeners.forEach { it.invoke(TorState.CONNECTED) }
            return
        }

        if (state == TorState.CONNECTING) {
            stopInternal(notifyDisconnected = false)
        }

        val abis = Build.SUPPORTED_ABIS ?: emptyArray()
        if (abis.none { it in supportedAbis }) {
            lastErrorMessage = TorState.NOT_SUPPORTED.displayText
            updateState(TorState.NOT_SUPPORTED)
            return
        }

        currentBridge = bridge
        bootstrapProgress = 0
        lastErrorMessage = null
        serviceErrorFromBroadcast = null
        updateState(TorState.CONNECTING)
        notifyProgress(0, "Initializing Tor")

        bootstrapJob = scope.launch(Dispatchers.IO) {
            try {
                val torDir = File(ctx.filesDir, "tor").apply { mkdirs() }
                val dataDir = File(torDir, "data").apply { mkdirs() }
                socksPort = findFreePort()
                controlPort = findFreePort()

                val savedCustomLines = if (bridge == null) {
                    parseCustomBridgeLines(PrefsManager.getInstance(ctx).torBridges)
                } else {
                    emptyList()
                }
                val bridgeLines = if (bridge != null) {
                    resolveBridgeLines(bridge)
                } else {
                    savedCustomLines
                }
                val ptTransportName = bridge?.let {
                    TorBridgeManager.toPluggableTransportName(it.type)
                } ?: inferPluggableTransportFromLines(savedCustomLines)
                val ptPort = if (ptTransportName != null) {
                    startPluggableTransport(ctx, ptTransportName)
                } else {
                    0
                }

                val standaloneBinary = extractTorBinary(ctx)

                val connected = if (standaloneBinary != null) {
                    val standaloneTorrc = File(torDir, "torrc")
                    writeStandaloneTorrc(
                        torrcFile = standaloneTorrc,
                        torDir = torDir,
                        dataDir = dataDir,
                        socks = socksPort,
                        control = controlPort,
                        ptTransport = ptTransportName,
                        ptPort = ptPort,
                        bridgeLines = bridgeLines
                    )
                    runStandaloneTorWithFallback(
                        ctx = ctx,
                        torBinary = standaloneBinary,
                        torrcFile = standaloneTorrc,
                        ptTransport = ptTransportName,
                        ptPort = ptPort,
                        bridgeLines = bridgeLines
                    )
                } else {
                    runBundledTorService(
                        ctx = ctx,
                        ptTransport = ptTransportName,
                        ptPort = ptPort,
                        bridgeLines = bridgeLines
                    )
                }

                if (!isActive || state != TorState.CONNECTING) return@launch

                if (connected) {
                    torService?.getSocksPort()?.takeIf { it > 0 }?.let { resolvedPort ->
                        socksPort = resolvedPort
                    }
                    withContext(Dispatchers.Main) {
                        WebViewProxyUtil.applyProxy(null, LOCALHOST, socksPort)
                    }
                    bootstrapProgress = 100
                    notifyProgress(100, "Connected")
                    updateState(TorState.CONNECTED)
                } else {
                    val reason = lastErrorMessage ?: "Timed out bootstrapping Tor (45s)"
                    lastErrorMessage = reason
                    stopInternal(notifyDisconnected = false)
                    updateState(TorState.ERROR)
                }
            } catch (_: CancellationException) {
                // Cancelled cleanly during stop()
            } catch (error: UnsatisfiedLinkError) {
                Log.e(TAG, "Unsupported native ABI for embedded Tor", error)
                lastErrorMessage = TorState.NOT_SUPPORTED.displayText
                stopInternal(notifyDisconnected = false)
                updateState(TorState.NOT_SUPPORTED)
            } catch (error: Exception) {
                Log.e(TAG, "Failed to start embedded Tor", error)
                lastErrorMessage = error.message ?: TorState.ERROR.displayText
                stopInternal(notifyDisconnected = false)
                updateState(TorState.ERROR)
            }
        }
    }

    fun stop() {
        lastErrorMessage = null
        stopInternal(notifyDisconnected = true)
    }

    fun getState(): TorState = state

    fun getSocksPort(): Int = socksPort

    fun isConnected(): Boolean = state == TorState.CONNECTED

    fun getBootstrapProgress(): Int = bootstrapProgress

    fun getLastError(): String? = lastErrorMessage

    fun getCurrentBridge(): TorBridge? = currentBridge

    fun validateBridges(text: String): String? {
        for (raw in text.lines()) {
            val cleaned = raw.trim().let { line ->
                if (line.startsWith("Bridge ", ignoreCase = true)) line.substring(7).trim() else line
            }
            if (cleaned.isEmpty() || cleaned.startsWith("#")) continue
            val firstToken = cleaned.substringBefore(' ').lowercase()
            val supportedPt = firstToken in setOf("obfs4", "meek_lite", "snowflake")
            val vanillaBridge =
                Regex("""^(\[[0-9a-fA-F:]+\]|[0-9]{1,3}(\.[0-9]{1,3}){3}):\d{1,5}(\s+[0-9A-Fa-f]{40})?\s*$""")
                    .matches(cleaned)
            if (!supportedPt && !vanillaBridge) {
                return "Unrecognized bridge line:\n$cleaned"
            }
        }
        return null
    }

    fun addStateListener(listener: (TorState) -> Unit) {
        stateListeners.add(listener)
    }

    fun removeStateListener(listener: (TorState) -> Unit) {
        stateListeners.remove(listener)
    }

    fun addProgressListener(listener: (Int, String) -> Unit) {
        progressListeners.add(listener)
    }

    fun removeProgressListener(listener: (Int, String) -> Unit) {
        progressListeners.remove(listener)
    }

    private fun stopInternal(notifyDisconnected: Boolean) {
        bootstrapJob?.cancel()
        bootstrapJob = null

        detachControlListener()

        torProcess?.let { process ->
            try {
                process.destroy()
            } catch (_: Exception) {
                // Process already terminated
            }
        }
        torProcess = null

        val ctx = appContext
        if (ctx != null) {
            unregisterStatusReceiver(ctx)
            shutdownTorService(ctx)
            cleanupTempFiles(ctx)
        }

        stopPluggableTransport()

        socksPort = 0
        controlPort = 0
        bootstrapProgress = 0
        serviceErrorFromBroadcast = null

        try {
            WebViewProxyUtil.clearProxy()
        } catch (_: Exception) {
            // Ignore proxy clear failure outside main looper during shutdown
        }

        if (notifyDisconnected && state != TorState.DISCONNECTED) {
            updateState(TorState.DISCONNECTED)
        }
    }

    private suspend fun runStandaloneTorWithFallback(
        ctx: Context,
        torBinary: File,
        torrcFile: File,
        ptTransport: String?,
        ptPort: Int,
        bridgeLines: List<String>
    ): Boolean {
        val process = try {
            Runtime.getRuntime().exec(
                arrayOf(torBinary.absolutePath, "-f", torrcFile.absolutePath)
            )
        } catch (error: Exception) {
            Log.w(TAG, "Standalone tor binary exec unavailable (${error.message}); using TorService")
            return runBundledTorService(ctx, ptTransport, ptPort, bridgeLines)
        }

        torProcess = process
        val startTime = System.currentTimeMillis()
        val reader = BufferedReader(InputStreamReader(process.inputStream))

        while (System.currentTimeMillis() - startTime < BOOTSTRAP_TIMEOUT_MS &&
            state == TorState.CONNECTING
        ) {
            while (reader.ready()) {
                val line = reader.readLine() ?: break
                parseBootstrapLine(line)
                if (bootstrapProgress >= 100) {
                    return true
                }
            }

            if (!isProcessAlive(process)) {
                val errorOutput = try {
                    process.errorStream.bufferedReader().readText().trim()
                } catch (_: Exception) {
                    ""
                }
                if (errorOutput.isNotBlank()) {
                    Log.w(TAG, "Standalone binary exited: $errorOutput")
                }
                return false
            }

            delay(POLL_INTERVAL_MS)
        }

        return bootstrapProgress >= 100
    }

    private suspend fun runBundledTorService(
        ctx: Context,
        ptTransport: String?,
        ptPort: Int,
        bridgeLines: List<String>
    ): Boolean {
        val torrcFile = TorService.getTorrc(ctx)
        torrcFile.parentFile?.mkdirs()
        writeServiceTorrc(
            torrcFile = torrcFile,
            socks = socksPort,
            ptTransport = ptTransport,
            ptPort = ptPort,
            bridgeLines = bridgeLines
        )

        registerStatusReceiver(ctx)

        val startIntent = Intent(ctx, TorService::class.java).apply {
            action = TorService.ACTION_START
        }
        try {
            ctx.startService(startIntent)
            serviceBound = ctx.bindService(startIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (error: Exception) {
            lastErrorMessage = error.message ?: "Unable to start embedded TorService"
            return false
        }

        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < BOOTSTRAP_TIMEOUT_MS &&
            state == TorState.CONNECTING
        ) {
            val broadcastErr = serviceErrorFromBroadcast
            if (!broadcastErr.isNullOrBlank()) {
                lastErrorMessage = broadcastErr
                return false
            }

            val controlConn = torService?.getTorControlConnection()
            if (controlConn != null) {
                attachControlListener(controlConn)
                try {
                    val phase = controlConn.getInfo("status/bootstrap-phase")
                    if (!phase.isNullOrBlank()) {
                        parseBootstrapLine(phase)
                    }
                } catch (_: Exception) {
                    // Control connection still stabilizing
                }
            }

            if (bootstrapProgress >= 100) {
                return true
            }

            delay(POLL_INTERVAL_MS)
        }

        if (bootstrapProgress < 100) {
            lastErrorMessage = "Connection timed out after 45 seconds"
        }
        return bootstrapProgress >= 100
    }

    private fun startPluggableTransport(ctx: Context, ptTransportName: String): Int {
        val ptStateDir = File(ctx.filesDir, "tor/pt_state").apply { mkdirs() }.absolutePath
        val controller = ptController ?: Controller(
            ptStateDir,
            true,
            false,
            "WARN",
            object : OnTransportEvents {
                override fun connected(name: String?) {
                    Log.d(TAG, "Pluggable transport connected: $name")
                }

                override fun error(name: String?, error: Exception?) {
                    Log.w(TAG, "Pluggable transport error ($name): ${error?.message}")
                }

                override fun stopped(name: String?, error: Exception?) {
                    if (error != null) {
                        Log.w(TAG, "Pluggable transport stopped ($name): ${error.message}")
                    }
                }
            }
        ).also { ptController = it }

        if (ptTransportName == PtConstants.Snowflake) {
            controller.setSnowflakeIceServers(DEFAULT_SNOWFLAKE_ICE)
            controller.setSnowflakeBrokerUrl(DEFAULT_SNOWFLAKE_BROKER)
            controller.setSnowflakeFrontDomains(DEFAULT_SNOWFLAKE_FRONT)
            controller.setSnowflakeMaxPeers(1L)
        }

        controller.start(ptTransportName, "")
        activePtTransport = ptTransportName

        val assignedPort = controller.port(ptTransportName).toInt()
        if (assignedPort <= 0) {
            throw IllegalStateException("Failed to open local port for $ptTransportName transport")
        }
        return assignedPort
    }

    private fun stopPluggableTransport() {
        val transport = activePtTransport ?: return
        activePtTransport = null
        try {
            ptController?.stop(transport)
        } catch (_: Exception) {
            // Transport already stopped
        }
    }

    private fun resolveBridgeLines(bridge: TorBridge?): List<String> {
        if (bridge == null) return emptyList()
        val preset = TorBridgeManager.getBridgesForType(bridge.type)
        return when {
            preset.contains(bridge.address) -> preset
            bridge.address.isNotBlank() -> listOf(bridge.address)
            else -> preset
        }
    }

    private fun parseCustomBridgeLines(rawText: String): List<String> {
        return rawText.lines()
            .map { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("Bridge ", ignoreCase = true)) {
                    trimmed.substring(7).trim()
                } else {
                    trimmed
                }
            }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun inferPluggableTransportFromLines(lines: List<String>): String? {
        for (line in lines) {
            when (line.substringBefore(' ').lowercase()) {
                "obfs4" -> return PtConstants.Obfs4
                "meek_lite", "meek-azure" -> return PtConstants.MeekLite
                "snowflake" -> return PtConstants.Snowflake
            }
        }
        return null
    }

    private fun writeStandaloneTorrc(
        torrcFile: File,
        torDir: File,
        dataDir: File,
        socks: Int,
        control: Int,
        ptTransport: String?,
        ptPort: Int,
        bridgeLines: List<String>
    ) {
        val sb = StringBuilder()
        sb.appendLine("SocksPort $socks")
        sb.appendLine("ControlPort $control")
        sb.appendLine("DataDirectory ${dataDir.absolutePath}")
        val geoIp = File(torDir, "geoip")
        if (geoIp.exists()) sb.appendLine("GeoIPFile ${geoIp.absolutePath}")
        val geoIp6 = File(torDir, "geoip6")
        if (geoIp6.exists()) sb.appendLine("GeoIPv6File ${geoIp6.absolutePath}")
        sb.appendLine("AvoidDiskWrites 1")
        sb.appendLine("SafeLogging 1")

        if (bridgeLines.isNotEmpty()) {
            sb.appendLine("UseBridges 1")
            if (ptTransport != null && ptPort > 0) {
                val obfsBinary = File(torDir, "obfs4proxy")
                if (ptTransport == PtConstants.Obfs4 && obfsBinary.exists() && obfsBinary.canExecute()) {
                    sb.appendLine("ClientTransportPlugin obfs4 exec ${obfsBinary.absolutePath}")
                } else {
                    sb.appendLine("ClientTransportPlugin $ptTransport socks5 $LOCALHOST:$ptPort")
                }
            }
            for (line in bridgeLines) {
                sb.appendLine("Bridge $line")
            }
        }

        torrcFile.writeText(sb.toString())
    }

    private fun writeServiceTorrc(
        torrcFile: File,
        socks: Int,
        ptTransport: String?,
        ptPort: Int,
        bridgeLines: List<String>
    ) {
        val sb = StringBuilder()
        sb.appendLine("SocksPort $socks")
        sb.appendLine("AvoidDiskWrites 1")
        sb.appendLine("SafeLogging 1")
        sb.appendLine("CircuitBuildTimeout 20")
        sb.appendLine("LearnCircuitBuildTimeout 0")

        if (bridgeLines.isNotEmpty()) {
            sb.appendLine("UseBridges 1")
            if (ptTransport != null && ptPort > 0) {
                sb.appendLine("ClientTransportPlugin $ptTransport socks5 $LOCALHOST:$ptPort")
            }
            for (line in bridgeLines) {
                sb.appendLine("Bridge $line")
            }
        }

        torrcFile.writeText(sb.toString())
    }

    private fun attachControlListener(controlConnection: TorControlConnection) {
        if (attachedControlConnection === controlConnection) return
        detachControlListener()

        val listener = object : RawEventListener {
            override fun onEvent(keyword: String?, data: String?) {
                val safeData = data.orEmpty()
                if (keyword == TorControlCommands.EVENT_STATUS_CLIENT ||
                    keyword == TorControlCommands.EVENT_NOTICE_MSG ||
                    safeData.contains("BOOTSTRAP", ignoreCase = true) ||
                    safeData.contains("Bootstrapped", ignoreCase = true)
                ) {
                    parseBootstrapLine(safeData)
                }
            }
        }

        attachedControlConnection = controlConnection
        controlEventListener = listener
        try {
            controlConnection.addRawEventListener(listener)
            controlConnection.setEvents(
                listOf(
                    TorControlCommands.EVENT_STATUS_CLIENT,
                    TorControlCommands.EVENT_NOTICE_MSG
                )
            )
        } catch (error: Exception) {
            Log.w(TAG, "Could not attach Tor control event listener: ${error.message}")
        }
    }

    private fun detachControlListener() {
        val conn = attachedControlConnection
        val listener = controlEventListener
        if (conn != null && listener != null) {
            try {
                conn.removeRawEventListener(listener)
            } catch (_: Exception) {
                // Ignore during shutdown
            }
        }
        attachedControlConnection = null
        controlEventListener = null
    }

    private fun parseBootstrapLine(line: String) {
        val controlMatch = controlProgressRegex.find(line)
        val stdoutMatch = stdoutBootstrapRegex.find(line)
        val progress = (controlMatch ?: stdoutMatch)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.coerceIn(0, 100)
            ?: return

        val summary = controlSummaryRegex.find(line)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()

        if (progress >= bootstrapProgress) {
            bootstrapProgress = progress
            notifyProgress(progress, summary)
        }
    }

    private fun registerStatusReceiver(ctx: Context) {
        if (statusReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(TorService.ACTION_STATUS)
            addAction(TorService.ACTION_ERROR)
        }
        LocalBroadcastManager.getInstance(ctx).registerReceiver(torStatusReceiver, filter)
        statusReceiverRegistered = true
    }

    private fun unregisterStatusReceiver(ctx: Context) {
        if (!statusReceiverRegistered) return
        try {
            LocalBroadcastManager.getInstance(ctx).unregisterReceiver(torStatusReceiver)
        } catch (_: Exception) {
            // Receiver not registered
        }
        statusReceiverRegistered = false
    }

    private fun shutdownTorService(ctx: Context) {
        try {
            torService?.torControlConnection?.shutdownTor("SHUTDOWN")
        } catch (_: Exception) {
            // Control socket already closed
        }
        if (serviceBound) {
            try {
                ctx.unbindService(serviceConnection)
            } catch (_: Exception) {
                // Not bound
            }
            serviceBound = false
        }
        torService = null
        try {
            ctx.stopService(Intent(ctx, TorService::class.java))
        } catch (_: Exception) {
            // Service not running
        }
    }

    private fun cleanupTempFiles(ctx: Context) {
        val torDir = File(ctx.filesDir, "tor")
        File(torDir, "torrc").delete()
        File(torDir, "control_auth_cookie").delete()
        try {
            TorService.getTorrc(ctx).delete()
        } catch (_: Exception) {
            // Ignore cleanup errors
        }
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            return socket.localPort
        }
    }

    private fun extractTorBinary(context: Context): File? {
        val torDir = File(context.filesDir, "tor").apply { mkdirs() }
        val targetFile = File(torDir, "tor")
        if (targetFile.exists() && targetFile.canExecute()) {
            return targetFile
        }

        val assetFiles = try {
            context.assets.list("tor").orEmpty()
        } catch (_: Exception) {
            emptyArray()
        }
        if (assetFiles.isEmpty()) return null

        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull { it in supportedAbis } ?: return null
        val assetName = "tor.$primaryAbi"
        if (!assetFiles.contains(assetName)) return null

        return try {
            context.assets.open("tor/$assetName").use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            targetFile.setExecutable(true)
            targetFile
        } catch (error: Exception) {
            Log.w(TAG, "Failed extracting asset tor/$assetName: ${error.message}")
            null
        }
    }

    private fun isProcessAlive(process: Process): Boolean {
        return try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    private fun updateState(newState: TorState) {
        if (state == newState && newState != TorState.ERROR) return
        state = newState
        onStateChanged?.invoke(newState)
        stateListeners.forEach { it.invoke(newState) }
    }

    private fun notifyProgress(progress: Int, summary: String) {
        onProgressChanged?.invoke(progress, summary)
        progressListeners.forEach { it.invoke(progress, summary) }
    }
}
