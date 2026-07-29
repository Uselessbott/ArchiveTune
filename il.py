#!/usr/bin/env python3
"""
Apply specific edits to MusicService.kt.

Usage: python3 apply_edits.py

The script will:
- Create a backup (MusicService.kt.bak)
- Perform exact string replacements as defined below
- Abort if any replacement pattern is not found exactly once
- Write the modified file only if all replacements succeed

No external dependencies are required.
"""

import shutil
import sys
from pathlib import Path

# The actual location of MusicService.kt relative to the script root.
FILE_PATH = Path("app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt")
BACKUP_PATH = FILE_PATH.with_suffix(FILE_PATH.suffix + ".bak")


# ----------------------------------------------------------------------
# Replacement definitions.
# Each entry is a tuple (find_string, replace_string).
# The strings must match the exact text (including indentation and newlines)
# that appears in the source file.  The order of replacements is the order
# they are applied.
# ----------------------------------------------------------------------

REPLACEMENTS = [
    # 1) Add import for WebRtcTransport before the javax.inject.Inject import.
    (
        "import javax.inject.Inject",
        "import moe.rukamori.archivetune.together.webrtc.WebRtcTransport\n"
        "import javax.inject.Inject",
    ),

    # 2) Inject webRtcTransport field.
    (
        "@Inject\n"
        "lateinit var database: MusicDatabase\n"
        "\n"
        "@Inject\n"
        "lateinit var lyricsHelper: LyricsHelper",

        "@Inject\n"
        "lateinit var database: MusicDatabase\n"
        "\n"
        "@Inject\n"
        "lateinit var webRtcTransport: WebRtcTransport\n"
        "\n"
        "@Inject\n"
        "lateinit var lyricsHelper: LyricsHelper",
    ),

    # 3) startTogetherOnlineHost signature (fully qualified settings type).
    (
        "fun startTogetherOnlineHost(\n"
        "    displayName: String,\n"
        "    settings: moe.rukamori.archivetune.together.TogetherRoomSettings,\n"
        ")",

        "fun startTogetherOnlineHost(\n"
        "    displayName: String,\n"
        "    settings: moe.rukamori.archivetune.together.TogetherRoomSettings,\n"
        "    useWebRtc: Boolean = false,\n"
        ")",
    ),

    # 4) TogetherOnlineHost constructor block – insert webRtcTransport and useWebRtc
    # after bearerToken.  We match the whole block to avoid ambiguity.
    (
        "            val onlineHost =\n"
        "                moe.rukamori.archivetune.together.TogetherOnlineHost(\n"
        "                    externalScope = ioScope,\n"
        "                    sessionId = created.sessionId,\n"
        "                    sessionKey = created.hostKey,\n"
        "                    hostId = togetherHostId,\n"
        "                    hostDisplayName = hostName,\n"
        "                    initialSettings = created.settings,\n"
        "                    clientId = getOrCreateTogetherClientId(),\n"
        "                    bearerToken = togetherToken,\n"
        "                )",

        "            val onlineHost =\n"
        "                moe.rukamori.archivetune.together.TogetherOnlineHost(\n"
        "                    externalScope = ioScope,\n"
        "                    sessionId = created.sessionId,\n"
        "                    sessionKey = created.hostKey,\n"
        "                    hostId = togetherHostId,\n"
        "                    hostDisplayName = hostName,\n"
        "                    initialSettings = created.settings,\n"
        "                    clientId = getOrCreateTogetherClientId(),\n"
        "                    bearerToken = togetherToken,\n"
        "                    webRtcTransport = webRtcTransport,\n"
        "                    useWebRtc = useWebRtc,\n"
        "                )",
    ),

    # 5) joinTogether signature.
    (
        "fun joinTogether(\n"
        "    rawLink: String,\n"
        "    displayName: String,\n"
        ")",

        "fun joinTogether(\n"
        "    rawLink: String,\n"
        "    displayName: String,\n"
        "    useWebRtc: Boolean = false,\n"
        ")",
    ),

    # 6) LAN TogetherClient constructor.
    (
        "TogetherClient(\n"
        "    ioScope,\n"
        "    clientId = getOrCreateTogetherClientId(),\n"
        ")",

        "TogetherClient(\n"
        "    ioScope,\n"
        "    clientId = getOrCreateTogetherClientId(),\n"
        "    webRtcTransport = webRtcTransport,\n"
        "    useWebRtc = useWebRtc,\n"
        ")",
    ),

    # 7) joinTogetherOnline signature.
    (
        "fun joinTogetherOnline(\n"
        "    code: String,\n"
        "    displayName: String,\n"
        ")",

        "fun joinTogetherOnline(\n"
        "    code: String,\n"
        "    displayName: String,\n"
        "    useWebRtc: Boolean = false,\n"
        ")",
    ),

    # 8) Online TogetherClient constructor (with bearerToken).
    (
        "TogetherClient(\n"
        "    ioScope,\n"
        "    clientId = getOrCreateTogetherClientId(),\n"
        "    bearerToken = togetherToken,\n"
        ")",

        "TogetherClient(\n"
        "    ioScope,\n"
        "    clientId = getOrCreateTogetherClientId(),\n"
        "    bearerToken = togetherToken,\n"
        "    webRtcTransport = webRtcTransport,\n"
        "    useWebRtc = useWebRtc,\n"
        ")",
    ),
]


def main():
    if not FILE_PATH.exists():
        print(f"Error: {FILE_PATH} not found.", file=sys.stderr)
        sys.exit(1)

    # Backup the original file.
    shutil.copy2(FILE_PATH, BACKUP_PATH)
    print(f"Backup created: {BACKUP_PATH}")

    # Read the file.
    with open(FILE_PATH, "r", encoding="utf-8") as f:
        content = f.read()

    # Apply each replacement.
    for idx, (find_str, replace_str) in enumerate(REPLACEMENTS, start=1):
        count = content.count(find_str)
        if count != 1:
            print(
                f"Replacement #{idx} failed: find string occurs {count} times (expected 1).",
                file=sys.stderr,
            )
            # Show a snippet for debugging.
            if count > 0:
                print(f"First occurrence: {repr(find_str[:80])}...", file=sys.stderr)
            else:
                print("Search string not found.", file=sys.stderr)
            print("Aborting. No changes were written.", file=sys.stderr)
            sys.exit(1)

        content = content.replace(find_str, replace_str)

    # Write the modified content back.
    with open(FILE_PATH, "w", encoding="utf-8") as f:
        f.write(content)

    print("All replacements succeeded. File updated.")


if __name__ == "__main__":
    main()
