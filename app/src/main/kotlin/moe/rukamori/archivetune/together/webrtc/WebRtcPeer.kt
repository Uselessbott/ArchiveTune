package moe.rukamori.archivetune.together.webrtc

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.rukamori.archivetune.together.TogetherJson
import moe.rukamori.archivetune.together.TogetherMessage
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "WebRtcPeer"

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

    private val _receivedMessages = MutableSharedFlow<TogetherMessage>(extraBufferCapacity = 64)
    val receivedMessages: SharedFlow<TogetherMessage> = _receivedMessages.asSharedFlow()

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

    fun sendMessage(message: TogetherMessage): Boolean {
        val channel = dataChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val jsonString = TogetherJson.json.encodeToString(TogetherMessage.serializer(), message)
        val bytes = jsonString.encodeToByteArray()
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
                val jsonString = String(bytes, Charsets.UTF_8)
                try {
                    val message = TogetherJson.json.decodeFromString(TogetherMessage.serializer(), jsonString)
                    scope.launch {
                        _receivedMessages.emit(message)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to deserialize TogetherMessage: $jsonString", e)
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
