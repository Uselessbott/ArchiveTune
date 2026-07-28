package moe.rukamori.archivetune.together.webrtc

import android.content.Context
import org.webrtc.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRtcPeerFactory @Inject constructor(
    private val context: Context
) {

    private val isInitialized = AtomicBoolean(false)

    private val eglBase: EglBase by lazy {
        EglBase.create()
    }

    private val peerConnectionFactory: PeerConnectionFactory by lazy {
        synchronized(this) {
            if (!isInitialized.getAndSet(true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions()
                )
            }
            val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
        }
    }

    fun createIceServers(): List<PeerConnection.IceServer> {
        return listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
    }

    fun createPeerConnection(observer: PeerConnection.Observer): PeerConnection {
        val rtcConfig = PeerConnection.RTCConfiguration(createIceServers())
        return requireNotNull(
            peerConnectionFactory.createPeerConnection(rtcConfig, observer)
        ) { "Failed to create PeerConnection" }
    }

    fun createDataChannel(peerConnection: PeerConnection): DataChannel {
        val init = DataChannel.Init().apply {
            ordered = true
        }
        return requireNotNull(
            peerConnection.createDataChannel("together", init)
        ) { "Failed to create DataChannel" }
    }

    fun dispose() {
        peerConnectionFactory.dispose()
        eglBase.release()
    }
}
