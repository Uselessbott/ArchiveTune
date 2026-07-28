#!/usr/bin/env python3
from pathlib import Path
import re
import sys

path = Path(
    "app/src/main/kotlin/moe/rukamori/archivetune/together/MusicTogetherRepository.kt"
)

text = path.read_text(encoding="utf-8")

old = re.compile(
    r"""MusicTogetherConnectionMode\.ONLINE\s*->\s*\{\s*
\s*service\.startTogetherOnlineHost\(
\s*displayName\s*=\s*displayName,
\s*settings\s*=\s*settings,
\s*\)
\s*\}""",
    re.MULTILINE | re.VERBOSE,
)

new = """MusicTogetherConnectionMode.ONLINE -> {
                    service.startTogetherPersonalHost(
                        port = port,
                        displayName = displayName,
                        settings = settings,
                    )
                }"""

text2, count = old.subn(new, text, count=1)

if count != 1:
    print("ERROR: Could not locate ONLINE host block.")
    sys.exit(1)

path.write_text(text2, encoding="utf-8")

print("✓ ONLINE mode now starts Personal Tunnel hosting.")
