package moe.rukamori.archivetune.together.manual

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.together.webrtc.WebRtcTransport
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import moe.rukamori.archivetune.together.ManualQrProtocol
import moe.rukamori.archivetune.together.TogetherJson
import org.webrtc.SessionDescription


class QrWebRtcSession(
    private val transport: WebRtcTransport,
) {

    val sessionId: String = UUID.randomUUID().toString()
    private companion object {
        private const val REASSEMBLED_PART = 0
    }

    private val _qrPackets = MutableStateFlow<List<String>>(emptyList())
    val qrPackets: StateFlow<List<String>> = _qrPackets.asStateFlow()

    private var remoteSessionId: String? = null


    private val _exchangeState =
        MutableStateFlow<QrExchangeState>(QrExchangeState.Idle)

    val exchangeState: StateFlow<QrExchangeState> =
        _exchangeState.asStateFlow()

    suspend fun startHost() {
        _exchangeState.value = QrExchangeState.CreatingOffer
        transport.host()
        val offerSdp = transport.createOffer()
        transport.setLocalDescription(offerSdp)

        val offer = ManualOffer(
            version = ManualQrProtocol.VERSION,
            sessionId = sessionId,
            type = sdpTypeToString(offerSdp.type),
            sdp = offerSdp.description
        )
        val offerJson = TogetherJson.json.encodeToString(offer)
        val fullPacket = QrSignalPacket(
            version = ManualQrProtocol.VERSION,
            sessionId = sessionId,
            kind = QrSignalKind.OFFER,
            part = REASSEMBLED_PART,
            total = ManualQrProtocol.QR_COUNT,
            payload = offerJson
        )
        val qrStrings = QrCodec.encode(fullPacket)
        _qrPackets.value = qrStrings
        _exchangeState.value = QrExchangeState.WaitingForRemoteAnswer
    }

    suspend fun startGuest() {
        _exchangeState.value = QrExchangeState.WaitingForOffer
    }

    suspend fun submitPackets(packets: List<String>) {
        val currentState = _exchangeState.value
        require(
            currentState is QrExchangeState.WaitingForOffer ||
            currentState is QrExchangeState.WaitingForRemoteAnswer
        ) {
            "Unexpected state: $currentState"
        }
        val signalPacket = QrCodec.decode(packets)
        remoteSessionId = signalPacket.sessionId
        _qrPackets.value = emptyList()
        when (signalPacket.kind) {
            QrSignalKind.OFFER -> {
                require(currentState is QrExchangeState.WaitingForOffer) {
                    "OFFER cannot be processed in state $currentState"
                }
                val offer = TogetherJson.json.decodeFromString<ManualOffer>(signalPacket.payload)
                handleOffer(offer)
            }
            QrSignalKind.ANSWER -> {
                require(currentState is QrExchangeState.WaitingForRemoteAnswer) {
                    "ANSWER cannot be processed in state $currentState"
                }
                val answer = TogetherJson.json.decodeFromString<ManualAnswer>(signalPacket.payload)
                handleAnswer(answer)
            }
            QrSignalKind.ICE -> {
                // Phase 3
                TODO("Phase 3: ICE batching")
            }
        }
    }

    private fun sdpTypeFromString(type: String): SessionDescription.Type {
        return when (type.lowercase()) {
            "offer" -> SessionDescription.Type.OFFER
            "answer" -> SessionDescription.Type.ANSWER
            "pranswer" -> SessionDescription.Type.PRANSWER
            "rollback" -> SessionDescription.Type.ROLLBACK
            else -> error("Unknown SDP type: $type")
        }
    }

    private fun sdpTypeToString(type: SessionDescription.Type): String =
        when (type) {
            SessionDescription.Type.OFFER -> "offer"
            SessionDescription.Type.ANSWER -> "answer"
            SessionDescription.Type.PRANSWER -> "pranswer"
            SessionDescription.Type.ROLLBACK -> "rollback"
            else -> error("Unknown SDP type: $type")
        }


    suspend fun handleOffer(
        offer: ManualOffer,
    ) {
        _exchangeState.value = QrExchangeState.CreatingAnswer
        transport.join()
        transport.setRemoteDescription(SessionDescription(sdpTypeFromString(offer.type), offer.sdp))
        val answerSdp = transport.createAnswer()
        transport.setLocalDescription(answerSdp)

        val remoteId = remoteSessionId ?: error("Remote session ID not set")
        val answer = ManualAnswer(
            version = ManualQrProtocol.VERSION,
            sessionId = remoteId,
            type = sdpTypeToString(answerSdp.type),
            sdp = answerSdp.description
        )
        val answerJson = TogetherJson.json.encodeToString(answer)
        val fullPacket = QrSignalPacket(
            version = ManualQrProtocol.VERSION,
            sessionId = remoteId,
            kind = QrSignalKind.ANSWER,
            part = REASSEMBLED_PART,
            total = ManualQrProtocol.QR_COUNT,
            payload = answerJson
        )
        val qrStrings = QrCodec.encode(fullPacket)
        _qrPackets.value = qrStrings
        _exchangeState.value = QrExchangeState.WaitingForRemoteAnswer
    }

    suspend fun handleAnswer(
        answer: ManualAnswer,
    ) {
        transport.setRemoteDescription(SessionDescription(sdpTypeFromString(answer.type), answer.sdp))
        _exchangeState.value = QrExchangeState.Connecting
    }

    suspend fun handleIce(
        candidate: ManualIceCandidate,
    ) {
        // Phase 3: ICE batching
        TODO("Phase 3: ICE batching")
    }

    fun close() {
        transport.disconnect()
        _exchangeState.value = QrExchangeState.Idle
        _qrPackets.value = emptyList()
        remoteSessionId = null
    }
}
