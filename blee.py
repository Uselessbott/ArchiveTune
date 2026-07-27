#!/usr/bin/env python3
import os
import re
import sys

BASE = "app/src/main/kotlin/moe/rukamori/archivetune"
TUNNEL_DIR = os.path.join(BASE, "together", "tunnel")
MUSIC_SERVICE = os.path.join(BASE, "playback", "MusicService.kt")

# New file contents (same as above, but with HttpUrl in Success)
TUNNEL_PROVIDER_KT = '''package moe.rukamori.archivetune.together.tunnel

import okhttp3.HttpUrl

sealed interface TunnelResult {
    data class Success(val publicUrl: HttpUrl) : TunnelResult
    data class Error(val message: String) : TunnelResult
}

interface TunnelProvider {
    suspend fun discoverTunnelUrl(): TunnelResult
}
'''

NO_OP_TUNNEL_PROVIDER_KT = '''package moe.rukamori.archivetune.together.tunnel

class NoOpTunnelProvider : TunnelProvider {
    override suspend fun discoverTunnelUrl(): TunnelResult =
        TunnelResult.Error("No tunnel provider configured")
}
'''

NGROK_TUNNEL_PROVIDER_KT = '''package moe.rukamori.archivetune.together.tunnel

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
private data class NgrokTunnel(
    val public_url: String,
    val proto: String,
)

@Serializable
private data class NgrokTunnelsResponse(
    val tunnels: List<NgrokTunnel>,
)

class NgrokTunnelProvider(
    private val client: OkHttpClient,
) : TunnelProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun discoverTunnelUrl(): TunnelResult {
        return try {
            val request = Request.Builder()
                .url("http://127.0.0.1:4040/api/tunnels")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return TunnelResult.Error("Ngrok API returned ${response.code}")
            }

            val body = response.body?.string() ?: return TunnelResult.Error("Empty response from ngrok")
            val parsed = json.decodeFromString<NgrokTunnelsResponse>(body)

            val tunnel = parsed.tunnels
                .firstOrNull { it.proto == "https" || it.proto == "http" }
                ?: return TunnelResult.Error("No HTTP/HTTPS tunnel found in ngrok")

            val publicUrl = tunnel.public_url.toHttpUrlOrNull()
                ?: return TunnelResult.Error("Invalid tunnel URL: $publicUrl")

            TunnelResult.Success(publicUrl)
        } catch (e: IOException) {
            TunnelResult.Error("Failed to reach ngrok: ${e.message}")
        } catch (e: SerializationException) {
            TunnelResult.Error("Failed to parse ngrok response: ${e.message}")
        }
    }
}
'''

# New function body for startTogetherPersonalHost
NEW_PERSONAL_BODY = '''    fun startTogetherPersonalHost(
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

            // Discover tunnel URL from provider
            val tunnelResult = tunnelProvider.discoverTunnelUrl()
            when (tunnelResult) {
                is TunnelResult.Success -> {
                    val publicUrl = tunnelResult.publicUrl
                    // Build WebSocket URL for Together
                    val wsUrl = publicUrl.newBuilder()
                        .scheme(if (publicUrl.isHttps) "wss" else "ws")
                        .addEncodedPathSegment("together")
                        .build()
                        .toString()

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
                is TunnelResult.Error -> {
                    scope.launch(SilentHandler) {
                        togetherSessionState.value =
                            moe.rukamori.archivetune.together.TogetherSessionState.Error(
                                message = tunnelResult.message,
                            )
                    }
                }
            }
        }
    }'''

def find_function_body(text, signature_regex):
    """Locate function by signature and return the whole body (including signature)."""
    match = re.search(signature_regex, text, re.MULTILINE)
    if not match:
        return None
    start = match.start()
    # Find opening brace after signature
    brace_idx = text.find("{", match.end())
    if brace_idx == -1:
        return None
    stack = 0
    pos = brace_idx
    while pos < len(text):
        ch = text[pos]
        if ch == '{':
            stack += 1
        elif ch == '}':
            stack -= 1
            if stack == 0:
                return text[start:pos+1]
        pos += 1
    return None

def create_dirs_and_files():
    os.makedirs(TUNNEL_DIR, exist_ok=True)
    with open(os.path.join(TUNNEL_DIR, "TunnelProvider.kt"), 'w') as f:
        f.write(TUNNEL_PROVIDER_KT)
    with open(os.path.join(TUNNEL_DIR, "NoOpTunnelProvider.kt"), 'w') as f:
        f.write(NO_OP_TUNNEL_PROVIDER_KT)
    with open(os.path.join(TUNNEL_DIR, "NgrokTunnelProvider.kt"), 'w') as f:
        f.write(NGROK_TUNNEL_PROVIDER_KT)
    print("Created tunnel provider files.")

def patch_music_service():
    try:
        with open(MUSIC_SERVICE, 'r') as f:
            content = f.read()
    except FileNotFoundError:
        print(f"Error: {MUSIC_SERVICE} not found.", file=sys.stderr)
        sys.exit(1)

    # 1. Add imports if missing (after last import)
    lines = content.splitlines()
    last_import_index = -1
    for i, line in enumerate(lines):
        if line.startswith("import "):
            last_import_index = i
    if last_import_index == -1:
        print("Error: No import found.", file=sys.stderr)
        sys.exit(1)

    new_imports = [
        "import moe.rukamori.archivetune.together.tunnel.NoOpTunnelProvider",
        "import moe.rukamori.archivetune.together.tunnel.TunnelProvider",
        "import moe.rukamori.archivetune.together.tunnel.TunnelResult",
    ]
    existing_imports = set(line for line in lines if line.startswith("import "))
    missing = [imp for imp in new_imports if imp not in existing_imports]
    if missing:
        insert_pos = last_import_index + 1
        for imp in missing:
            lines.insert(insert_pos, imp)
            insert_pos += 1
        content = "\n".join(lines)

    # 2. Add property and setter after "private var togetherIsOnlineSession"
    marker = "private var togetherIsOnlineSession: Boolean = false"
    if marker not in content:
        print("Error: Could not find the togetherIsOnlineSession property.", file=sys.stderr)
        sys.exit(1)
    marker_index = content.find(marker)
    line_end = content.find("\n", marker_index)
    insert_at = line_end + 1
    property_block = """
    private var tunnelProvider: TunnelProvider = NoOpTunnelProvider()

    fun setTunnelProvider(provider: TunnelProvider) {
        tunnelProvider = provider
    }
"""
    if "tunnelProvider" not in content:
        content = content[:insert_at] + property_block + content[insert_at:]

    # 3. Replace the Personal function body using brace-matching
    sig_regex = r'\bfun\s+startTogetherPersonalHost\s*\([^)]*\)\s*\{'
    old_body = find_function_body(content, sig_regex)
    if old_body is None:
        print("Error: Could not find startTogetherPersonalHost function.", file=sys.stderr)
        sys.exit(1)
    content = content.replace(old_body, NEW_PERSONAL_BODY)

    with open(MUSIC_SERVICE, 'w') as f:
        f.write(content)
    print("Patched MusicService.kt")

if __name__ == "__main__":
    create_dirs_and_files()
    patch_music_service()
    print("All changes applied successfully.")
