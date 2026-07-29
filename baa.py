#!/usr/bin/env python3

from pathlib import Path
import shutil
import re
import sys

FILE = Path("app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt")
BACKUP = FILE.with_suffix(FILE.suffix + ".sig.bak")

shutil.copy2(FILE, BACKUP)
print(f"Backup created: {BACKUP}")

text = FILE.read_text(encoding="utf-8")

functions = [
    "startTogetherOnlineHost",
    "joinTogether",
    "joinTogetherOnline",
]

for fn in functions:
    pattern = re.compile(
        rf"(fun\s+{fn}\s*\()(.*?)(\)\s*\{{)",
        re.DOTALL,
    )

    m = pattern.search(text)
    if not m:
        sys.exit(f"Couldn't find {fn}")

    params = m.group(2)

    if "useWebRtc" in params:
        print(f"{fn}: already patched")
        continue

    lines = params.splitlines()

    indent = "        "
    for line in reversed(lines):
        if line.strip():
            indent = re.match(r"^\s*", line).group(0)
            break

    params = params.rstrip()

    if not params.endswith(","):
        params += ","

    params += f"\n{indent}useWebRtc: Boolean = false,\n"

    replacement = m.group(1) + params + m.group(3)

    text = text[:m.start()] + replacement + text[m.end():]

    print(f"{fn}: patched")

FILE.write_text(text, encoding="utf-8")
print("Done.")
