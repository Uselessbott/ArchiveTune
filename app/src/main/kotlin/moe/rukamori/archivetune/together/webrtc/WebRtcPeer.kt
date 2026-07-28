package moe.rukamori.archivetune.together.webrtc

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebRtcPeer(
    val peerConnection: PeerConnection
) {
    private val _localIceCandidates = MutableSharedFlow<IceCandidateDto>(extraBufferCapacity = 64)
    val localIceCandidates: SharedFlow<IceCandidateDto> = _localIceCandidates

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

    suspend fun emitLocalIceCandidate(candidate: IceCandidate) {
        val dto = IceCandidateDto(
            candidate = candidate.sdp,
            sdpMid = candidate.sdpMid,
            sdpMLineIndex = candidate.sdpMLineIndex
        )
        _localIceCandidates.emit(dto)
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
