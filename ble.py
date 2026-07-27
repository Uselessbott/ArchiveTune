#!/usr/bin/env python3
"""
Inserts startTogetherPersonalHost method into MusicService.kt
after the closing brace of startTogetherOnlineHost.
"""
import os
import re

FILE_PATH = "app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt"

# The method to insert (using triple quotes for multiline)
NEW_METHOD = """
    fun startTogetherPersonalHost(
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

            // Start the local server (same as LAN)
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

            // Broadcast loop (same as LAN)
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
    }
"""

def main():
    if not os.path.exists(FILE_PATH):
        print(f"Error: {FILE_PATH} not found")
        return

    with open(FILE_PATH, 'r') as f:
        lines = f.readlines()

    # Find the line containing "fun joinTogether(" (start of next method)
    insert_line = None
    for i, line in enumerate(lines):
        if re.search(r'^\s*fun joinTogether\s*\(', line):
            insert_line = i
            break

    if insert_line is None:
        print("Error: Could not find 'fun joinTogether('")
        return

    # The method should be inserted BEFORE the joinTogether line
    # (i.e., after the previous method's closing brace, which is the line before)
    # We'll insert right at insert_line (so it appears before joinTogether)

    # Split the new method into lines (preserve indentation)
    new_lines = NEW_METHOD.splitlines(keepends=True)

    # Insert the new method at the found position
    lines[insert_line:insert_line] = new_lines

    with open(FILE_PATH, 'w') as f:
        f.writelines(lines)

    print(f"Successfully inserted startTogetherPersonalHost into {FILE_PATH}")
    print("Please verify the insertion and compile the project.")

if __name__ == "__main__":
    main()
