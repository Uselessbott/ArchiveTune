package moe.rukamori.archivetune.together.webrtc

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
