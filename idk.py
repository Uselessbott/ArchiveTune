#!/usr/bin/env python3
import re
import sys

FILE_PATH = "app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt"

def find_function_body(content, signature_regex):
    """
    Finds the function body (including the signature) by matching the signature
    and then counting braces to find the matching closing brace.
    Returns the full function text or None.
    """
    # Find the signature
    match = re.search(signature_regex, content, re.MULTILINE)
    if not match:
        return None
    start = match.start()
    # Find the opening brace after the signature
    brace_idx = content.find("{", match.end())
    if brace_idx == -1:
        return None
    # Count braces
    stack = 0
    pos = brace_idx
    while pos < len(content):
        ch = content[pos]
        if ch == '{':
            stack += 1
        elif ch == '}':
            stack -= 1
            if stack == 0:
                end = pos + 1
                return content[start:end]
        pos += 1
    return None

def main():
    try:
        with open(FILE_PATH, 'r') as f:
            content = f.read()
    except FileNotFoundError:
        print(f"Error: {FILE_PATH} not found.", file=sys.stderr)
        sys.exit(1)

    # Define regex patterns for the two functions
    # They start with "fun startTogetherHost(" and end with a closing brace.
    # We'll use a slightly relaxed pattern to handle whitespace.
    host_pattern = r'\bfun\s+startTogetherHost\s*\([^)]*\)\s*\{'
    personal_pattern = r'\bfun\s+startTogetherPersonalHost\s*\([^)]*\)\s*\{'

    # Extract original bodies
    orig_host = find_function_body(content, host_pattern)
    if orig_host is None:
        print("Error: Could not find startTogetherHost function.", file=sys.stderr)
        sys.exit(1)
    orig_personal = find_function_body(content, personal_pattern)
    if orig_personal is None:
        print("Error: Could not find startTogetherPersonalHost function.", file=sys.stderr)
        sys.exit(1)

    # Now we need to replace them. We'll replace the entire body with the new versions.
    # The new versions are defined in the script as strings.
    # We'll use the same extraction logic: we'll replace the original function with the new one.
    # But we need to ensure we don't interfere with other functions.

    # We'll replace the first occurrence of the original function with the new one.
    # We'll use regex substitution, but we need to be careful.
    # We'll escape the original text to avoid regex issues.
    # Instead of using regex substitution, we'll use simple string replace on the original exact text.
    # But we already extracted the exact text, so we can use replace.
    new_content = content.replace(orig_host, NEW_START_TOGETHER_HOST)
    new_content = new_content.replace(orig_personal, NEW_START_TOGETHER_PERSONAL_HOST)

    # Insert the helper functions after the Personal function.
    # We'll find the Personal function in the new content and insert after its closing brace.
    personal_start = new_content.find("fun startTogetherPersonalHost(")
    if personal_start == -1:
        print("Error: Could not find new startTogetherPersonalHost function.", file=sys.stderr)
        sys.exit(1)
    # Find the matching brace (same logic)
    brace_idx = new_content.find("{", personal_start)
    if brace_idx == -1:
        print("Error: Could not find opening brace for Personal function.", file=sys.stderr)
        sys.exit(1)
    stack = 0
    pos = brace_idx
    while pos < len(new_content):
        ch = new_content[pos]
        if ch == '{':
            stack += 1
        elif ch == '}':
            stack -= 1
            if stack == 0:
                end_pos = pos + 1
                break
        pos += 1
    else:
        print("Error: Could not find matching closing brace for Personal function.", file=sys.stderr)
        sys.exit(1)

    # Insert helpers after that closing brace
    before = new_content[:end_pos]
    after = new_content[end_pos:]
    new_content = before + "\n" + HELPER_FUNCTIONS + after

    # Write back
    with open(FILE_PATH, 'w') as f:
        f.write(new_content)
    print("Successfully applied the extraction.")

# The new function definitions (same as before)
NEW_START_TOGETHER_HOST = """    fun startTogetherHost(
        port: Int,
        displayName: String,
        settings: moe.rukamori.archivetune.together.TogetherRoomSettings,
    ) {
        ensureScopesActive()
        scope.launch(SilentHandler) {
            togetherSessionState.value = moe.rukamori.archivetune.together.TogetherSessionState.Idle
        }

        ioScope.launch(SilentHandler) {
            stopTogetherInternal()
            togetherIsOnlineSession = false

            val localIp = getLocalIpv4Address()
            val sessionId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val sessionKey =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val joinInfo =
                moe.rukamori.archivetune.together.TogetherJoinInfo(
                    host = localIp ?: "127.0.0.1",
                    port = port,
                    sessionId = sessionId,
                    sessionKey = sessionKey,
                )
            val joinLink =
                moe.rukamori.archivetune.together.TogetherLink
                    .encode(joinInfo)

            val server = createTogetherServer(
                port = port,
                displayName = displayName,
                settings = settings,
                sessionId = sessionId,
                sessionKey = sessionKey,
            )

            scheduleTogetherHostInactivityTimeout(sessionId)

            scope.launch(SilentHandler) {
                togetherSessionState.value =
                    moe.rukamori.archivetune.together.TogetherSessionState.Hosting(
                        sessionId = sessionId,
                        joinLink = joinLink,
                        localAddressHint = localIp ?: "127.0.0.1",
                        port = port,
                        settings = settings,
                        roomState = null,
                    )
            }

            startBroadcastLoop(server = server, sessionId = sessionId)
        }
    }"""

NEW_START_TOGETHER_PERSONAL_HOST = """    fun startTogetherPersonalHost(
        displayName: String,
        settings: moe.rukamori.archivetune.together.TogetherRoomSettings,
    ) {
        ensureScopesActive()
        scope.launch(SilentHandler) {
            togetherSessionState.value = moe.rukamori.archivetune.together.TogetherSessionState.Idle
        }

        ioScope.launch(SilentHandler) {
            stopTogetherInternal()
            togetherIsOnlineSession = false

            // Generate session details
            val sessionId = java.util.UUID.randomUUID().toString()
            val sessionKey = java.util.UUID.randomUUID().toString()

            // Use the same port as LAN (or could be configurable)
            val port = dataStore.get(TogetherDefaultPortKey, 42117)

            val server = createTogetherServer(
                port = port,
                displayName = displayName,
                settings = settings,
                sessionId = sessionId,
                sessionKey = sessionKey,
            )

            // TODO: Integrate tunnel provider (ngrok, etc.)
            // For now, use a placeholder URL
            val tunnelUrl = "wss://your-ngrok-url.ngrok-free.app/together"
            val wsUrl = tunnelUrl

            // Build join info with wsUrl
            val joinInfo = moe.rukamori.archivetune.together.TogetherJoinInfo(
                host = "tunnel",  // dummy, wsUrl overrides
                port = 443,       // dummy, wsUrl overrides
                sessionId = sessionId,
                sessionKey = sessionKey,
                wsUrl = wsUrl,
            )
            val joinLink = moe.rukamori.archivetune.together.TogetherLink.encode(joinInfo)

            scheduleTogetherHostInactivityTimeout(sessionId)

            scope.launch(SilentHandler) {
                togetherSessionState.value =
                    moe.rukamori.archivetune.together.TogetherSessionState.Hosting(
                        sessionId = sessionId,
                        joinLink = joinLink,
                        localAddressHint = "tunnel",
                        port = port,
                        settings = settings,
                        roomState = null,
                    )
            }

            startBroadcastLoop(server = server, sessionId = sessionId)
        }
    }"""

HELPER_FUNCTIONS = """

    private suspend fun createTogetherServer(
        port: Int,
        displayName: String,
        settings: moe.rukamori.archivetune.together.TogetherRoomSettings,
        sessionId: String,
        sessionKey: String,
    ): moe.rukamori.archivetune.together.TogetherServer {
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
        return server
    }

    private suspend fun startBroadcastLoop(
        server: moe.rukamori.archivetune.together.TogetherServer,
        sessionId: String,
    ) {
        togetherBroadcastJob =
            ioScope.launch(SilentHandler) {
                while (togetherServer === server) {
                    if (togetherAuthorityParticipantId == null || togetherAuthorityParticipantId == togetherHostId) {
                        val state = buildTogetherRoomState(sessionId = sessionId, hostId = togetherHostId)
                        server.broadcastRoomState(state)
                        scope.launch(SilentHandler) {
                            val hosting = togetherSessionState.value as? moe.rukamori.archivetune.together.TogetherSessionState.Hosting
                            if (hosting?.sessionId == sessionId) {
                                togetherSessionState.value =
                                    hosting.copy(
                                        settings = server.currentSettings(),
                                        roomState =
                                            state.copy(
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
    }"""

if __name__ == "__main__":
    main()
