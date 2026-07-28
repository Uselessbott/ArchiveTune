#!/usr/bin/env python3

from pathlib import Path

FILE = Path(
    "app/src/main/kotlin/moe/rukamori/archivetune/together/tunnel/CustomTunnelProvider.kt"
)

text = FILE.read_text(encoding="utf-8")

old = """sealed interface CustomTunnelResult {
    data class Success(val publicUrl: HttpUrl) : TunnelResult
    data class Error(val message: String) : TunnelResult
}
"""

new = """sealed interface CustomTunnelResult {
    data class Success(val publicUrl: HttpUrl) : CustomTunnelResult
    data class Error(val message: String) : CustomTunnelResult
}
"""

if old not in text:
    raise SystemExit("Pattern not found. File may already be fixed or changed.")

text = text.replace(old, new)

FILE.write_text(text, encoding="utf-8")

print("✓ Fixed CustomTunnelResult inheritance.")
