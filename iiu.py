#!/usr/bin/env python3

import re
import shutil
import sys
from pathlib import Path

FILE = Path("app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt")
BACKUP = FILE.with_suffix(FILE.suffix + ".client.bak")

if not FILE.exists():
    sys.exit(f"File not found: {FILE}")

shutil.copy2(FILE, BACKUP)
print(f"Backup created: {BACKUP}")

content = FILE.read_text(encoding="utf-8")

def patch_constructor(content, has_bearer):
    pattern = re.compile(
        r'(moe\.rukamori\.archivetune\.together\.TogetherClient\(\s*\n'
        r'(?P<body>.*?)'
        r'(?P<indent>[ \t]*)\))',
        re.DOTALL,
    )

    matches = list(pattern.finditer(content))
    target = None

    for m in matches:
        body = m.group("body")
        bearer = "bearerToken = togetherToken" in body

        if bearer == has_bearer:
            target = m
            break

    if target is None:
        kind = "Online" if has_bearer else "LAN"
        sys.exit(f"Could not locate {kind} TogetherClient constructor.")

    body = target.group("body")

    if "webRtcTransport = webRtcTransport" in body:
        kind = "Online" if has_bearer else "LAN"
        print(f"{kind} constructor already patched.")
        return content

    indent = target.group("indent")

    insertion = (
        f"{indent}    webRtcTransport = webRtcTransport,\n"
        f"{indent}    useWebRtc = useWebRtc,\n"
    )

    new_body = body + insertion

    new_match = (
        "moe.rukamori.archivetune.together.TogetherClient(\n"
        + new_body
        + indent
        + ")"
    )

    return (
        content[:target.start()]
        + new_match
        + content[target.end():]
    )

content = patch_constructor(content, has_bearer=False)
content = patch_constructor(content, has_bearer=True)

FILE.write_text(content, encoding="utf-8")
print("Successfully patched both TogetherClient constructors.")
