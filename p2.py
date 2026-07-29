#!/usr/bin/env python3
"""
Phase 7 integration – WebRTC transport integration.
Uses exact string replacements for safety.
Set DRY_RUN = True to preview; False to apply.
"""
import os
import shutil
from pathlib import Path

DRY_RUN = True   # ← change to False to actually modify files

BASE = Path("app/src/main/kotlin/moe/rukamori/archivetune")
BACKUP_SUFFIX = ".bak"

def backup_file(rel_path):
    full = BASE / rel_path
    if full.exists():
        backup = full.with_suffix(full.suffix + BACKUP_SUFFIX)
        shutil.copy2(full, backup)
        print(f"Backup created: {backup}")

def write_file(rel_path, content):
    full = BASE / rel_path
    full.parent.mkdir(parents=True, exist_ok=True)
    if DRY_RUN:
        print(f"[DRY RUN] Would write: {full}")
        return
    backup_file(rel_path)
    with open(full, "w") as f:
        f.write(content)
    print(f"Written: {full}")

def replace_in_file(rel_path, old, new, required=True):
    full = BASE / rel_path
    if not full.exists():
        print(f"Warning: {full} does not exist, skipping.")
        return False
    with open(full, "r") as f:
        content = f.read()
    if old not in content:
        if required:
            print(f"ERROR: Exact text not found in {full}")
            return False
        else:
            print(f"Warning: exact text not found in {full}")
            return True
    new_content = content.replace(old, new)
    if DRY_RUN:
        print(f"[DRY RUN] Would modify {full} (exact replacement)")
        return True
    backup_file(rel_path)
    with open(full, "w") as f:
        f.write(new_content)
    print(f"Modified: {full}")
    return True

# ==========================================================================
# 1. Write complete TogetherOnlineHost.kt
# ==========================================================================
write_file(
    "together/TogetherOnlineHost.kt",
    """/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.together

import androidx.compose.runtime.Immutable
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.together.webrtc.WebRtcTransport
import org.webrtc.DataChannel
import java.util.UUID
import java.util.concurrent.TimeUnit

@Immutable
sealed class TogetherOnlineHostState {
    data object Idle : TogetherOnlineHostState()
    data object Connecting : TogetherOnlineHostState()
    data class Connected(
        val wsUrl: String,
        val sessionId: String,
        val hostParticipantId: String,
    ) : TogetherOnlineHostState()
}

class TogetherOnlineHost(
    externalScope: CoroutineScope,
    val sessionId: String,
    private val sessionKey: String,
    private val hostId: String,
    private val hostDisplayName: String,
    initialSettings: TogetherRoomSettings,
    clientId: String = UUID.randomUUID().toString(),
    private val bearerToken: String? = null,
    private val webRtcTransport: WebRtcTransport? = null,
    private val useWebRtc: Boolean = false,
) {
    private val client =
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(15, TimeUnit.SECONDS)
                    readTimeout(30, TimeUnit.SECONDS)
                    writeTimeout(15, TimeUnit.SECONDS)
                    pingInterval(25, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                }
            }
            install(WebSockets) {
                pingIntervalMillis = 25_000
            }
        }

    private val scope = CoroutineScope(externalScope.coroutineContext + SupervisorJob())
    private val mutex = Mutex()
    private var settings: TogetherRoomSettings = initialSettings

    private var session: WebSocketSession? = null
    private var loopJob: Job? = null
    private var webRtcReceiveJob: Job? = null
    private var hostParticipantId: String? = null
    private var authorityParticipantId: String? = null

    private val clientId = clientId.trim().ifBlank { UUID.randomUUID().toString() }.take(64)
    private val normalizedBearerToken: String? = bearerToken?.trim()?.takeIf { it.isNotBlank() }

    private data class Guest(
        val participantId: String,
        val clientId: String,
        val name: String,
        var pending: Boolean,
    )

    private val guests = LinkedHashMap<String, Guest>()

    @Volatile
    private var lastParticipants: List<TogetherParticipant> = emptyList()

    var onEvent: ((TogetherServerEvent) -> Unit)? = null

    // ---------- Shared send ----------
    private suspend fun sendMessage(message: TogetherMessage) {
        if (useWebRtc) {
            webRtcTransport?.sendMessage(message)
        } else {
            session?.send(TogetherJson.json.encodeToString(TogetherMessage.serializer(), message))
        }
    }

    // ---------- Shared message processing ----------
    private suspend fun processIncomingMessage(message: TogetherMessage) {
        when (message) {
            is ServerWelcome -> {
                if (message.sessionId == sessionId) {
                    hostParticipantId = message.participantId
                    mutex.withLock { settings = message.settings }
                    authorityParticipantId = hostId
                }
            }
            is JoinRequest -> {
                if (message.sessionId == sessionId) {
                    val participant = message.participant.copy(isHost = false, isConnected = true, isPending = true)
                    guests[participant.id] =
                        Guest(
                            participantId = participant.id,
                            clientId = "",
                            name = participant.name,
                            pending = true,
                        )
                    rebuildParticipantsSnapshot()
                    onEvent?.invoke(TogetherServerEvent.JoinRequested(participant))
                }
            }
            is ParticipantJoined -> {
                if (message.sessionId == sessionId) {
                    val participant = message.participant.copy(isHost = false, isConnected = true, isPending = false)
                    guests[participant.id] =
                        Guest(
                            participantId = participant.id,
                            clientId = "",
                            name = participant.name,
                            pending = false,
                        )
                    rebuildParticipantsSnapshot()
                    onEvent?.invoke(TogetherServerEvent.ParticipantJoined(participant))
                }
            }
            is ParticipantLeft -> {
                if (message.sessionId == sessionId) {
                    guests.remove(message.participantId)
                    if (authorityParticipantId == message.participantId) {
                        authorityParticipantId = hostId
                    }
                    rebuildParticipantsSnapshot()
                    onEvent?.invoke(TogetherServerEvent.ParticipantLeft(message.participantId, message.reason))
                }
            }
            is RoomStateMessage -> {
                if (message.state.sessionId == sessionId) {
                    onEvent?.invoke(TogetherServerEvent.RoomStateReceived(message.state))
                }
            }
            is HostTransferred -> {
                if (message.sessionId == sessionId) {
                    authorityParticipantId = message.participantId
                    rebuildParticipantsSnapshot()
                    onEvent?.invoke(TogetherServerEvent.HostTransferred(message.participantId))
                }
            }
            is ControlRequest -> {
                if (message.sessionId == sessionId) onEvent?.invoke(TogetherServerEvent.ControlRequested(message))
            }
            is AddTrackRequest -> {
                if (message.sessionId == sessionId) onEvent?.invoke(TogetherServerEvent.AddTrackRequested(message))
            }
            is ServerError -> {
                onEvent?.invoke(TogetherServerEvent.Error(message.message, null))
            }
            else -> { /* ignore */ }
        }
    }

    suspend fun connect(wsUrl: String) {
        disconnect()
        hostParticipantId = null
        authorityParticipantId = null
        guests.clear()
        lastParticipants = emptyList()

        if (useWebRtc) {
            webRtcTransport?.host()
            // Start listening to incoming messages
            webRtcReceiveJob = scope.launch {
                webRtcTransport?.receivedMessages?.collect { message ->
                    processIncomingMessage(message)
                }
            }
            // After DataChannel opens, send ClientHello to establish identity
            scope.launch {
                try {
                    webRtcTransport?.connectionState?.first { it == DataChannel.State.OPEN }
                    val hello =
                        ClientHello(
                            protocolVersion = TogetherProtocolVersion,
                            sessionId = sessionId,
                            sessionKey = sessionKey,
                            clientId = clientId,
                            displayName = hostDisplayName.trim(),
                        )
                    sendMessage(hello)
                } catch (_: Exception) {
                    // Connection closed or timeout; handled elsewhere
                }
            }
            // The host participant ID will be set when ServerWelcome arrives.
            return
        }

        val trimmed = wsUrl.trim()
        val urls = listOfNotNull(trimmed, alternateWebSocketSchemeOrNull(trimmed)).distinct()

        val token = normalizedBearerToken
        if (token == null) {
            onEvent?.invoke(TogetherServerEvent.Error("Together token is missing"))
            return
        }

        var lastError: Throwable? = null
        for (candidate in urls) {
            try {
                client.webSocket(
                    urlString = candidate,
                    request = {
                        header("Authorization", "Bearer $token")
                    },
                ) {
                    session = this
                    val hello =
                        ClientHello(
                            protocolVersion = TogetherProtocolVersion,
                            sessionId = sessionId,
                            sessionKey = sessionKey,
                            clientId = clientId,
                            displayName = hostDisplayName.trim(),
                        )
                    sendMessage(hello)
                    runLoop(this, candidate)
                }
                return
            } catch (t: Throwable) {
                lastError = t
            }
        }

        onEvent?.invoke(TogetherServerEvent.Error(connectionFailureMessage(lastError), lastError))
    }

    private fun alternateWebSocketSchemeOrNull(url: String): String? {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("ws://") -> "wss://${trimmed.removePrefix("ws://")}"
            trimmed.startsWith("wss://") -> "ws://${trimmed.removePrefix("wss://")}"
            else -> null
        }
    }

    private fun connectionFailureMessage(t: Throwable?): String {
        val root = generateSequence(t) { it.cause }.lastOrNull()
        val raw = root?.message?.trim().orEmpty()
        val reason =
            when (root) {
                is java.net.UnknownHostException -> "Server not found"
                is java.net.ConnectException -> "Connection refused"
                is java.net.SocketTimeoutException -> "Connection timed out"
                is javax.net.ssl.SSLHandshakeException -> "Secure connection failed"
                is IllegalArgumentException -> {
                    if (raw.contains("ws", ignoreCase = true) &&
                        raw.contains("scheme", ignoreCase = true)
                    ) {
                        "Invalid server websocket URL"
                    } else {
                        null
                    }
                }
                else -> null
            }
        val detail = reason ?: raw.takeIf { it.isNotBlank() }
        return if (detail == null) "Connection failed" else "Connection failed: $detail"
    }

    suspend fun disconnect() {
        webRtcReceiveJob?.cancel()
        webRtcReceiveJob = null
        webRtcTransport?.disconnect()
        loopJob?.cancel()
        loopJob?.cancelAndJoin()
        loopJob = null
        runCatching { session?.close(CloseReason(CloseReason.Codes.NORMAL, "Disconnect")) }
        session = null
        hostParticipantId = null
        authorityParticipantId = null
        guests.clear()
        lastParticipants = emptyList()
    }

    fun currentParticipants(): List<TogetherParticipant> = lastParticipants

    suspend fun currentSettings(): TogetherRoomSettings = mutex.withLock { settings }

    suspend fun updateSettings(newSettings: TogetherRoomSettings) {
        mutex.withLock {
            settings = newSettings
        }
    }

    suspend fun approveParticipant(
        participantId: String,
        approved: Boolean,
    ) {
        val guest = guests[participantId] ?: return
        if (!guest.pending) return

        if (!approved) {
            runCatching {
                sendMessage(JoinDecision(sessionId = sessionId, participantId = participantId, approved = false))
            }
            guest.pending = false
            return
        }

        guest.pending = false
        runCatching {
            sendMessage(JoinDecision(sessionId = sessionId, participantId = participantId, approved = true))
        }
        onEvent?.invoke(
            TogetherServerEvent.ParticipantJoined(
                TogetherParticipant(
                    id = participantId,
                    name = guest.name,
                    isHost = false,
                    isPending = false,
                    isConnected = true,
                ),
            ),
        )
        rebuildParticipantsSnapshot()
    }

    suspend fun kickParticipant(
        participantId: String,
        reason: String?,
    ) {
        if (!guests.containsKey(participantId)) return
        runCatching {
            sendMessage(KickParticipant(sessionId = sessionId, participantId = participantId, reason = reason))
        }
    }

    suspend fun banParticipant(
        participantId: String,
        reason: String?,
    ) {
        if (!guests.containsKey(participantId)) return
        runCatching {
            sendMessage(BanParticipant(sessionId = sessionId, participantId = participantId, reason = reason))
        }
    }

    suspend fun transferHostOwnership(participantId: String) {
        val guest = guests[participantId] ?: return
        if (guest.pending) return
        runCatching {
            sendMessage(HostTransfer(sessionId = sessionId, participantId = participantId))
        }
    }

    suspend fun broadcastRoomState(state: TogetherRoomState) {
        val snapshotSettings = mutex.withLock { settings }
        val activeHostId = authorityParticipantId ?: hostId
        rebuildParticipantsSnapshot()
        val roomState =
            state.copy(
                hostId = activeHostId,
                settings = snapshotSettings,
                participants = lastParticipants,
            )

        runCatching {
            sendMessage(RoomStateMessage(roomState))
        }
    }

    private fun rebuildParticipantsSnapshot() {
        val activeHostId = authorityParticipantId ?: hostId
        val host =
            TogetherParticipant(
                id = hostId,
                name = hostDisplayName,
                isHost = activeHostId == hostId,
                isPending = false,
                isConnected = true,
            )

        val guestList =
            guests.values
                .sortedBy { it.name.lowercase() }
                .map {
                    TogetherParticipant(
                        id = it.participantId,
                        name = it.name,
                        isHost = it.participantId == activeHostId,
                        isPending = it.pending,
                        isConnected = true,
                    )
                }

        lastParticipants =
            buildList {
                add(host)
                addAll(guestList)
            }
    }

    // ---------- WebSocket loop ----------
    private suspend fun runLoop(
        session: WebSocketSession,
        wsUrl: String,
    ) {
        loopJob =
            scope.launch {
                try {
                    while (true) {
                        val frame =
                            try {
                                session.incoming.receive()
                            } catch (_: ClosedReceiveChannelException) {
                                break
                            }

                        val text = (frame as? Frame.Text)?.readText() ?: continue
                        val message =
                            runCatching { TogetherJson.json.decodeFromString(TogetherMessage.serializer(), text) }
                                .getOrElse {
                                    onEvent?.invoke(TogetherServerEvent.Error("Failed to decode message", it))
                                    continue
                                }
                        processIncomingMessage(message)
                    }
                } catch (t: Throwable) {
                    onEvent?.invoke(TogetherServerEvent.Error("Connection loop failed", t))
                } finally {
                    hostParticipantId = null
                    authorityParticipantId = null
                    guests.clear()
                    lastParticipants = emptyList()
                    runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, "Disconnected")) }
                    onEvent?.invoke(TogetherServerEvent.Error("Disconnected", null))
                }
            }
        loopJob?.join()
    }
}
"""
)

# ==========================================================================
# 2. Write complete TogetherClient.kt
# ==========================================================================
write_file(
    "together/TogetherClient.kt",
    """/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.together

import androidx.compose.runtime.Immutable
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.together.webrtc.WebRtcTransport
import org.webrtc.DataChannel
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed interface TogetherClientEvent {
    data class Welcome(val welcome: ServerWelcome) : TogetherClientEvent
    data class RoomState(val state: TogetherRoomState) : TogetherClientEvent
    data class JoinDecision(val decision: JoinDecision) : TogetherClientEvent
    data class HostTransferred(val transfer: HostTransferred) : TogetherClientEvent
    data class ControlRequested(val request: ControlRequest) : TogetherClientEvent
    data class AddTrackRequested(val request: AddTrackRequest) : TogetherClientEvent
    data class ServerIssue(val message: String, val code: String? = null) : TogetherClientEvent
    data class Error(val message: String, val throwable: Throwable? = null) : TogetherClientEvent
    data class HeartbeatPong(val pong: HeartbeatPong, val receivedAtElapsedRealtimeMs: Long) : TogetherClientEvent
    data object Disconnected : TogetherClientEvent
}

@Immutable
sealed class TogetherClientState {
    data object Idle : TogetherClientState()
    data class Connecting(val joinInfo: TogetherJoinInfo) : TogetherClientState()
    data class Connected(val session: TogetherJoinInfo) : TogetherClientState()
    data class ConnectingRemote(val wsUrl: String, val sessionId: String) : TogetherClientState()
    data class ConnectedRemote(val wsUrl: String, val sessionId: String) : TogetherClientState()
}

class TogetherClient(
    private val externalScope: CoroutineScope,
    clientId: String = UUID.randomUUID().toString(),
    private val bearerToken: String? = null,
    private val webRtcTransport: WebRtcTransport? = null,
    private val useWebRtc: Boolean = false,
) {
    private val client =
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(15, TimeUnit.SECONDS)
                    readTimeout(30, TimeUnit.SECONDS)
                    writeTimeout(15, TimeUnit.SECONDS)
                    pingInterval(25, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                }
            }
            install(WebSockets) {
                pingIntervalMillis = 25_000
            }
        }

    private val scope = CoroutineScope(externalScope.coroutineContext + SupervisorJob())

    private val _state = MutableStateFlow<TogetherClientState>(TogetherClientState.Idle)
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<TogetherClientEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    private var session: WebSocketSession? = null
    private var loopJob: Job? = null
    private var webRtcReceiveJob: Job? = null
    private var selfParticipantId: String? = null
    private val clientId = clientId.trim().ifBlank { UUID.randomUUID().toString() }.take(64)
    private val normalizedBearerToken: String? = bearerToken?.trim()?.takeIf { it.isNotBlank() }

    // ---------- Shared send ----------
    private suspend fun sendMessage(message: TogetherMessage) {
        if (useWebRtc) {
            webRtcTransport?.sendMessage(message)
        } else {
            session?.send(TogetherJson.json.encodeToString(TogetherMessage.serializer(), message))
        }
    }

    // ---------- Shared message processing ----------
    private suspend fun processIncomingMessage(message: TogetherMessage) {
        when (message) {
            is ServerWelcome -> {
                if (message.sessionId == sessionId ?: return) {
                    selfParticipantId = message.participantId
                    _events.tryEmit(TogetherClientEvent.Welcome(message))
                }
            }
            is RoomStateMessage -> {
                if (message.state.sessionId == sessionId ?: return) {
                    _events.tryEmit(TogetherClientEvent.RoomState(message.state))
                }
            }
            is JoinDecision -> {
                if (message.sessionId == sessionId && message.participantId == selfParticipantId) {
                    _events.tryEmit(TogetherClientEvent.JoinDecision(message))
                }
            }
            is HostTransferred -> {
                if (message.sessionId == sessionId) {
                    _events.tryEmit(TogetherClientEvent.HostTransferred(message))
                }
            }
            is KickParticipant -> {
                if (message.sessionId == sessionId && message.participantId == selfParticipantId) {
                    val detail = message.reason?.trim().orEmpty().ifBlank { "Kicked" }
                    _events.tryEmit(TogetherClientEvent.Error(detail, null))
                }
            }
            is BanParticipant -> {
                if (message.sessionId == sessionId && message.participantId == selfParticipantId) {
                    val detail = message.reason?.trim().orEmpty().ifBlank { "Banned" }
                    _events.tryEmit(TogetherClientEvent.Error(detail, null))
                }
            }
            is HeartbeatPong -> {
                if (message.sessionId == sessionId) {
                    _events.tryEmit(
                        TogetherClientEvent.HeartbeatPong(
                            pong = message,
                            receivedAtElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
                        )
                    )
                }
            }
            is ControlRequest -> {
                if (message.sessionId == sessionId) {
                    _events.tryEmit(TogetherClientEvent.ControlRequested(message))
                }
            }
            is AddTrackRequest -> {
                if (message.sessionId == sessionId) {
                    _events.tryEmit(TogetherClientEvent.AddTrackRequested(message))
                }
            }
            is ServerError -> {
                _events.tryEmit(TogetherClientEvent.ServerIssue(message = message.message, code = message.code))
            }
            else -> { /* ignore */ }
        }
    }

    fun connect(
        joinInfo: TogetherJoinInfo,
        displayName: String,
    ) {
        scope.launch {
            disconnect()
            _state.value = TogetherClientState.Connecting(joinInfo)

            if (useWebRtc) {
                webRtcTransport?.join()
                // Start listening to incoming messages
                webRtcReceiveJob = scope.launch {
                    webRtcTransport?.receivedMessages?.collect { message ->
                        processIncomingMessage(message)
                    }
                }
                // After DataChannel opens, send ClientHello
                scope.launch {
                    try {
                        webRtcTransport?.connectionState?.first { it == DataChannel.State.OPEN }
                        val hello =
                            ClientHello(
                                protocolVersion = TogetherProtocolVersion,
                                sessionId = joinInfo.sessionId,
                                sessionKey = joinInfo.sessionKey,
                                clientId = clientId,
                                displayName = displayName.trim(),
                            )
                        sendMessage(hello)
                        _state.value = TogetherClientState.Connected(joinInfo)
                    } catch (_: Exception) {
                        // Connection closed or timeout
                    }
                }
                return@launch
            }

            val wsUrl = joinInfo.toWebSocketUrl()
            val urls = listOfNotNull(wsUrl, alternateWebSocketSchemeOrNull(wsUrl)).distinct()

            val token = normalizedBearerToken

            var lastError: Throwable? = null
            for (candidate in urls) {
                try {
                    client.webSocket(
                        urlString = candidate,
                        request = {
                            if (token != null) header("Authorization", "Bearer $token")
                        },
                    ) {
                        session = this
                        val hello =
                            ClientHello(
                                protocolVersion = TogetherProtocolVersion,
                                sessionId = joinInfo.sessionId,
                                sessionKey = joinInfo.sessionKey,
                                clientId = clientId,
                                displayName = displayName.trim(),
                            )
                        sendMessage(hello)
                        _state.value = TogetherClientState.Connected(joinInfo)
                        runLoop(this, joinInfo.sessionId)
                    }
                    return@launch
                } catch (t: Throwable) {
                    lastError = t
                }
            }

            _events.tryEmit(TogetherClientEvent.Error(connectionFailureMessage(lastError), lastError))
            _state.value = TogetherClientState.Idle
        }
    }

    fun connect(
        wsUrl: String,
        sessionId: String,
        sessionKey: String,
        displayName: String,
    ) {
        scope.launch {
            disconnect()
            _state.value = TogetherClientState.ConnectingRemote(wsUrl = wsUrl, sessionId = sessionId)

            if (useWebRtc) {
                webRtcTransport?.join()
                webRtcReceiveJob = scope.launch {
                    webRtcTransport?.receivedMessages?.collect { message ->
                        processIncomingMessage(message)
                    }
                }
                scope.launch {
                    try {
                        webRtcTransport?.connectionState?.first { it == DataChannel.State.OPEN }
                        val hello =
                            ClientHello(
                                protocolVersion = TogetherProtocolVersion,
                                sessionId = sessionId,
                                sessionKey = sessionKey,
                                clientId = clientId,
                                displayName = displayName.trim().ifBlank { "Guest" },
                            )
                        sendMessage(hello)
                        _state.value = TogetherClientState.ConnectedRemote(wsUrl = wsUrl, sessionId = sessionId)
                    } catch (_: Exception) {
                        // Connection closed or timeout
                    }
                }
                return@launch
            }

            val urls = listOfNotNull(wsUrl.trim(), alternateWebSocketSchemeOrNull(wsUrl.trim())).distinct()

            val token = normalizedBearerToken

            var lastError: Throwable? = null
            for (candidate in urls) {
                try {
                    client.webSocket(
                        urlString = candidate,
                        request = {
                            if (token != null) header("Authorization", "Bearer $token")
                        },
                    ) {
                        session = this
                        val hello =
                            ClientHello(
                                protocolVersion = TogetherProtocolVersion,
                                sessionId = sessionId,
                                sessionKey = sessionKey,
                                clientId = clientId,
                                displayName = displayName.trim().ifBlank { "Guest" },
                            )
                        sendMessage(hello)
                        _state.value = TogetherClientState.ConnectedRemote(wsUrl = candidate, sessionId = sessionId)
                        runLoop(this, sessionId)
                    }
                    return@launch
                } catch (t: Throwable) {
                    lastError = t
                }
            }

            _events.tryEmit(TogetherClientEvent.Error(connectionFailureMessage(lastError), lastError))
            _state.value = TogetherClientState.Idle
        }
    }

    private fun alternateWebSocketSchemeOrNull(url: String): String? {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("ws://") -> "wss://${trimmed.removePrefix("ws://")}"
            trimmed.startsWith("wss://") -> "ws://${trimmed.removePrefix("wss://")}"
            else -> null
        }
    }

    private fun connectionFailureMessage(t: Throwable?): String {
        val root = generateSequence(t) { it.cause }.lastOrNull()
        val raw = root?.message?.trim().orEmpty()
        val reason =
            when (root) {
                is java.net.UnknownHostException -> "Server not found"
                is java.net.ConnectException -> "Connection refused"
                is java.net.SocketTimeoutException -> "Connection timed out"
                is javax.net.ssl.SSLHandshakeException -> "Secure connection failed"
                is IllegalArgumentException -> {
                    if (raw.contains("ws", ignoreCase = true) &&
                        raw.contains("scheme", ignoreCase = true)
                    ) {
                        "Invalid server websocket URL"
                    } else {
                        null
                    }
                }
                else -> null
            }
        val detail = reason ?: raw.takeIf { it.isNotBlank() }
        return if (detail == null) "Connection failed" else "Connection failed: $detail"
    }

    suspend fun disconnect() {
        webRtcReceiveJob?.cancel()
        webRtcReceiveJob = null
        webRtcTransport?.disconnect()
        loopJob?.cancel()
        loopJob?.cancelAndJoin()
        loopJob = null
        runCatching { session?.close(CloseReason(CloseReason.Codes.NORMAL, "Disconnect")) }
        session = null
        selfParticipantId = null
        _state.value = TogetherClientState.Idle
    }

    fun requestControl(
        sessionId: String,
        action: ControlAction,
    ) {
        val pid = selfParticipantId ?: return
        scope.launch {
            sendMessage(ControlRequest(sessionId = sessionId, participantId = pid, action = action))
        }
    }

    fun requestAddTrack(
        sessionId: String,
        track: TogetherTrack,
        mode: AddTrackMode,
    ) {
        val pid = selfParticipantId ?: return
        scope.launch {
            sendMessage(AddTrackRequest(sessionId = sessionId, participantId = pid, track = track, mode = mode))
        }
    }

    fun sendRoomState(state: TogetherRoomState) {
        scope.launch {
            sendMessage(RoomStateMessage(state))
        }
    }

    fun transferHostOwnership(
        sessionId: String,
        participantId: String,
    ) {
        scope.launch {
            sendMessage(HostTransfer(sessionId = sessionId, participantId = participantId))
        }
    }

    fun sendHeartbeat(
        sessionId: String,
        pingId: Long,
        clientElapsedRealtimeMs: Long,
    ) {
        scope.launch {
            sendMessage(
                HeartbeatPing(
                    sessionId = sessionId,
                    pingId = pingId,
                    clientElapsedRealtimeMs = clientElapsedRealtimeMs,
                )
            )
        }
    }

    // ---------- WebSocket loop ----------
    private suspend fun runLoop(
        session: WebSocketSession,
        sessionId: String,
    ) {
        loopJob =
            scope.launch {
                try {
                    while (true) {
                        val frame =
                            try {
                                session.incoming.receive()
                            } catch (_: ClosedReceiveChannelException) {
                                break
                            }

                        val text = (frame as? Frame.Text)?.readText() ?: continue
                        val message =
                            runCatching { TogetherJson.json.decodeFromString(TogetherMessage.serializer(), text) }
                                .getOrElse {
                                    _events.tryEmit(TogetherClientEvent.Error("Failed to decode message", it))
                                    continue
                                }
                        processIncomingMessage(message)
                    }
                } catch (t: Throwable) {
                    _events.tryEmit(TogetherClientEvent.Error("Connection loop failed", t))
                } finally {
                    _events.tryEmit(TogetherClientEvent.Disconnected)
                    _state.value = TogetherClientState.Idle
                }
            }
        loopJob?.join()
    }

    private val sessionId: String?
        get() = when (val s = _state.value) {
            is TogetherClientState.Connected -> s.session.sessionId
            is TogetherClientState.ConnectedRemote -> s.sessionId
            else -> null
        }
}
"""
)

# ==========================================================================
# 3. Modify MusicService.kt with exact string replacements
# ==========================================================================

# Add import: we'll add after the last import. We'll use a simple regex to find the last import.
# But we can also add it at the top after package. Since we don't have the exact import list,
# we'll add a new import line after the package.
def add_import_music_service():
    full = BASE / "playback/MusicService.kt"
    if not full.exists():
        print("MusicService.kt not found")
        return False
    with open(full, "r") as f:
        content = f.read()
    # Find the package line
    import_line = "import moe.rukamori.archivetune.together.webrtc.WebRtcTransport"
    if import_line in content:
        print("Import already present")
        return True
    # Insert after the last import. We'll find the line that starts with "import " and is the last one.
    lines = content.splitlines(keepends=True)
    last_import_idx = -1
    for i, line in enumerate(lines):
        if line.strip().startswith("import "):
            last_import_idx = i
    if last_import_idx == -1:
        # If no import, insert after package
        for i, line in enumerate(lines):
            if line.startswith("package "):
                lines.insert(i+1, import_line + "\n")
                break
        else:
            # fallback: insert at top
            lines.insert(0, import_line + "\n")
    else:
        lines.insert(last_import_idx+1, import_line + "\n")
    new_content = "".join(lines)
    if DRY_RUN:
        print("[DRY RUN] Would add import to MusicService.kt")
        return True
    backup_file("playback/MusicService.kt")
    with open(full, "w") as f:
        f.write(new_content)
    print("Added import to MusicService.kt")
    return True

# Add field: inject WebRtcTransport
def add_field_music_service():
    full = BASE / "playback/MusicService.kt"
    if not full.exists():
        return False
    with open(full, "r") as f:
        content = f.read()
    field_text = "    @Inject\n    lateinit var webRtcTransport: WebRtcTransport"
    # Find the line with "@Inject lateinit var database: MusicDatabase"
    old_line = "@Inject\n    lateinit var database: MusicDatabase"
    if old_line not in content:
        print("ERROR: Could not find database injection line")
        return False
    new_content = content.replace(old_line, old_line + "\n\n" + field_text)
    if DRY_RUN:
        print("[DRY RUN] Would add field to MusicService.kt")
        return True
    backup_file("playback/MusicService.kt")
    with open(full, "w") as f:
        f.write(new_content)
    print("Added field to MusicService.kt")
    return True

# Modify startTogetherOnlineHost signature
def modify_start_host_signature():
    full = BASE / "playback/MusicService.kt"
    if not full.exists():
        return False
    with open(full, "r") as f:
        content = f.read()
    old_sig = """    fun startTogetherOnlineHost(
        displayName: String,
        settings: moe.rukamori.archivetune.together.TogetherRoomSettings,
    ) {"""
    new_sig = """    fun startTogetherOnlineHost(
        displayName: String,
        settings: moe.rukamori.archivetune.together.TogetherRoomSettings,
        useWebRtc: Boolean = false,
    ) {"""
    if old_sig not in content:
        print("ERROR: Could not find startTogetherOnlineHost signature")
        return False
    new_content = content.replace(old_sig, new_sig)
    if DRY_RUN:
        print("[DRY RUN] Would modify startTogetherOnlineHost signature")
        return True
    backup_file("playback/MusicService.kt")
    with open(full, "w") as f:
        f.write(new_content)
    print("Modified startTogetherOnlineHost signature")
    return True

# Modify TogetherOnlineHost constructor call
def modify_online_host_constructor():
    full = BASE / "playback/MusicService.kt"
    if not full.exists():
        return False
    with open(full, "r") as f:
        content = f.read()
    old_constructor = """            val onlineHost =
                moe.rukamori.archivetune.together.TogetherOnlineHost(
                    externalScope = ioScope,
                    sessionId = created.sessionId,
                    sessionKey = created.hostKey,
                    hostId = togetherHostId,
                    hostDisplayName = hostName,
                    initialSettings = created.settings,
                    clientId = getOrCreateTogetherClientId(),
                    bearerToken = togetherToken,
                )"""
    new_constructor = """            val onlineHost =
                moe.rukamori.archivetune.together.TogetherOnlineHost(
                    webRtcTransport = webRtcTransport,
                    useWebRtc = useWebRtc,
                    externalScope = ioScope,
                    sessionId = created.sessionId,
                    sessionKey = created.hostKey,
                    hostId = togetherHostId,
                    hostDisplayName = hostName,
                    initialSettings = created.settings,
                    clientId = getOrCreateTogetherClientId(),
                    bearerToken = togetherToken,
                )"""
    if old_constructor not in content:
        print("ERROR: Could not find TogetherOnlineHost constructor")
        return False
    new_content = content.replace(old_constructor, new_constructor)
    if DRY_RUN:
        print("[DRY RUN] Would modify TogetherOnlineHost constructor")
        return True
    backup_file("playback/MusicService.kt")
    with open(full, "w") as f:
        f.write(new_content)
    print("Modified TogetherOnlineHost constructor")
    return True

# Modify joinTogether signature
def modify_join_together_signature():
    full = BASE / "playback/MusicService.kt"
    if not full.exists():
        return False
    with open(full, "r") as f:
        content = f.read()
    old_sig = """    fun joinTogether(
        rawLink: String,
        displayName: String,
    ) {"""
    new_sig = """    fun joinTogether(
        rawLink: String,
        displayName: String,
        useWebRtc: Boolean = false,
    ) {"""
    if old_sig not in content:
        print("ERROR: Could not find joinTogether signature")
        return False
    new_content = content.replace(old_sig, new_sig)
    if DRY_RUN:
        print("[DRY RUN] Would modify joinTogether signature")
        return True
    backup_file("playback/MusicService.kt")
    with open(full, "w") as f:
        f.write(new_content)
    print("Modified joinTogether signature")
    return True

# Modify joinTogether client constructor
def modify_join_together_client():
    full = BASE / "playback/MusicService.kt"
    if not full.exists():
        return False
    with open(full, "r") as f:
        content = f.read()
    old_client = """            val client =
                moe.rukamori.archivetune.together.TogetherClient(
                    ioScope,
                    clientId = getOrCreateTogetherClientId(),
                )"""
    new_client = """            val client =
                moe.rukamori.archivetune.together.TogetherClient(
                    webRtcTransport = webRtcTransport,
                    useWebRtc = useWebRtc,
                    ioScope,
                    clientId = getOrCreateTogetherClientId(),
                )"""
    if old_client not in content:
        print("ERROR: Could not find joinTogether client constructor")
        return False
    new_content = content.replace(old_client, new_client)
    if DRY_RUN:
        print("[DRY RUN] Would modify joinTogether client constructor")
        return True
    backup_file("playback/MusicService.kt")
    with open(full, "w") as f:
        f.write(new_content)
    print("Modified joinTogether client constructor")
    return True

# Modify joinTogetherOnline signature
def modify_join_online_signature():
    full = BASE / "playback/MusicService.kt"
    if not full.exists():
        return False
    with open(full, "r") as f:
        content = f.read()
    old_sig = """    fun joinTogetherOnline(
        code: String,
        displayName: String,
    ) {"""
    new_sig = """    fun joinTogetherOnline(
        code: String,
        displayName: String,
        useWebRtc: Boolean = false,
    ) {"""
    if old_sig not in content:
        print("ERROR: Could not find joinTogetherOnline signature")
        return False
    new_content = content.replace(old_sig, new_sig)
    if DRY_RUN:
        print("[DRY RUN] Would modify joinTogetherOnline signature")
        return True
    backup_file("playback/MusicService.kt")
    with open(full, "w") as f:
        f.write(new_content)
    print("Modified joinTogetherOnline signature")
    return True

# Modify joinTogetherOnline client constructor
def modify_join_online_client():
    full = BASE / "playback/MusicService.kt"
    if not full.exists():
        return False
    with open(full, "r") as f:
        content = f.read()
    old_client = """            val client =
                moe.rukamori.archivetune.together.TogetherClient(
                    ioScope,
                    clientId = getOrCreateTogetherClientId(),
                    bearerToken = togetherToken,
                )"""
    new_client = """            val client =
                moe.rukamori.archivetune.together.TogetherClient(
                    webRtcTransport = webRtcTransport,
                    useWebRtc = useWebRtc,
                    ioScope,
                    clientId = getOrCreateTogetherClientId(),
                    bearerToken = togetherToken,
                )"""
    if old_client not in content:
        print("ERROR: Could not find joinTogetherOnline client constructor")
        return False
    new_content = content.replace(old_client, new_client)
    if DRY_RUN:
        print("[DRY RUN] Would modify joinTogetherOnline client constructor")
        return True
    backup_file("playback/MusicService.kt")
    with open(full, "w") as f:
        f.write(new_content)
    print("Modified joinTogetherOnline client constructor")
    return True

# Apply all MusicService modifications
add_import_music_service()
add_field_music_service()
modify_start_host_signature()
modify_online_host_constructor()
modify_join_together_signature()
modify_join_together_client()
modify_join_online_signature()
modify_join_online_client()

# ==========================================================================
# 4. Modify MusicTogetherRepository.kt with exact string replacements
# ==========================================================================

# Modify startSession signature
def modify_repo_start_session():
    full = BASE / "together/MusicTogetherRepository.kt"
    if not full.exists():
        return False
    with open(full, "r") as f:
        content = f.read()
    old_sig = """        fun startSession(
            mode: MusicTogetherConnectionMode,
            displayName: String,
            port: Int,
            settings: TogetherRoomSettings,
        ) {"""
    new_sig = """        fun startSession(
            mode: MusicTogetherConnectionMode,
            displayName: String,
            port: Int,
            settings: TogetherRoomSettings,
            useWebRtc: Boolean = false,
        ) {"""
    if old_sig not in content:
        print("ERROR: Could not find startSession signature")
        return False
    new_content = content.replace(old_sig, new_sig)
    if DRY_RUN:
        print("[DRY RUN] Would modify startSession signature")
        return True
    backup_file("together/MusicTogetherRepository.kt")
    with open(full, "w") as f:
        f.write(new_content)
    print("Modified startSession signature")
    return True

# Modify service.startTogetherOnlineHost call
def modify_repo_start_call():
    full = BASE / "together/MusicTogetherRepository.kt"
    if not full.exists():
        return False
    with open(full, "r") as f:
        content = f.read()
    old_call = """                    service.startTogetherOnlineHost(
                        displayName = displayName,
                        settings = settings,
                    )"""
    new_call = """                    service.startTogetherOnlineHost(
                        displayName = displayName,
                        settings = settings,
                        useWebRtc = useWebRtc,
                    )"""
    if old_call not in content:
        print("ERROR: Could not find startTogetherOnlineHost call")
        return False
    new_content = content.replace(old_call, new_call)
    if DRY_RUN:
        print("[DRY RUN] Would modify startTogetherOnlineHost call")
        return True
    backup_file("together/MusicTogetherRepository.kt")
    with open(full, "w") as f:
        f.write(new_content)
    print("Modified startTogetherOnlineHost call")
    return True

# Modify joinSession signature
def modify_repo_join_session():
    full = BASE / "together/MusicTogetherRepository.kt"
    if not full.exists():
        return False
    with open(full, "r") as f:
        content = f.read()
    old_sig = """        fun joinSession(
            mode: MusicTogetherConnectionMode,
            rawInput: String,
            displayName: String,
        ) {"""
    new_sig = """        fun joinSession(
            mode: MusicTogetherConnectionMode,
            rawInput: String,
            displayName: String,
            useWebRtc: Boolean = false,
        ) {"""
    if old_sig not in content:
        print("ERROR: Could not find joinSession signature")
        return False
    new_content = content.replace(old_sig, new_sig)
    if DRY_RUN:
        print("[DRY RUN] Would modify joinSession signature")
        return True
    backup_file("together/MusicTogetherRepository.kt")
    with open(full, "w") as f:
        f.write(new_content)
    print("Modified joinSession signature")
    return True

# Modify service.joinTogether call (the line style)
def modify_repo_join_call():
    full = BASE / "together/MusicTogetherRepository.kt"
    if not full.exists():
        return False
    with open(full, "r") as f:
        content = f.read()
    # The line is: MusicTogetherConnectionMode.LAN -> service.joinTogether(rawInput, displayName)
    old_call = "MusicTogetherConnectionMode.LAN -> service.joinTogether(rawInput, displayName)"
    new_call = "MusicTogetherConnectionMode.LAN -> service.joinTogether(rawInput, displayName, useWebRtc)"
    if old_call not in content:
        # Try alternative: maybe it's without the mode, just the call.
        old_call_alt = "service.joinTogether(rawInput, displayName)"
        new_call_alt = "service.joinTogether(rawInput, displayName, useWebRtc)"
        if old_call_alt in content:
            new_content = content.replace(old_call_alt, new_call_alt)
        else:
            print("ERROR: Could not find joinTogether call")
            return False
    else:
        new_content = content.replace(old_call, new_call)
    if DRY_RUN:
        print("[DRY RUN] Would modify joinTogether call")
        return True
    backup_file("together/MusicTogetherRepository.kt")
    with open(full, "w") as f:
        f.write(new_content)
    print("Modified joinTogether call")
    return True

# Modify service.joinTogetherOnline call
def modify_repo_join_online_call():
    full = BASE / "together/MusicTogetherRepository.kt"
    if not full.exists():
        return False
    with open(full, "r") as f:
        content = f.read()
    old_call = "MusicTogetherConnectionMode.ONLINE -> service.joinTogetherOnline(rawInput, displayName)"
    new_call = "MusicTogetherConnectionMode.ONLINE -> service.joinTogetherOnline(rawInput, displayName, useWebRtc)"
    if old_call not in content:
        old_call_alt = "service.joinTogetherOnline(rawInput, displayName)"
        new_call_alt = "service.joinTogetherOnline(rawInput, displayName, useWebRtc)"
        if old_call_alt in content:
            new_content = content.replace(old_call_alt, new_call_alt)
        else:
            print("ERROR: Could not find joinTogetherOnline call")
            return False
    else:
        new_content = content.replace(old_call, new_call)
    if DRY_RUN:
        print("[DRY RUN] Would modify joinTogetherOnline call")
        return True
    backup_file("together/MusicTogetherRepository.kt")
    with open(full, "w") as f:
        f.write(new_content)
    print("Modified joinTogetherOnline call")
    return True

# Apply all MusicTogetherRepository modifications
modify_repo_start_session()
modify_repo_start_call()
modify_repo_join_session()
modify_repo_join_call()
modify_repo_join_online_call()

print("========================================")
if DRY_RUN:
    print("DRY RUN – no files changed. Set DRY_RUN=False to apply.")
else:
    print("PHASE 7 COMPLETED")
print("NEXT PHASE: 8")
print("DO NOT CONTINUE UNTIL USER CONFIRMS.")
print("========================================")
