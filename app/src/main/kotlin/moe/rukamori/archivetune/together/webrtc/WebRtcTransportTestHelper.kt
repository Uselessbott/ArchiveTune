package moe.rukamori.archivetune.together.webrtc

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel

object WebRtcTransportTestHelper {

    /**
     * Guest-side ping responder.
     * Listens for incoming "ping" messages and replies with "pong".
     * Should be launched in a coroutine.
     */
    suspend fun runGuestPingResponder(transport: WebRtcTransport) {
        transport.receivedMessages.collect { message ->
            if (message == "ping") {
                transport.sendText("pong")
            }
        }
    }

    /**
     * Host-side ping test.
     * Waits for DataChannel to be OPEN, sends "ping", and waits for "pong".
     * Returns true if "pong" is received within the timeout, false otherwise.
     */
    suspend fun runHostPingTest(
        transport: WebRtcTransport,
        timeoutMillis: Long = 5000
    ): Boolean {
        // Wait for DataChannel to be open
        val open = withTimeoutOrNull(timeoutMillis) {
            transport.connectionState.first { it == DataChannel.State.OPEN }
        }
        if (open == null) {
            return false // timed out waiting for open
        }

        // Send ping
        transport.sendText("ping")

        // Wait for pong
        val pong = withTimeoutOrNull(timeoutMillis) {
            transport.receivedMessages.first { it == "pong" }
        }
        return pong != null
    }
}
