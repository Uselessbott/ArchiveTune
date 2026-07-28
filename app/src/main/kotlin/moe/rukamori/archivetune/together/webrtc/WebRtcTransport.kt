package moe.rukamori.archivetune.together.webrtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.together.TogetherMessage
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.cancelChildren

@Singleton
class WebRtcTransport @Inject constructor(
    private val peerFactory: WebRtcPeerFactory
) {
    private var currentPeer: WebRtcPeer? = null
    private val _incomingMessages = MutableSharedFlow<TogetherMessage>()
    val incomingMessages: SharedFlow<TogetherMessage> = _incomingMessages

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    fun host(listener: (status: String) -> Unit = {}) {
        disconnect()
        createPeerAndChannel(isHost = true)
        listener("Host peer created")
    }

    fun join(listener: (status: String) -> Unit = {}) {
        disconnect()
        createPeerAndChannel(isHost = false)
        listener("Guest peer created")
    }

    fun disconnect() {
        currentPeer?.close()
        currentPeer = null
        scope.coroutineContext.cancelChildren()
    }

    fun send(message: TogetherMessage) {
        val peer = currentPeer ?: throw IllegalStateException("No active WebRTC peer")
        val jsonString = json.encodeToString(TogetherMessage.serializer(), message)
        val bytes = jsonString.toByteArray()
        if (!peer.send(bytes)) {
            throw IllegalStateException("Failed to send message; DataChannel not open or not set")
        }
    }

    private fun createPeerAndChannel(isHost: Boolean) {
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
                // Guest receives the DataChannel from host
                if (!isHost && channel != null) {
                    val peer = currentPeer
                    if (peer != null) {
                        peer.setDataChannel(channel, createDataChannelObserver())
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
        val webRtcPeer = WebRtcPeer(peerConnection)
        currentPeer = webRtcPeer

        if (isHost) {
            val dataChannel = peerFactory.createDataChannel(peerConnection)
            webRtcPeer.setDataChannel(dataChannel, createDataChannelObserver())
        }
    }

    private fun createDataChannelObserver(): DataChannel.Observer {
        return object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {
                // Not needed
            }

            override fun onStateChange() {
                // Could emit state changes later
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                try {
                    val jsonString = String(bytes)
                    val message = json.decodeFromString(TogetherMessage.serializer(), jsonString)
                    scope.launch {
                        _incomingMessages.emit(message)
                    }
                } catch (e: Exception) {
                    // Log parsing error
                }
            }
        }
    }
}
