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

    private var lastImportedFingerprint: Int? = null

    private var iceCollectionJob: Job? = null
    private var connectionObserverJob: Job? = null
    private var pendingIceFlushJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pendingIceCandidates = mutableListOf<ManualIceCandidate>()

    private val seenRemoteIce = hashSetOf<String>()
    private val iceMutex = Mutex()

    private val _exchangeState =
        MutableStateFlow<QrExchangeState>(QrExchangeState.Idle)

    val exchangeState: StateFlow<QrExchangeState> =
        _exchangeState.asStateFlow()

    // ---------- Host multi‑peer state (preparation) ----------
    private enum class Role { HOST, GUEST }
    private var role: Role? = null

    private data class HostPeerSession(
        val sessionId: String,
        var remoteSessionId: String? = null,
        var lastImportedFingerprint: Int? = null,
        val pendingIceCandidates: MutableList<ManualIceCandidate> = mutableListOf(),
        val seenRemoteIce: MutableSet<String> = mutableSetOf()
    )
    private val hostPeers = mutableMapOf<String, HostPeerSession>()
    // TODO: Replace with one WebRtcTransport per HostPeerSession when the transport layer supports multiple PeerConnections.
    private var activeHostPeer: HostPeerSession? = null
    // Buffer ICE candidates that arrive before the peer is known
    private val hostPendingIceBuffer = mutableListOf<ManualIceCandidate>()
    // ---------------------------------------------------------

    private fun observeConnectionState() {
        connectionObserverJob?.cancel()

        connectionObserverJob = scope.launch {
            transport.webRtcConnectionState.collectLatest { state ->
                when (state) {
                    WebRtcConnectionState.CONNECTING -> {
                        _exchangeState.value = QrExchangeState.Connecting
                    }

                    WebRtcConnectionState.CONNECTED -> {
                        cleanupAfterSuccessfulConnection()
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
                    val candidate = ManualIceCandidate(
                        version = ManualQrProtocol.VERSION,
                        sessionId = sessionId,
                        candidate = dto,
                    )

                    when (role) {
                        Role.HOST -> {
                            // If we have an active peer, add directly, otherwise buffer
                            if (activeHostPeer != null) {
                                activeHostPeer!!.pendingIceCandidates.add(candidate)
                            } else {
                                hostPendingIceBuffer.add(candidate)
                            }
                        }
                        Role.GUEST -> {
                            pendingIceCandidates += candidate
                        }
                        else -> { /* ignore */ }
                    }

                    pendingIceFlushJob?.cancel()
                    pendingIceFlushJob =
                        scope.launch {
                            kotlinx.coroutines.delay(750)
                            flushIceCandidates()
                        }
                } finally {
                    iceMutex.unlock()
                }
            }
        }
    }


    suspend fun startHost() {
        role = Role.HOST
        activeHostPeer = null
        hostPeers.clear()
        hostPendingIceBuffer.clear()
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

        _exchangeState.value =
            QrExchangeState.WaitingForRemoteAnswer
    }

    suspend fun startGuest() {
        role = Role.GUEST
        _exchangeState.value = QrExchangeState.WaitingForOffer
        transport.join()
        startIceCollection()
        observeConnectionState()
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

            val fingerprint = packets.sorted().hashCode()
            val signalPacket = QrCodec.decode(packets)
            val peerSessionId = signalPacket.sessionId

            when (role) {
                Role.HOST -> {
                    val peer = hostPeers.getOrPut(peerSessionId) {
                        HostPeerSession(peerSessionId)
                    }
                    if (peer.lastImportedFingerprint == fingerprint) {
                        return
                    }
                    peer.lastImportedFingerprint = fingerprint
                }
                Role.GUEST -> {
                    if (lastImportedFingerprint == fingerprint) {
                        return
                    }
                    lastImportedFingerprint = fingerprint
                    remoteSessionId = peerSessionId
                }
                null -> error("Session not started")
            }

            when (signalPacket.kind) {
                QrSignalKind.OFFER -> {
                    if (role == Role.HOST) {
                        error("Host received OFFER")
                    } else {
                        val offer = TogetherJson.json.decodeFromString<ManualOffer>(signalPacket.payload)
                        handleOffer(offer)
                    }
                }
                QrSignalKind.ANSWER -> {
                    if (role == Role.HOST) {
                        // Only accept the first answer; reject others to preserve single‑peer behaviour
                        if (activeHostPeer == null) {
                            val answer = TogetherJson.json.decodeFromString<ManualAnswer>(signalPacket.payload)
                            handleAnswer(answer, peerSessionId)
                        } else {
                            // TODO: future multi‑peer will need separate transports
                            // Ignore additional answers for now
                            return
                        }
                    } else {
                        val answer = TogetherJson.json.decodeFromString<ManualAnswer>(signalPacket.payload)
                        handleAnswer(answer)
                    }
                }
                QrSignalKind.ICE -> {
                    if (role == Role.HOST) {
                        handleIceBatchHost(signalPacket)
                    } else {
                        handleIceBatch(signalPacket)
                    }
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
        when (role) {
            Role.HOST -> {
                val peer = activeHostPeer ?: return
                val batch = ManualIceCandidateBatch(
                    sessionId = sessionId,
                    candidates = peer.pendingIceCandidates.toList()
                )
                if (batch.candidates.isEmpty()) return
                val json = TogetherJson.json.encodeToString(batch)
                peer.pendingIceCandidates.clear()
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
            Role.GUEST -> {
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
            else -> { /* ignore */ }
        }
    }

    private suspend fun handleIceBatchHost(packet: QrSignalPacket) {
        val batch =
            TogetherJson.json.decodeFromString<ManualIceCandidateBatch>(
                packet.payload,
            )

        val peer = hostPeers[packet.sessionId]
        // Only process ICE if this is the active peer
        if (peer == null || peer != activeHostPeer) {
            return
        }

        batch.candidates.forEach { candidate ->
            val key = buildString {
                append(candidate.candidate.sdpMid)
                append(':')
                append(candidate.candidate.sdpMLineIndex)
                append(':')
                append(candidate.candidate.candidate)
            }
            if (peer.seenRemoteIce.add(key)) {
                transport.addRemoteIceCandidate(candidate.candidate)
            }
        }
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
            val key = buildString {
                append(it.candidate.sdpMid)
                append(':')
                append(it.candidate.sdpMLineIndex)
                append(':')
                append(it.candidate.candidate)
            }
            if (seenRemoteIce.add(key)) {
                transport.addRemoteIceCandidate(it.candidate)
            }
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
        peerSessionId: String,
    ) {
        // Host side: only one active peer supported
        val peer = hostPeers.getOrPut(peerSessionId) {
            HostPeerSession(peerSessionId)
        }
        activeHostPeer = peer
        peer.remoteSessionId = peerSessionId

        // Assign any buffered ICE candidates to this peer
        if (hostPendingIceBuffer.isNotEmpty()) {
            peer.pendingIceCandidates.addAll(hostPendingIceBuffer)
            hostPendingIceBuffer.clear()
        }

        transport.setRemoteDescription(SessionDescription(sdpTypeFromString(answer.type), answer.sdp))
        _exchangeState.value = QrExchangeState.Connecting
    }

    suspend fun handleAnswer(
        answer: ManualAnswer,
    ) {
        // Guest side: single peer
        transport.setRemoteDescription(SessionDescription(sdpTypeFromString(answer.type), answer.sdp))
        _exchangeState.value = QrExchangeState.Connecting
    }

    private suspend fun flushIceCandidates() {
        iceMutex.lock()
        try {
            val candidates = when (role) {
                Role.HOST -> {
                    val peer = activeHostPeer ?: return
                    peer.pendingIceCandidates
                }
                Role.GUEST -> pendingIceCandidates
                else -> return
            }
            if (candidates.isEmpty()) return

            val batch = ManualIceCandidateBatch(
                version = ManualQrProtocol.VERSION,
                sessionId = sessionId,
                candidates = candidates.toList()
            )

            val json = TogetherJson.json.encodeToString(batch)

            when (role) {
                Role.HOST -> {
                    activeHostPeer?.pendingIceCandidates?.clear()
                }
                Role.GUEST -> pendingIceCandidates.clear()
                else -> { /* ignore */ }
            }

            val packet = QrSignalPacket(
                version = ManualQrProtocol.VERSION,
                sessionId = sessionId,
                kind = QrSignalKind.ICE,
                part = REASSEMBLED_PART,
                total = ManualQrProtocol.QR_COUNT,
                payload = json,
            )

            _qrPackets.value = QrCodec.encode(packet)

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

        // Use appropriate seen set based on role
        when (role) {
            Role.HOST -> {
                val peer = activeHostPeer ?: return
                if (!peer.seenRemoteIce.add(key)) {
                    return
                }
            }
            Role.GUEST -> {
                if (!seenRemoteIce.add(key)) {
                    return
                }
            }
            else -> return
        }

        transport.addRemoteIceCandidate(candidate.candidate)
    }

    fun resetManualQrSession() {
        role = null
        pendingIceCandidates.clear()
        seenRemoteIce.clear()
        remoteSessionId = null
        lastImportedFingerprint = null
        hostPeers.clear()
        activeHostPeer = null
        hostPendingIceBuffer.clear()
        _qrPackets.value = emptyList()
        _exchangeState.value = QrExchangeState.Idle
    }

    private fun cleanupAfterSuccessfulConnection() {
        // Guest only: clear local state; host keeps its peer state for potential reuse
        if (role == Role.GUEST) {
            pendingIceCandidates.clear()
            seenRemoteIce.clear()
            remoteSessionId = null
            lastImportedFingerprint = null
        }
        _qrPackets.value = emptyList()
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
        lastImportedFingerprint = null
        hostPeers.values.forEach { it.pendingIceCandidates.clear() }
        hostPeers.clear()
        activeHostPeer = null
        hostPendingIceBuffer.clear()
    }
}
