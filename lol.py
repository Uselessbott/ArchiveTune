import os
from pathlib import Path

BASE = Path("app/src/main/kotlin/moe/rukamori/archivetune")

def write_file(rel_path, content):
    full_path = BASE / rel_path
    full_path.parent.mkdir(parents=True, exist_ok=True)
    with open(full_path, "w") as f:
        f.write(content)
    print(f"Written: {full_path}")

# WebRtcPeer.kt – with injected CoroutineScope, non-null flows, proper cleanup
write_file(
    "together/webrtc/WebRtcPeer.kt",
    """package moe.rukamori.archivetune.together.webrtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebRtcPeer(
    val peerConnection: PeerConnection,
    private val scope: CoroutineScope
) {
    // SDP methods
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

    fun addIceCandidate(candidate: IceCandidate): Boolean {
        return peerConnection.addIceCandidate(candidate)
    }

    // ICE candidates
    private val _localIceCandidates = MutableSharedFlow<IceCandidateDto>(extraBufferCapacity = 64)
    val localIceCandidates: SharedFlow<IceCandidateDto> = _localIceCandidates

    suspend fun emitLocalIceCandidate(candidate: IceCandidate) {
        val dto = IceCandidateDto(
            candidate = candidate.sdp,
            sdpMid = candidate.sdpMid,
            sdpMLineIndex = candidate.sdpMLineIndex
        )
        _localIceCandidates.emit(dto)
    }

    // DataChannel
    private var dataChannel: DataChannel? = null

    private val _connectionState = MutableStateFlow<DataChannel.State?>(null)
    val connectionState: StateFlow<DataChannel.State?> = _connectionState.asStateFlow()

    private val _receivedMessages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val receivedMessages: SharedFlow<String> = _receivedMessages.asSharedFlow()

    fun setDataChannel(channel: DataChannel) {
        dataChannel = channel
        val observer = createDataChannelObserver()
        channel.registerObserver(observer)
        _connectionState.value = channel.state()
    }

    fun createDataChannel(label: String = "archivetune"): DataChannel {
        val init = DataChannel.Init().apply {
            ordered = true
        }
        val channel = peerConnection.createDataChannel(label, init)
            ?: throw IllegalStateException("Failed to create DataChannel")
        setDataChannel(channel)
        return channel
    }

    fun sendText(text: String): Boolean {
        val channel = dataChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val bytes = text.encodeToByteArray()
        val buffer = ByteBuffer.wrap(bytes)
        return channel.send(DataChannel.Buffer(buffer, false))
    }

    private fun createDataChannelObserver(): DataChannel.Observer {
        return object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {
                // Ignore
            }

            override fun onStateChange() {
                val channel = dataChannel
                if (channel != null) {
                    _connectionState.value = channel.state()
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = String(bytes, Charsets.UTF_8)
                scope.launch {
                    _receivedMessages.emit(text)
                }
            }
        }
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
        dataChannel?.close()
        dataChannel = null
        peerConnection.close()
        peerConnection.dispose()
    }
}
"""
)

# WebRtcTransport.kt – own non-null flows, forward from peer, pass scope
write_file(
    "together/webrtc/WebRtcTransport.kt",
    """package moe.rukamori.archivetune.together.webrtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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

    // ICE candidates - own non-null flow
    private val _localIceCandidates = MutableSharedFlow<IceCandidateDto>(extraBufferCapacity = 64)
    val localIceCandidates: SharedFlow<IceCandidateDto> = _localIceCandidates

    // DataChannel state and messages - own non-null flows
    private val _connectionState = MutableSharedFlow<DataChannel.State>(extraBufferCapacity = 64)
    val connectionState: SharedFlow<DataChannel.State> = _connectionState

    private val _receivedMessages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val receivedMessages: SharedFlow<String> = _receivedMessages

    private var peerStateJob: kotlinx.coroutines.Job? = null
    private var peerMessageJob: kotlinx.coroutines.Job? = null

    fun host(listener: (status: String) -> Unit = {}) {
        disconnect()
        createPeer(isHost = true)
        listener("Host peer created")
    }

    fun join(listener: (status: String) -> Unit = {}) {
        disconnect()
        createPeer(isHost = false)
        listener("Guest peer created")
    }

    fun disconnect() {
        peerStateJob?.cancel()
        peerStateJob = null
        peerMessageJob?.cancel()
        peerMessageJob = null
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

    suspend fun addRemoteIceCandidate(candidate: IceCandidateDto) {
        val peer = currentPeer ?: throw IllegalStateException("No active peer")
        val iceCandidate = IceCandidate(
            candidate.sdpMid,
            candidate.sdpMLineIndex,
            candidate.candidate
        )
        if (!peer.addIceCandidate(iceCandidate)) {
            throw IllegalStateException("Failed to add remote ICE candidate")
        }
    }

    fun sendText(text: String) {
        val peer = currentPeer ?: throw IllegalStateException("No active peer")
        if (!peer.sendText(text)) {
            throw IllegalStateException("Failed to send text; DataChannel not open or not set")
        }
    }

    private fun createPeer(isHost: Boolean) {
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    val dto = IceCandidateDto(
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex
                    )
                    scope.launch {
                        _localIceCandidates.emit(dto)
                    }
                }
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
                // Not used
            }

            override fun onRemoveStream(stream: MediaStream?) {
                // Not used
            }

            override fun onDataChannel(channel: DataChannel?) {
                if (!isHost && channel != null) {
                    val peer = currentPeer
                    if (peer != null) {
                        peer.setDataChannel(channel)
                    }
                }
            }

            override fun onRenegotiationNeeded() {
                // Will be used later
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                // Not used
            }
        }

        val peerConnection = peerFactory.createPeerConnection(observer)
        val peer = WebRtcPeer(peerConnection, scope)
        currentPeer = peer

        if (isHost) {
            peer.createDataChannel()
        }

        // Forward state and messages from peer to transport flows
        peerStateJob = peer.connectionState
            .onEach { state ->
                if (state != null) {
                    _connectionState.emit(state)
                }
            }
            .launchIn(scope)

        peerMessageJob = peer.receivedMessages
            .onEach { message ->
                _receivedMessages.emit(message)
            }
            .launchIn(scope)
    }
}
"""
)

print("DataChannel foundation with proper scopes and flows written successfully.")
