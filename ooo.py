#!/usr/bin/env python3
"""
Safe patch for MusicService.kt to add TunnelProvider support and startTogetherPersonalHost().

Verifies all anchors exist, uses only real APIs from the repository.
"""

import re
import sys
from pathlib import Path

FILE = Path("app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt")

# ----------------------------------------------------------------------
# Required imports (only add if missing)
REQUIRED_IMPORTS = [
    "import moe.rukamori.archivetune.together.tunnel.NoOpTunnelProvider",
    "import moe.rukamori.archivetune.together.tunnel.TunnelProvider",
    "import moe.rukamori.archivetune.together.tunnel.TunnelResult",
    "import moe.rukamori.archivetune.together.tunnel.NgrokTunnelProvider",
]

# ----------------------------------------------------------------------
# Field and setter to insert after "private var togetherIsOnlineSession"
FIELD_AND_SETTER = """
    private var tunnelProvider: TunnelProvider = NoOpTunnelProvider()

    fun setTunnelProvider(provider: TunnelProvider) {
        tunnelProvider = provider
    }
"""

# ----------------------------------------------------------------------
# Initialization to insert after "val extractorMediaOkHttpClient ="
INIT_TUNNEL = """
        setTunnelProvider(NgrokTunnelProvider(extractorMediaOkHttpClient))"""

# ----------------------------------------------------------------------
# Clean implementation of startTogetherPersonalHost.
# Uses real APIs:
# - TunnelResult.Success(publicUrl: HttpUrl)
# - TunnelResult.Error(message: String)
# - TogetherJoinInfo(host, port, sessionId, sessionKey, wsUrl)
# - TogetherServer, getLocalIpv4Address(), scheduleTogetherHostInactivityTimeout(),
#   buildTogetherRoomState(), handleTogetherHostEvent(), TogetherPlaybackSync.BroadcastIntervalMs
NEW_FUNCTION = """
    fun startTogetherPersonalHost(
        port: Int,
        displayName: String,
        settings: moe.rukamori.archivetune.together.TogetherRoomSettings,
    ) {
        ensureScopesActive()
        ioScope.launch(SilentHandler) {
            stopTogetherInternal()
            togetherIsOnlineSession = false

            val tunnelResult = tunnelProvider.discoverTunnelUrl()
            when (tunnelResult) {
                is TunnelResult.Success -> {
                    // Use the tunnel public URL as wsUrl
                    val wsUrl = tunnelResult.publicUrl.toString()
                    val sessionId = java.util.UUID.randomUUID().toString()
                    val sessionKey = java.util.UUID.randomUUID().toString()

                    // Join info with wsUrl override (host/port dummy)
                    val joinInfo = moe.rukamori.archivetune.together.TogetherJoinInfo(
                        host = "localhost",
                        port = 443,
                        sessionId = sessionId,
                        sessionKey = sessionKey,
                        wsUrl = wsUrl,
                    )
                    val joinLink = moe.rukamori.archivetune.together.TogetherLink.encode(joinInfo)

                    val server = moe.rukamori.archivetune.together.TogetherServer(
                        scope = ioScope,
                        sessionId = sessionId,
                        sessionKey = sessionKey,
                        hostDisplayName = displayName.trim().ifBlank { getString(R.string.app_name) },
                        initialSettings = settings,
                        hostParticipantId = togetherHostId,
                    )
                    server.onEvent = { event ->
                        ioScope.launch(SilentHandler) {
                            handleTogetherHostEvent(event) { server.currentSettings() }
                        }
                    }
                    server.start(port)
                    togetherServer = server

                    scheduleTogetherHostInactivityTimeout(sessionId)

                    scope.launch(SilentHandler) {
                        togetherSessionState.value = moe.rukamori.archivetune.together.TogetherSessionState.Hosting(
                            sessionId = sessionId,
                            joinLink = joinLink,
                            localAddressHint = getLocalIpv4Address() ?: "127.0.0.1",
                            port = port,
                            settings = settings,
                            roomState = null,
                        )
                    }

                    // Broadcast loop (same pattern as startTogetherHost)
                    togetherBroadcastJob = ioScope.launch(SilentHandler) {
                        while (togetherServer === server) {
                            if (togetherAuthorityParticipantId == null || togetherAuthorityParticipantId == togetherHostId) {
                                val state = buildTogetherRoomState(sessionId = sessionId, hostId = togetherHostId)
                                server.broadcastRoomState(state)
                                scope.launch(SilentHandler) {
                                    val hosting = togetherSessionState.value as? moe.rukamori.archivetune.together.TogetherSessionState.Hosting
                                    if (hosting?.sessionId == sessionId) {
                                        togetherSessionState.value = hosting.copy(
                                            settings = server.currentSettings(),
                                            roomState = state.copy(
                                                participants = server.currentParticipants(),
                                                settings = server.currentSettings(),
                                            ),
                                        )
                                    }
                                }
                            }
                            kotlinx.coroutines.delay(TogetherPlaybackSync.BroadcastIntervalMs)
                        }
                    }
                }
                is TunnelResult.Error -> {
                    scope.launch(SilentHandler) {
                        togetherSessionState.value = moe.rukamori.archivetune.together.TogetherSessionState.Error(
                            message = tunnelResult.message,
                            recoverable = true,
                        )
                    }
                }
            }
        }
    }
"""

# ----------------------------------------------------------------------
def verify_anchor(lines, pattern, description):
    """Ensure a line matching pattern exists; return its index or abort."""
    for i, line in enumerate(lines):
        if re.search(pattern, line):
            return i
    print(f"ERROR: Anchor '{description}' not found in {FILE}. Aborting.")
    sys.exit(1)

def already_has(lines, pattern, description):
    """Return True if a line matching pattern exists."""
    return any(re.search(pattern, line) for line in lines)

def apply_patches():
    if not FILE.exists():
        print(f"ERROR: {FILE} not found", file=sys.stderr)
        sys.exit(1)

    with open(FILE, "r", encoding="utf-8") as f:
        lines = f.readlines()

    # ----- 1. Add missing imports after last import -----
    last_import_idx = -1
    for i, line in enumerate(lines):
        if line.startswith("import "):
            last_import_idx = i
    if last_import_idx == -1:
        print("ERROR: No import statements found. Cannot determine insertion point.")
        sys.exit(1)
    insert_idx = last_import_idx + 1

    existing_imports = {line.strip() for line in lines if line.startswith("import ")}
    missing_imports = [imp for imp in REQUIRED_IMPORTS if imp not in existing_imports]
    if missing_imports:
        for imp in reversed(missing_imports):
            lines.insert(insert_idx, imp + "\n")
        print("Added imports:", missing_imports)

    # ----- 2. Verify anchor for field insertion -----
    anchor_idx = verify_anchor(lines, r"private var togetherIsOnlineSession", "private var togetherIsOnlineSession")
    if not already_has(lines, r"private var tunnelProvider", "tunnelProvider field"):
        lines.insert(anchor_idx + 1, FIELD_AND_SETTER + "\n")
        print("Added tunnelProvider field and setter")
    else:
        print("tunnelProvider field already exists, skipping.")

    # ----- 3. Verify anchor for tunnel initialization -----
    init_idx = verify_anchor(lines, r"val extractorMediaOkHttpClient =", "val extractorMediaOkHttpClient =")
    if not already_has(lines, r"setTunnelProvider\s*\(NgrokTunnelProvider", "setTunnelProvider initialization"):
        lines.insert(init_idx + 1, INIT_TUNNEL + "\n")
        print("Added tunnel provider initialization")
    else:
        print("Tunnel initialization already exists, skipping.")

    # ----- 4. Verify anchor for function insertion (before "fun joinTogether") -----
    join_idx = verify_anchor(lines, r"^(\s*)fun joinTogether\s*\(", "fun joinTogether")
    if not already_has(lines, r"fun startTogetherPersonalHost", "startTogetherPersonalHost function"):
        lines.insert(join_idx, NEW_FUNCTION + "\n")
        print("Added startTogetherPersonalHost function")
    else:
        print("startTogetherPersonalHost already exists, skipping.")

    # ----- Write back -----
    with open(FILE, "w", encoding="utf-8") as f:
        f.writelines(lines)

    print("Patch applied successfully. Please review changes and compile.")

if __name__ == "__main__":
    apply_patches()
