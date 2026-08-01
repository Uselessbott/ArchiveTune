package moe.rukamori.archivetune.together.manual

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.Job

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.together.webrtc.WebRtcTransport
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import moe.rukamori.archivetune.together.ManualQrProtocol
import moe.rukamori.archivetune.together.TogetherJson
import org.webrtc.SessionDescription
import kotlinx.coroutines.flow.collectLatest
import moe.rukamori.archivetune.together.webrtc.WebRtcConnectionState


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


    private var iceCollectionJob: Job? = null
    private var connectionObserverJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pendingIceCandidates = mutableListOf<ManualIceCandidate>()

    private val seenRemoteIce = hashSetOf<String>()
    private val iceMutex = Mutex()


    private val _exchangeState =
        MutableStateFlow<QrExchangeState>(QrExchangeState.Idle)

    val exchangeState: StateFlow<QrExchangeState> =
        _exchangeState.asStateFlow()



    
    private fun observeConnectionState() {
        connectionObserverJob?.cancel()

        connectionObserverJob = scope.launch {
            transport.webRtcConnectionState.collectLatest { state ->
                when (state) {
                    WebRtcConnectionState.CONNECTING -> {
                        _exchangeState.value = QrExchangeState.Connecting
                    }

                    WebRtcConnectionState.CONNECTED -> {
                        _exchangeState.value = QrExchangeState.Connected
                    }

                    WebRtcConnectionState.DISCONNECTED -> {
                        _exchangeState.value =
                            QrExchangeState.Failed("Peer disconnected")
                    }

                    WebRtcConnectionState.CLOSED -> {
                        if (_exchangeState.value != QrExchangeState.Idle) {
                            _exchangeState.value = QrExchangeState.Idle
                        }
                    }

                    WebRtcConnectionState.IDLE -> Unit
                }
            }
        }
    }

private fun startIceCollection() {
        iceCollectionJob?.cancel()
        connectionObserverJob?.cancel()

        iceCollectionJob = scope.launch {
            transport.localIceCandidates.collect { dto ->
                iceMutex.lock()
                try {
                    pendingIceCandidates += ManualIceCandidate(
                        version = ManualQrProtocol.VERSION,
                        sessionId = sessionId,
                        candidate = dto,
                    )


                flushIceCandidates()

                } finally {
                    iceMutex.unlock()
                }
            }
        }
    }


    suspend fun startHost() {
        _exchangeState.value = QrExchangeState.CreatingOffer
        transport.host()
        startIceCollection()
        observeConnectionState()
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
        transport.join()
        startIceCollection()
    }

    suspend fun submitPackets(packets: List<String>) {
        try {
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
                handleIceBatch(signalPacket)
            }
                }
        } catch (t: Throwable) {
            _exchangeState.value =
                QrExchangeState.Failed(
                    t.message ?: "Failed to process QR packet",
                )
        }
    }

    

    suspend fun exportPendingIce() {
        val batch = iceMutex.withLock {
            if (pendingIceCandidates.isEmpty()) return

            ManualIceCandidateBatch(
                sessionId = remoteSessionId ?: sessionId,
                candidates = pendingIceCandidates.toList(),
            )
        }

        val json = TogetherJson.json.encodeToString(batch)

        iceMutex.withLock {
            pendingIceCandidates.clear()
        }

        val packet = QrSignalPacket(
            version = ManualQrProtocol.VERSION,
            sessionId = batch.sessionId,
            kind = QrSignalKind.ICE,
            part = REASSEMBLED_PART,
            total = ManualQrProtocol.QR_COUNT,
            payload = json,
        )

        _qrPackets.value = QrCodec.encode(packet)
    }


    private suspend fun handleIceBatch(packet: QrSignalPacket) {
        val batch =
            TogetherJson.json.decodeFromString<ManualIceCandidateBatch>(
                packet.payload,
            )

        val expected = remoteSessionId ?: sessionId

        require(batch.sessionId == expected) {
            "ICE session mismatch"
        }

        batch.candidates.forEach {
            transport.addRemoteIceCandidate(it.candidate)
        }
    }


private fun sdpTypeFromString(type: String): SessionDescription.Type {
        return when (type.lowercase()) {
            "offer" -> SessionDescription.Type.OFFER
            "answer" -> SessionDescription.Type.ANSWER
            "pranswer" -> SessionDescription.Type.PRANSWER
            else -> error("Unknown SDP type: $type")
        }
    }

    private fun sdpTypeToString(type: SessionDescription.Type): String =
        when (type) {
            SessionDescription.Type.OFFER -> "offer"
            SessionDescription.Type.ANSWER -> "answer"
            SessionDescription.Type.PRANSWER -> "pranswer"
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


    private suspend fun flushIceCandidates() {
        iceMutex.lock()
        try {
            if (pendingIceCandidates.isEmpty()) return

            val batch = ManualIceCandidateBatch(
                version = ManualQrProtocol.VERSION,
                sessionId = sessionId,
                candidates = pendingIceCandidates.map { it.candidate }
            )

            val json = TogetherJson.json.encodeToString(batch)

            val packet = QrSignalPacket(
                version = ManualQrProtocol.VERSION,
                sessionId = sessionId,
                kind = QrSignalKind.ICE,
                part = REASSEMBLED_PART,
                total = ManualQrProtocol.QR_COUNT,
                payload = json,
            )

            _qrPackets.value = QrCodec.encode(packet)

            pendingIceCandidates.clear()

        } finally {
            iceMutex.unlock()
        }
    }


    suspend fun handleIce(
        candidate: ManualIceCandidate,
    ) {
        val key =
            buildString {
                append(candidate.candidate.sdpMid)
                append(':')
                append(candidate.candidate.sdpMLineIndex)
                append(':')
                append(candidate.candidate.candidate)
            }

        if (!seenRemoteIce.add(key)) {
            return
        }

        transport.addRemoteIceCandidate(candidate.candidate)
    }

    fun close() {
        transport.disconnect()
        _exchangeState.value = QrExchangeState.Idle
        _qrPackets.value = emptyList()
        iceCollectionJob?.cancel()
        connectionObserverJob?.cancel()
        scope.cancel()
        pendingIceCandidates.clear()
        seenRemoteIce.clear()
        remoteSessionId = null
    }
}