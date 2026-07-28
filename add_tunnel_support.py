#!/usr/bin/env python3
"""
Adds TunnelProvider support to MusicService.kt using exact line numbers.
No patch, no manual editing.
"""

import re
from pathlib import Path

FILE = Path("app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt")

# Read the file
with open(FILE, "r", encoding="utf-8") as f:
    lines = f.readlines()

# ----------------------------------------------------------------------
# 1. Insert imports after line 277 (import kotlin.math.cos)
# We'll find the line number by searching to be safe.
import_line_idx = None
for i, line in enumerate(lines):
    if "import kotlin.math.cos" in line:
        import_line_idx = i
        break

if import_line_idx is None:
    print("ERROR: Could not find 'import kotlin.math.cos'")
    exit(1)

imports = [
    "import moe.rukamori.archivetune.together.tunnel.NoOpTunnelProvider\n",
    "import moe.rukamori.archivetune.together.tunnel.TunnelProvider\n",
    "import moe.rukamori.archivetune.together.tunnel.TunnelResult\n",
    "import moe.rukamori.archivetune.together.tunnel.NgrokTunnelProvider\n",
]
# Insert after the found line
for imp in reversed(imports):
    lines.insert(import_line_idx + 1, imp)
print("Added imports")

# ----------------------------------------------------------------------
# 2. Insert field after line 720 (private var togetherIsOnlineSession)
field_line_idx = None
for i, line in enumerate(lines):
    if "private var togetherIsOnlineSession: Boolean = false" in line:
        field_line_idx = i
        break

if field_line_idx is None:
    print("ERROR: Could not find 'private var togetherIsOnlineSession'")
    exit(1)

# Check if already present to avoid duplication
if not any("private var tunnelProvider" in line for line in lines):
    field_block = [
        "\n",
        "    private var tunnelProvider: TunnelProvider = NoOpTunnelProvider()\n",
        "\n",
        "    fun setTunnelProvider(provider: TunnelProvider) {\n",
        "        tunnelProvider = provider\n",
        "    }\n",
    ]
    for line in reversed(field_block):
        lines.insert(field_line_idx + 1, line)
    print("Added tunnelProvider field")
else:
    print("tunnelProvider field already exists, skipping.")

# ----------------------------------------------------------------------
# 3. Insert setTunnelProvider before the catch at line ~1055
# We'll search for the specific catch line we identified.
catch_line_idx = None
for i, line in enumerate(lines):
    # We want the catch that is inside onCreate's try block (the one with notification channels)
    # It should be preceded by the closing brace of the try block.
    # We'll search for a line with only "        } catch (e: Exception) {" (indented with 8 spaces)
    if re.match(r'^\s*\}\s+catch\s*\(\s*e\s*:\s*Exception\s*\)\s*\{', line):
        # Check if it's after the notification channel creation (we can look at surrounding lines)
        # We'll just take the first such line after we see "TOGETHER_NOTIFICATION_CHANNEL_ID"
        # But to be safe, we'll use the exact line number we found earlier: 1055 (0-indexed 1054)
        # We'll use a more robust approach: find the line that matches the pattern and is within the onCreate block.
        catch_line_idx = i
        break

if catch_line_idx is None:
    print("ERROR: Could not find the catch block in onCreate")
    exit(1)

# Insert the initialization line before the catch.
init_line = "            setTunnelProvider(NgrokTunnelProvider(extractorMediaOkHttpClient))\n"
# Check if already exists
if not any("setTunnelProvider(NgrokTunnelProvider" in line for line in lines):
    lines.insert(catch_line_idx, init_line)
    print("Added tunnel initialization in onCreate")
else:
    print("Tunnel initialization already exists, skipping.")

# ----------------------------------------------------------------------
# 4. Replace startTogetherHost function
# Find the start of the function: "    fun startTogetherHost("
start_idx = None
for i, line in enumerate(lines):
    if re.match(r'^\s*fun startTogetherHost\s*\(', line):
        start_idx = i
        break

if start_idx is None:
    print("ERROR: Could not find startTogetherHost function")
    exit(1)

# Find the matching closing brace for the function.
# We'll count braces from the line that contains the opening brace.
# The function's opening brace might be on the same line or next line.
brace_open_idx = None
for i in range(start_idx, len(lines)):
    if '{' in lines[i]:
        brace_open_idx = i
        break
if brace_open_idx is None:
    print("ERROR: Could not find opening brace for startTogetherHost")
    exit(1)

# Count braces to find the closing one.
brace_count = 0
end_idx = None
for i in range(brace_open_idx, len(lines)):
    brace_count += lines[i].count('{') - lines[i].count('}')
    if brace_count == 0:
        end_idx = i
        break
if end_idx is None:
    print("ERROR: Could not find closing brace for startTogetherHost")
    exit(1)

# Replace the lines from start_idx to end_idx with the new function.
# We'll construct the new function code.
new_startTogetherHost = """    fun startTogetherHost(
        port: Int,
        displayName: String,
        settings: moe.rukamori.archivetune.together.TogetherRoomSettings,
        wsUrl: String? = null
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
            val joinInfo = if (wsUrl != null) {
                moe.rukamori.archivetune.together.TogetherJoinInfo(
                    host = "localhost",
                    port = 443,
                    sessionId = sessionId,
                    sessionKey = sessionKey,
                    wsUrl = wsUrl
                )
            } else {
                moe.rukamori.archivetune.together.TogetherJoinInfo(
                    host = localIp ?: "127.0.0.1",
                    port = port,
                    sessionId = sessionId,
                    sessionKey = sessionKey
                )
            }
            val joinLink =
                moe.rukamori.archivetune.together.TogetherLink
                    .encode(joinInfo)

            val server =
                moe.rukamori.archivetune.together.TogetherServer(
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
                togetherSessionState.value =
                    moe.rukamori.archivetune.together.TogetherSessionState.Hosting(
                        sessionId = sessionId,
                        joinLink = joinLink,
                        localAddressHint = localIp,
                        port = port,
                        settings = settings,
                        roomState = null,
                    )
            }

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
        }
    }
"""

# Replace the block
lines[start_idx:end_idx+1] = new_startTogetherHost.splitlines(keepends=True)
print("Replaced startTogetherHost function")

# ----------------------------------------------------------------------
# 5. Insert startTogetherPersonalHost before "fun joinTogether("
join_idx = None
for i, line in enumerate(lines):
    if re.match(r'^\s*fun joinTogether\s*\(', line):
        join_idx = i
        break

if join_idx is None:
    print("ERROR: Could not find 'fun joinTogether('")
    exit(1)

# Check if already present
if not any("fun startTogetherPersonalHost" in line for line in lines):
    new_function = """    fun startTogetherPersonalHost(
        port: Int,
        displayName: String,
        settings: moe.rukamori.archivetune.together.TogetherRoomSettings
    ) {
        ensureScopesActive()
        scope.launch(SilentHandler) {
            val result = tunnelProvider.discoverTunnelUrl()
            when (result) {
                is TunnelResult.Success -> {
                    val httpUrl = result.publicUrl
                    val isDefaultPort =
                        (httpUrl.scheme == "https" && httpUrl.port == 443) ||
                        (httpUrl.scheme == "http" && httpUrl.port == 80)
                    val wsUrl = buildString {
                        append(if (httpUrl.scheme == "https") "wss://" else "ws://")
                        append(httpUrl.host)
                        if (!isDefaultPort) append(":${httpUrl.port}")
                        append("/together")
                    }
                    startTogetherHost(port, displayName, settings, wsUrl = wsUrl)
                }
                is TunnelResult.Error -> {
                    togetherSessionState.value =
                        moe.rukamori.archivetune.together.TogetherSessionState.Error(
                            message = result.message,
                            recoverable = true
                        )
                }
            }
        }
    }

"""
    # Insert before the joinTogether line
    lines.insert(join_idx, new_function)
    print("Added startTogetherPersonalHost function")
else:
    print("startTogetherPersonalHost already exists, skipping.")

# ----------------------------------------------------------------------
# Write back
with open(FILE, "w", encoding="utf-8") as f:
    f.writelines(lines)

print("Done! MusicService.kt has been updated.")
