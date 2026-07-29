#!/usr/bin/env python3
"""
Apply remaining edits to MusicService.kt conditionally and idempotently.

This script:
- Creates a backup of the original file.
- Inserts the WebRtcTransport import if missing (before javax.inject.Inject).
- Checks for the existence of `lateinit var webRtcTransport: WebRtcTransport`
  (ignoring leading indentation) and aborts if not found (since it's required).
- For each of the six required edits (function signatures and constructor calls):
    - If the patched (final) version is already present, reports "already patched".
    - Else if the original (unedited) version is present, applies the edit.
    - Else aborts because neither the original nor patched block can be located.
- Aborts if any required block cannot be found or is ambiguous.

All replacements use exact multiline strings. No regex is used.

Usage: python3 apply_edits.py
"""

import shutil
import sys
from pathlib import Path

FILE_PATH = Path("app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt")
BACKUP_PATH = FILE_PATH.with_suffix(FILE_PATH.suffix + ".bak")

# ----------------------------------------------------------------------
# Edit definitions.
# Each edit is a tuple of (description, find_string, replace_string).
# find_string is the original (unedited) block; replace_string is the final version.
# For function signatures, the opening brace is on the same line as the closing parenthesis.
# For the TogetherOnlineHost constructor, we match from the "hostId" line
# to the closing parenthesis to avoid indentation fragility.
# ----------------------------------------------------------------------

EDITS = [
    # 1) startTogetherOnlineHost signature – add useWebRtc parameter
    (
        "startTogetherOnlineHost signature",
        "fun startTogetherOnlineHost(\n"
        "    displayName: String,\n"
        "    settings: moe.rukamori.archivetune.together.TogetherRoomSettings,\n"
        ") {",
        "fun startTogetherOnlineHost(\n"
        "    displayName: String,\n"
        "    settings: moe.rukamori.archivetune.together.TogetherRoomSettings,\n"
        "    useWebRtc: Boolean = false,\n"
        ") {",
    ),
    # 2) TogetherOnlineHost constructor – add webRtcTransport and useWebRtc
    # Match from hostId line to the closing parenthesis, inclusive.
    (
        "TogetherOnlineHost constructor",
        "hostId = togetherHostId,\n"
        "                    hostDisplayName = hostName,\n"
        "                    initialSettings = created.settings,\n"
        "                    clientId = getOrCreateTogetherClientId(),\n"
        "                    bearerToken = togetherToken,\n"
        "                )",
        "hostId = togetherHostId,\n"
        "                    hostDisplayName = hostName,\n"
        "                    initialSettings = created.settings,\n"
        "                    clientId = getOrCreateTogetherClientId(),\n"
        "                    bearerToken = togetherToken,\n"
        "                    webRtcTransport = webRtcTransport,\n"
        "                    useWebRtc = useWebRtc,\n"
        "                )",
    ),
    # 3) joinTogether signature – add useWebRtc parameter
    (
        "joinTogether signature",
        "fun joinTogether(\n"
        "    rawLink: String,\n"
        "    displayName: String,\n"
        ") {",
        "fun joinTogether(\n"
        "    rawLink: String,\n"
        "    displayName: String,\n"
        "    useWebRtc: Boolean = false,\n"
        ") {",
    ),
    # 4) LAN TogetherClient constructor – add webRtcTransport and useWebRtc
    (
        "LAN TogetherClient constructor",
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
    # 5) joinTogetherOnline signature – add useWebRtc parameter
    (
        "joinTogetherOnline signature",
        "fun joinTogetherOnline(\n"
        "    code: String,\n"
        "    displayName: String,\n"
        ") {",
        "fun joinTogetherOnline(\n"
        "    code: String,\n"
        "    displayName: String,\n"
        "    useWebRtc: Boolean = false,\n"
        ") {",
    ),
    # 6) Online TogetherClient constructor (with bearerToken) – add webRtcTransport and useWebRtc
    (
        "Online TogetherClient constructor",
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

    # Backup original
    shutil.copy2(FILE_PATH, BACKUP_PATH)
    print(f"Backup created: {BACKUP_PATH}")

    with open(FILE_PATH, "r", encoding="utf-8") as f:
        content = f.read()

    changes = []

    # --------------------------------------------------------------
    # 1) Handle import for WebRtcTransport
    # --------------------------------------------------------------
    import_line = "import moe.rukamori.archivetune.together.webrtc.WebRtcTransport"
    if import_line in content:
        print("Skipped existing import")
    else:
        target = "import javax.inject.Inject"
        if target not in content:
            print("Error: Could not find 'import javax.inject.Inject' to insert import before.", file=sys.stderr)
            sys.exit(1)
        content = content.replace(target, import_line + "\n" + target, 1)
        print("Added import")
        changes.append("import")

    # --------------------------------------------------------------
    # 2) Handle field injection – check presence of the field declaration
    #    (ignoring indentation) and abort if missing.
    # --------------------------------------------------------------
    field_decl = "lateinit var webRtcTransport: WebRtcTransport"
    if field_decl not in content:
        print("Error: Required field injection for webRtcTransport not found.", file=sys.stderr)
        print("Please add '@Inject' and 'lateinit var webRtcTransport: WebRtcTransport' before running.", file=sys.stderr)
        sys.exit(1)
    else:
        print("Skipped existing injected field (field declaration present)")

    # --------------------------------------------------------------
    # 3) Apply each edit safely
    # --------------------------------------------------------------
    for desc, find_str, replace_str in EDITS:
        if replace_str in content:
            print(f"Already patched: {desc}")
            continue

        if find_str in content:
            # Apply the replacement (should be exactly once)
            count = content.count(find_str)
            if count != 1:
                print(f"Error: Found {count} occurrences of original block for '{desc}'. Cannot proceed.", file=sys.stderr)
                sys.exit(1)
            content = content.replace(find_str, replace_str)
            print(f"Patched: {desc}")
            changes.append(desc)
        else:
            # Neither original nor patched version found – something is wrong
            print(f"Error: Could not locate either original or patched block for '{desc}'.", file=sys.stderr)
            print("Aborting to avoid silent mis-application.", file=sys.stderr)
            sys.exit(1)

    # Write the modified content only if something changed
    if changes:
        with open(FILE_PATH, "w", encoding="utf-8") as f:
            f.write(content)
        print("File updated successfully.")
    else:
        print("No changes were necessary; file already up to date.")

    # Summary
    print("\nSummary of changes applied:")
    if not changes:
        print("  - None")
    else:
        for item in changes:
            print(f"  - {item}")

if __name__ == "__main__":
    main()
