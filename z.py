#!/usr/bin/env python3
from pathlib import Path
import sys

path = Path("app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt")
text = path.read_text(encoding="utf-8")

old = "private sealed interface TogetherConnectionState"
new = "internal sealed interface TogetherConnectionState"

if new in text:
    print("Already patched.")
    sys.exit(0)

if old not in text:
    print("Declaration not found.")
    sys.exit(1)

text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")

print("✓ TogetherConnectionState is now internal.")
