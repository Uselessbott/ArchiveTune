package moe.rukamori.archivetune.together.webrtc

import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import java.nio.ByteBuffer

class WebRtcPeer(
    val peerConnection: PeerConnection
) {
    private var dataChannel: DataChannel? = null

    fun setDataChannel(channel: DataChannel, observer: DataChannel.Observer) {
        channel.registerObserver(observer)
        this.dataChannel = channel
    }

    fun send(data: ByteArray): Boolean {
        val channel = dataChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val buffer = ByteBuffer.wrap(data)
        return channel.send(DataChannel.Buffer(buffer, false))
    }

    fun close() {
        dataChannel?.close()
        peerConnection.close()
        peerConnection.dispose()
    }
}
