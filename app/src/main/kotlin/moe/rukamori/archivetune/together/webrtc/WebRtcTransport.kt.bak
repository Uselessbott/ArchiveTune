package moe.rukamori.archivetune.together.webrtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.together.TogetherMessage
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

    // DataChannel state - own StateFlow for current state
    private val _connectionState = MutableStateFlow<DataChannel.State?>(null)
    val connectionState: StateFlow<DataChannel.State?> = _connectionState.asStateFlow()

    // Messages
    private val _receivedMessages = MutableSharedFlow<TogetherMessage>(extraBufferCapacity = 64)
    val receivedMessages: SharedFlow<TogetherMessage> = _receivedMessages

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

    fun sendMessage(message: TogetherMessage) {
        val peer = currentPeer ?: throw IllegalStateException("No active peer")
        if (!peer.sendMessage(message)) {
            throw IllegalStateException("Failed to send message; DataChannel not open or not set")
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
                _connectionState.value = state
            }
            .launchIn(scope)

        peerMessageJob = peer.receivedMessages
            .onEach { message ->
                _receivedMessages.emit(message)
            }
            .launchIn(scope)
    }
}
