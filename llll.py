import os
from pathlib import Path

BASE = Path("app/src/main/kotlin/moe/rukamori/archivetune")

def write_file(rel_path, content):
    full_path = BASE / rel_path
    full_path.parent.mkdir(parents=True, exist_ok=True)
    with open(full_path, "w") as f:
        f.write(content)
    print(f"Written: {full_path}")

# WebRtcPeer.kt – only SDP methods, no DataChannel logic
write_file(
    "together/webrtc/WebRtcPeer.kt",
    """package moe.rukamori.archivetune.together.webrtc

import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebRtcPeer(
    val peerConnection: PeerConnection
) {
    suspend fun createOffer(): SessionDescription = suspendCancellableCoroutine { continuation ->
        val observer = createSdpObserver(continuation)
        peerConnection.createOffer(observer, MediaConstraints())
    }

    suspend fun createAnswer(): SessionDescription = suspendCancellableCoroutine { continuation ->
        val observer = createSdpObserver(continuation)
        peerConnection.createAnswer(observer, MediaConstraints())
    }

    suspend fun setLocalDescription(sdp: SessionDescription): Unit = suspendCancellableCoroutine { continuation ->
        val observer = createSetSdpObserver(continuation)
        peerConnection.setLocalDescription(observer, sdp)
    }

    suspend fun setRemoteDescription(sdp: SessionDescription): Unit = suspendCancellableCoroutine { continuation ->
        val observer = createSetSdpObserver(continuation)
        peerConnection.setRemoteDescription(observer, sdp)
    }

    private fun createSdpObserver(continuation: kotlin.coroutines.Continuation<SessionDescription>): SdpObserver {
        return object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp != null) {
                    continuation.resume(sdp)
                } else {
                    continuation.resumeWithException(IllegalStateException("Created SDP is null"))
                }
            }

            override fun onSetSuccess() {
                // Not used for creation
            }

            override fun onCreateFailure(error: String?) {
                continuation.resumeWithException(RuntimeException("SDP creation failed: ${error ?: "unknown error"}"))
            }

            override fun onSetFailure(error: String?) {
                // Not used
            }
        }
    }

    private fun createSetSdpObserver(continuation: kotlin.coroutines.Continuation<Unit>): SdpObserver {
        return object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                // Not used
            }

            override fun onSetSuccess() {
                continuation.resume(Unit)
            }

            override fun onCreateFailure(error: String?) {
                // Not used
            }

            override fun onSetFailure(error: String?) {
                continuation.resumeWithException(RuntimeException("Set SDP failed: ${error ?: "unknown error"}"))
            }
        }
    }

    fun close() {
        peerConnection.close()
        peerConnection.dispose()
    }
}
"""
)

# WebRtcTransport.kt – only SDP, no DataChannel, no messaging, no JSON
write_file(
    "together/webrtc/WebRtcTransport.kt",
    """package moe.rukamori.archivetune.together.webrtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRtcTransport @Inject constructor(
    private val peerFactory: WebRtcPeerFactory
) {
    private var currentPeer: WebRtcPeer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun host(listener: (status: String) -> Unit = {}) {
        disconnect()
        createPeer()
        listener("Host peer created")
    }

    fun join(listener: (status: String) -> Unit = {}) {
        disconnect()
        createPeer()
        listener("Guest peer created")
    }

    fun disconnect() {
        currentPeer?.close()
        currentPeer = null
        scope.coroutineContext.cancelChildren()
    }

    suspend fun createOffer(): SessionDescription {
        val peer = currentPeer ?: throw IllegalStateException("No active peer")
        return peer.createOffer()
    }

    suspend fun createAnswer(): SessionDescription {
        val peer = currentPeer ?: throw IllegalStateException("No active peer")
        return peer.createAnswer()
    }

    suspend fun setLocalDescription(sdp: SessionDescription) {
        val peer = currentPeer ?: throw IllegalStateException("No active peer")
        peer.setLocalDescription(sdp)
    }

    suspend fun setRemoteDescription(sdp: SessionDescription) {
        val peer = currentPeer ?: throw IllegalStateException("No active peer")
        peer.setRemoteDescription(sdp)
    }

    private fun createPeer() {
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                // Will be used for signalling in future phase
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                // Not needed yet
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                // Not needed yet
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                // Not needed yet
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                // Not needed yet
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                // Not needed yet
            }

            override fun onAddStream(stream: MediaStream?) {
                // Not used for data-only
            }

            override fun onRemoveStream(stream: MediaStream?) {
                // Not used
            }

            override fun onDataChannel(channel: DataChannel?) {
                // Will be used for DataChannel in a later phase
            }

            override fun onRenegotiationNeeded() {
                // Will be used later
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                // Not used
            }
        }

        val peerConnection = peerFactory.createPeerConnection(observer)
        currentPeer = WebRtcPeer(peerConnection)
    }
}
"""
)

# WebRtcSignallingApi.kt – skeleton, unchanged
write_file(
    "together/webrtc/WebRtcSignallingApi.kt",
    """package moe.rukamori.archivetune.together.webrtc

// Placeholder interface for future signalling implementation.
// Will replace TogetherOnlineApi for SDP/ICE exchange.
interface WebRtcSignallingApi {
    // Future methods: sendOffer, sendAnswer, sendIceCandidate, etc.
}
"""
)

print("All SDP foundation files written successfully.")
