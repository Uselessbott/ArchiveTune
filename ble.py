#!/usr/bin/env python3
"""
Phase 8 - Step 1: Connection State Reporting Only
Set DRY_RUN = True to preview; False to apply changes.
"""
import os
import shutil
from pathlib import Path

DRY_RUN = True   # ← change to False to actually modify files

BASE = Path("app/src/main/kotlin/moe/rukamori/archivetune")
BACKUP_SUFFIX = ".bak"

def backup_file(rel_path):
    full = BASE / rel_path
    if full.exists():
        backup = full.with_suffix(full.suffix + BACKUP_SUFFIX)
        shutil.copy2(full, backup)
        print(f"Backup created: {backup}")

def replace_in_file(rel_path, old, new, required=True):
    full = BASE / rel_path
    if not full.exists():
        print(f"Warning: {full} does not exist, skipping.")
        return False
    with open(full, "r") as f:
        content = f.read()
    if old not in content:
        if required:
            print(f"ERROR: Exact text not found in {full}")
            return False
        else:
            print(f"Warning: exact text not found in {full}")
            return True
    new_content = content.replace(old, new)
    if DRY_RUN:
        print(f"[DRY RUN] Would modify {full} (exact replacement)")
        return True
    backup_file(rel_path)
    with open(full, "w") as f:
        f.write(new_content)
    print(f"Modified: {full}")
    return True

# ==========================================================================
# Step 1: Add connection state enum and failure flow to WebRtcTransport.kt
# ==========================================================================

# 1. Add import for StateFlow (if not already present)
replace_in_file(
    "together/webrtc/WebRtcTransport.kt",
    "import kotlinx.coroutines.flow.MutableStateFlow",
    "import kotlinx.coroutines.flow.MutableStateFlow\nimport kotlinx.coroutines.flow.StateFlow"
)

# 2. Add WebRtcConnectionState enum after imports (before class)
replace_in_file(
    "together/webrtc/WebRtcTransport.kt",
    "import javax.inject.Singleton",
    """import javax.inject.Singleton

/**
 * Represents the overall connection state of the WebRTC transport.
 * This is more granular than DataChannel.State and includes lifecycle phases.
 */
enum class WebRtcConnectionState {
    /** Initial state, no connection attempted */
    IDLE,
    /** Actively trying to establish a connection */
    CONNECTING,
    /** Connection established and DataChannel is open */
    CONNECTED,
    /** Connection was lost unexpectedly */
    DISCONNECTED,
    /** Connection permanently closed */
    CLOSED
}

/**
 * Reason for connection failure, used by reconnect logic.
 */
enum class ConnectionFailureReason {
    /** ICE connection was disconnected (transient) */
    ICE_DISCONNECTED,
    /** ICE connection failed permanently */
    ICE_FAILED,
    /** DataChannel closed unexpectedly */
    DATA_CHANNEL_CLOSED
}"""
)

# 3. Add webRtcConnectionState flow after _connectionState
replace_in_file(
    "together/webrtc/WebRtcTransport.kt",
    "    private val _connectionState = MutableStateFlow<DataChannel.State?>(null)\n    val connectionState: StateFlow<DataChannel.State?> = _connectionState.asStateFlow()",
    """    private val _connectionState = MutableStateFlow<DataChannel.State?>(null)
    val connectionState: StateFlow<DataChannel.State?> = _connectionState.asStateFlow()

    // Higher-level connection state for reconnect logic
    private val _webRtcConnectionState = MutableStateFlow<WebRtcConnectionState>(WebRtcConnectionState.IDLE)
    val webRtcConnectionState: StateFlow<WebRtcConnectionState> = _webRtcConnectionState.asStateFlow()"""
)

# 4. Add connectionFailure flow after receivedMessages
replace_in_file(
    "together/webrtc/WebRtcTransport.kt",
    "    private val _receivedMessages = MutableSharedFlow<TogetherMessage>(extraBufferCapacity = 64)\n    val receivedMessages: SharedFlow<TogetherMessage> = _receivedMessages",
    """    private val _receivedMessages = MutableSharedFlow<TogetherMessage>(extraBufferCapacity = 64)
    val receivedMessages: SharedFlow<TogetherMessage> = _receivedMessages

    // Connection failure events - emitted when connection drops unexpectedly
    // Only emitted from ICE state changes, not from DataChannel state
    private val _connectionFailure = MutableSharedFlow<ConnectionFailureReason>(extraBufferCapacity = 64)
    val connectionFailure: SharedFlow<ConnectionFailureReason> = _connectionFailure"""
)

# 5. Add wasConnected and manualDisconnect flags
replace_in_file(
    "together/webrtc/WebRtcTransport.kt",
    "    private var peerStateJob: kotlinx.coroutines.Job? = null\n    private var peerMessageJob: kotlinx.coroutines.Job? = null",
    """    private var peerStateJob: kotlinx.coroutines.Job? = null
    private var peerMessageJob: kotlinx.coroutines.Job? = null
    private var wasConnected = false
    private var manualDisconnect = false"""
)

# 6. Update host() - call disconnect, then create peer, then set state
replace_in_file(
    "together/webrtc/WebRtcTransport.kt",
    "    fun host(listener: (status: String) -> Unit = {}) {\n        disconnect()\n        createPeer(isHost = true)\n        listener(\"Host peer created\")\n    }",
    """    fun host(listener: (status: String) -> Unit = {}) {
        disconnect()
        createPeer(isHost = true)
        _webRtcConnectionState.value = WebRtcConnectionState.CONNECTING
        listener("Host peer created")
    }"""
)

# 7. Update join() - call disconnect, then create peer, then set state
replace_in_file(
    "together/webrtc/WebRtcTransport.kt",
    "    fun join(listener: (status: String) -> Unit = {}) {\n        disconnect()\n        createPeer(isHost = false)\n        listener(\"Guest peer created\")\n    }",
    """    fun join(listener: (status: String) -> Unit = {}) {
        disconnect()
        createPeer(isHost = false)
        _webRtcConnectionState.value = WebRtcConnectionState.CONNECTING
        listener("Guest peer created")
    }"""
)

# 8. Update disconnect() - set manualDisconnect before closing
replace_in_file(
    "together/webrtc/WebRtcTransport.kt",
    "    fun disconnect() {\n        peerStateJob?.cancel()\n        peerStateJob = null\n        peerMessageJob?.cancel()\n        peerMessageJob = null\n        currentPeer?.close()\n        currentPeer = null\n        scope.coroutineContext.cancelChildren()\n    }",
    """    fun disconnect() {
        peerStateJob?.cancel()
        peerStateJob = null
        peerMessageJob?.cancel()
        peerMessageJob = null
        // Mark as intentional disconnect before closing peer
        manualDisconnect = true
        currentPeer?.close()
        currentPeer = null
        wasConnected = false
        scope.coroutineContext.cancelChildren()
        _webRtcConnectionState.value = WebRtcConnectionState.CLOSED
    }"""
)

# 9. Update createPeer() to reset manualDisconnect when new peer becomes active
replace_in_file(
    "together/webrtc/WebRtcTransport.kt",
    "        val peerConnection = peerFactory.createPeerConnection(observer)\n        val peer = WebRtcPeer(peerConnection, scope)\n        currentPeer = peer",
    """        val peerConnection = peerFactory.createPeerConnection(observer)
        val peer = WebRtcPeer(peerConnection, scope)
        currentPeer = peer
        // New peer is active; clear the manual disconnect flag
        manualDisconnect = false"""
)

# 10. Update peer.connectionState observer - only update state, no failure emission
replace_in_file(
    "together/webrtc/WebRtcTransport.kt",
    "        peerStateJob = peer.connectionState\n            .onEach { state ->\n                _connectionState.value = state\n            }\n            .launchIn(scope)",
    """        peerStateJob = peer.connectionState
            .onEach { state ->
                _connectionState.value = state
                when (state) {
                    DataChannel.State.OPEN -> {
                        _webRtcConnectionState.value = WebRtcConnectionState.CONNECTED
                        wasConnected = true
                    }
                    DataChannel.State.CLOSED,
                    DataChannel.State.CLOSING -> {
                        // Only update state, don't emit failure here
                        // ICE state changes will handle failure emission
                        if (wasConnected && _webRtcConnectionState.value != WebRtcConnectionState.CLOSED) {
                            _webRtcConnectionState.value = WebRtcConnectionState.DISCONNECTED
                        }
                    }
                    DataChannel.State.CONNECTING -> {
                        _webRtcConnectionState.value = WebRtcConnectionState.CONNECTING
                    }
                    else -> { /* ignore */ }
                }
            }
            .launchIn(scope)"""
)

# 11. Add ICE state monitoring - single source of failure emission with manualDisconnect guard
replace_in_file(
    "together/webrtc/WebRtcTransport.kt",
    "            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {\n                // Not needed yet\n            }",
    """            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                // Single source of truth for connection failure detection
                // Only emit if this is NOT an intentional disconnect
                when (state) {
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        if (!manualDisconnect &&
                            wasConnected &&
                            _webRtcConnectionState.value == WebRtcConnectionState.CONNECTED
                        ) {
                            _webRtcConnectionState.value = WebRtcConnectionState.DISCONNECTED
                            _connectionFailure.tryEmit(ConnectionFailureReason.ICE_DISCONNECTED)
                        }
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        if (!manualDisconnect &&
                            wasConnected &&
                            _webRtcConnectionState.value == WebRtcConnectionState.CONNECTED
                        ) {
                            _webRtcConnectionState.value = WebRtcConnectionState.DISCONNECTED
                            _connectionFailure.tryEmit(ConnectionFailureReason.ICE_FAILED)
                        }
                    }
                    else -> { /* ignore */ }
                }
            }"""
)

print("========================================")
if DRY_RUN:
    print("PHASE 8 - STEP 1 (DRY RUN)")
    print("")
    print("Added to WebRtcTransport.kt:")
    print("  - WebRtcConnectionState enum (IDLE, CONNECTING, CONNECTED, DISCONNECTED, CLOSED)")
    print("  - ConnectionFailureReason enum (ICE_DISCONNECTED, ICE_FAILED, DATA_CHANNEL_CLOSED)")
    print("  - webRtcConnectionState: StateFlow<WebRtcConnectionState>")
    print("  - connectionFailure: SharedFlow<ConnectionFailureReason>")
    print("  - manualDisconnect flag reset inside createPeer() after new peer becomes active")
    print("  - State transitions: IDLE -> CONNECTING -> CONNECTED/DISCONNECTED -> CLOSED")
    print("  - Single source of failure: onIceConnectionChange() only")
    print("")
    print("No reconnect logic, backoff, or client propagation yet.")
    print("Set DRY_RUN=False to apply.")
else:
    print("PHASE 8 - STEP 1 COMPLETED")
    print("Connection state reporting added with failure reason enum.")
    print("Next: Step 2 - Reconnection Backoff & State Machine")
print("========================================")
print("DO NOT CONTINUE UNTIL USER CONFIRMS.")
print("========================================")
