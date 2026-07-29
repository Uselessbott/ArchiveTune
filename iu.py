#!/usr/bin/env python3
"""
Apply remaining edits to MusicService.kt conditionally and idempotently.

This script:
- Creates a backup of the original file.
- Inserts the WebRtcTransport import if missing (before javax.inject.Inject).
- Checks for the existence of `lateinit var webRtcTransport: WebRtcTransport`
  (ignoring leading indentation) and aborts if not found.
- For the three function signatures (startTogetherOnlineHost, joinTogether, joinTogetherOnline):
    uses regex with named capture groups to insert the `useWebRtc: Boolean = false,`
    parameter if missing, preserving original formatting and indentation.
- For the three constructor calls (TogetherOnlineHost, LAN TogetherClient, Online TogetherClient):
    uses exact multiline replacements to add webRtcTransport and useWebRtc.
- Aborts if any required block cannot be found or is ambiguous.

All constructor replacements use exact multiline strings (brittle but targeted).
Function signature edits use regex with DOTALL and named capture groups,
and rebuild the declaration from the captured groups.

Usage: python3 apply_edits.py
"""

import re
import shutil
import sys
from pathlib import Path

FILE_PATH = Path("app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt")
BACKUP_PATH = FILE_PATH.with_suffix(FILE_PATH.suffix + ".bak")

# ----------------------------------------------------------------------
# Constructor edits (exact multiline replacements)
# These are brittle and depend on exact whitespace. They target the
# specific formatting in the current ArchiveTune repository.
# ----------------------------------------------------------------------
CONSTRUCTOR_EDITS = [
    # 1) TogetherOnlineHost constructor – add webRtcTransport and useWebRtc
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
    # 2) LAN TogetherClient constructor – add webRtcTransport and useWebRtc
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
    # 3) Online TogetherClient constructor (with bearerToken) – add webRtcTransport and useWebRtc
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

# ----------------------------------------------------------------------
# Function signature regex patches
# Each is (description, func_name, insert_line_template)
# The insert_line_template will have {indent} replaced with the inferred indentation.
# ----------------------------------------------------------------------
SIGNATURE_PATCHES = [
    (
        "startTogetherOnlineHost signature",
        "startTogetherOnlineHost",
        "{indent}useWebRtc: Boolean = false,",
    ),
    (
        "joinTogether signature",
        "joinTogether",
        "{indent}useWebRtc: Boolean = false,",
    ),
    (
        "joinTogetherOnline signature",
        "joinTogetherOnline",
        "{indent}useWebRtc: Boolean = false,",
    ),
]

def apply_regex_signature(content, desc, func_name, insert_line_template):
    """
    Apply regex patch for a function signature.
    Returns (new_content, applied) where applied is True if patched, False if already present.
    Aborts if the function declaration cannot be found.
    
    This function preserves original formatting by finding the position of
    the closing ")" within the matched text and inserting before it,
    rather than stripping or modifying the parameter list content.
    """
    # Pattern: fun <func_name>( <params> ) {
    pattern = re.compile(
        rf'(fun\s+{re.escape(func_name)}\s*\()(?P<params>.*?)(?P<closing>\)\s*\{{)',
        re.DOTALL
    )
    match = pattern.search(content)
    if not match:
        print(f"Error: Could not locate function declaration for '{desc}'.", file=sys.stderr)
        sys.exit(1)

    # Extract the parameter list
    params = match.group('params')
    
    # Check if the insert line already exists in the parameter list
    if re.search(r"\buseWebRtc\s*:", params):
        return content, False

    # Find the last parameter line to infer indentation
    lines = params.splitlines()
    if not lines:
        # No parameters, use default indent
        indent = "    "
    else:
        # Get the last non-empty line
        last_line = next((line for line in reversed(lines) if line.strip()), "")
        if last_line:
            # Extract indentation from the last line
            match_indent = re.match(r"^\s*", last_line)
            indent = match_indent.group(0) if match_indent else "    "
        else:
            indent = "    "

    # Build the insert line with the inferred indentation
    insert_line = insert_line_template.format(indent=indent)

    # We need to insert before the closing ")" in the full match.
    # We'll use the full match text and find the last ")" that corresponds
    # to the closing of the parameter list.
    full_match = match.group(0)
    closing = match.group('closing')
    
    # Find where the closing pattern starts within the full match
    # The closing pattern is the captured group, which appears at the end.
    # We can find its position using rfind on the full_match.
    closing_pos = full_match.rfind(closing)
    if closing_pos == -1:
        print(f"Error: Could not locate closing ')' in function declaration for '{desc}'.", file=sys.stderr)
        sys.exit(1)
    
    before_closing = full_match[:closing_pos]
    after_closing = full_match[closing_pos:]
    
    # Determine if we need to add a comma before the new parameter.
    # We check if the last non-whitespace character before the closing is a comma.
    trimmed = before_closing.rstrip()
    if trimmed.endswith(','):
        # Already has a comma, just insert the new line
        new_match = before_closing + '\n' + insert_line + after_closing
    else:
        # Need to add a comma before the new line
        # But we must be careful: if the previous parameter is on the same line
        # as the closing ")", this could break formatting. However, for Kotlin
        # multi-line function signatures with trailing commas, this is safe.
        new_match = before_closing + ',\n' + insert_line + after_closing
    
    # Replace using the match's span to be precise
    content = content[:match.start()] + new_match + content[match.end():]
    return content, True

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
    # --------------------------------------------------------------
    field_decl = "lateinit var webRtcTransport: WebRtcTransport"
    if field_decl not in content:
        print("Error: Required field injection for webRtcTransport not found.", file=sys.stderr)
        print("Please add '@Inject' and 'lateinit var webRtcTransport: WebRtcTransport' before running.", file=sys.stderr)
        sys.exit(1)
    else:
        print("Skipped existing injected field (field declaration present)")

    # --------------------------------------------------------------
    # 3) Apply function signature regex patches
    # --------------------------------------------------------------
    for desc, func_name, insert_line_template in SIGNATURE_PATCHES:
        new_content, applied = apply_regex_signature(content, desc, func_name, insert_line_template)
        if applied:
            content = new_content
            print(f"Patched: {desc}")
            changes.append(desc)
        else:
            print(f"Already patched: {desc}")

    # --------------------------------------------------------------
    # 4) Apply constructor exact edits
    # --------------------------------------------------------------
    for desc, find_str, replace_str in CONSTRUCTOR_EDITS:
        if replace_str in content:
            print(f"Already patched: {desc}")
            continue

        if find_str in content:
            count = content.count(find_str)
            if count != 1:
                print(f"Error: Found {count} occurrences of original block for '{desc}'. Cannot proceed.", file=sys.stderr)
                sys.exit(1)
            content = content.replace(find_str, replace_str)
            print(f"Patched: {desc}")
            changes.append(desc)
        else:
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
