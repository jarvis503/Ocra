package com.orca.browser.tor

enum class TorState(val displayText: String) {
    DISCONNECTED("Tor: Off"),
    CONNECTING("Connecting to Tor..."),
    CONNECTED("Connected via Tor"),
    ERROR("Tor connection failed"),
    NOT_SUPPORTED("Tor not supported on this device")
}
