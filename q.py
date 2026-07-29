#!/usr/bin/env python3

from pathlib import Path
import shutil
import sys

ROOT = Path("app/src/main/kotlin/moe/rukamori/archivetune")
FILE = ROOT / "playback" / "MusicService.kt"

if not FILE.exists():
    print("MusicService.kt not found.")
    sys.exit(1)

backup = FILE.with_suffix(FILE.suffix + ".bak")
shutil.copy2(FILE, backup)

text = FILE.read_text(encoding="utf-8")

changes = 0


def replace_exact(old: str, new: str, name=""):
    global text, changes

    if old not in text:
        print(f"\nFAILED: {name}\n")
        sys.exit(1)

    text = text.replace(old, new, 1)
    changes += 1
    print(f"OK: {name}")

# -------------------------------------------------------
# Inject WebRtcTransport
# -------------------------------------------------------

replace_exact(
"""lateinit var database: MusicDatabase""",
"""lateinit var database: MusicDatabase

    @Inject
    lateinit var webRtcTransport: WebRtcTransport""",
)


# -------------------------------------------------------
# Import
# -------------------------------------------------------

if "WebRtcTransport" not in text:
    marker = "import javax."
    idx = text.find(marker)

    if idx == -1:
        print("Couldn't locate import section.")
        sys.exit(1)

    text = (
        text[:idx]
        + "import moe.rukamori.archivetune.together.webrtc.WebRtcTransport\n"
        + text[idx:]
    )

    changes += 1


# -------------------------------------------------------
# startTogetherOnlineHost()
# -------------------------------------------------------

replace_exact(
"""fun startTogetherOnlineHost(
        displayName: String,
        settings: moe.rukamori.archivetune.together.TogetherRoomSettings,
    )""",
"""fun startTogetherOnlineHost(
        displayName: String,
        settings: moe.rukamori.archivetune.together.TogetherRoomSettings,
        useWebRtc: Boolean = false,
    )""",
)


# -------------------------------------------------------
# joinTogether()
# -------------------------------------------------------

replace_exact(
"""fun joinTogether(
        rawLink: String,
        displayName: String,
    )""",
"""fun joinTogether(
        rawLink: String,
        displayName: String,
        useWebRtc: Boolean = false,
    )""",
)


# -------------------------------------------------------
# joinTogetherOnline()
# -------------------------------------------------------

replace_exact(
"""fun joinTogetherOnline(
        code: String,
        displayName: String,
    )""",
"""fun joinTogetherOnline(
        code: String,
        displayName: String,
        useWebRtc: Boolean = false,
    )""",
)
# -------------------------------------------------------
# TogetherOnlineHost constructor
# -------------------------------------------------------

replace_exact(
"""            val onlineHost =
                moe.rukamori.archivetune.together.TogetherOnlineHost(
                    externalScope = ioScope,
                    sessionId = created.sessionId,
                    sessionKey = created.hostKey,
                    hostId = togetherHostId,
                    hostDisplayName = hostName,
                    initialSettings = created.settings,
                    clientId = getOrCreateTogetherClientId(),
                    bearerToken = togetherToken,
                )""",
"""            val onlineHost =
                moe.rukamori.archivetune.together.TogetherOnlineHost(
                    externalScope = ioScope,
                    sessionId = created.sessionId,
                    sessionKey = created.hostKey,
                    hostId = togetherHostId,
                    hostDisplayName = hostName,
                    initialSettings = created.settings,
                    clientId = getOrCreateTogetherClientId(),
                    bearerToken = togetherToken,
                    webRtcTransport = webRtcTransport,
                    useWebRtc = useWebRtc,
                )""",
)

# -------------------------------------------------------
# LAN TogetherClient
# -------------------------------------------------------

replace_exact(
"""            val client =
                moe.rukamori.archivetune.together.TogetherClient(
                    ioScope,
                    clientId = getOrCreateTogetherClientId(),
                )""",
"""            val client =
                moe.rukamori.archivetune.together.TogetherClient(
                    ioScope,
                    clientId = getOrCreateTogetherClientId(),
                    webRtcTransport = webRtcTransport,
                    useWebRtc = useWebRtc,
                )""",
)

# -------------------------------------------------------
# Online TogetherClient
# -------------------------------------------------------

replace_exact(
"""            val client =
                moe.rukamori.archivetune.together.TogetherClient(
                    ioScope,
                    clientId = getOrCreateTogetherClientId(),
                    bearerToken = togetherToken,
                )""",
"""            val client =
                moe.rukamori.archivetune.together.TogetherClient(
                    ioScope,
                    clientId = getOrCreateTogetherClientId(),
                    bearerToken = togetherToken,
                    webRtcTransport = webRtcTransport,
                    useWebRtc = useWebRtc,
                )""",
)
# -------------------------------------------------------
# Final sanity check
# -------------------------------------------------------

expected = 8

if changes != expected:
    print()
    print("=" * 60)
    print(f"ERROR: expected {expected} replacements but made {changes}.")
    print("MusicService.kt was NOT written.")
    print(f"Backup is at: {backup}")
    print("=" * 60)
    sys.exit(1)
    print(f"Changes made: {changes}")

# -------------------------------------------------------
# Write file
# -------------------------------------------------------
print("\n===== DIFF CHECK =====")
FILE.write_text(text, encoding="utf-8")

print()
print("=" * 60)
print("MusicService.kt patched successfully.")
print(f"Replacements made : {changes}")
print(f"Backup            : {backup}")
print("=" * 60)
