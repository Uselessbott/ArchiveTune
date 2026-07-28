#!/usr/bin/env python3

from pathlib import Path

ROOT = Path("app/src/main/kotlin/moe/rukamori/archivetune/together/tunnel")

FILES = [
    ROOT / "TunnelProvider.kt",
    ROOT / "NgrokTunnelProvider.kt",
    ROOT / "NoOpTunnelProvider.kt",
]

REPLACEMENTS = [
    ("interface TunnelProvider", "interface CustomTunnelProvider"),
    ("sealed interface TunnelResult", "sealed interface CustomTunnelResult"),
    (": TunnelProvider", ": CustomTunnelProvider"),
    ("TunnelResult.", "CustomTunnelResult."),
    ("discoverTunnelUrl(): TunnelResult", "discoverTunnelUrl(): CustomTunnelResult"),
]

for path in FILES:
    text = path.read_text(encoding="utf-8")
    original = text

    for old, new in REPLACEMENTS:
        text = text.replace(old, new)

    if text != original:
        path.write_text(text, encoding="utf-8")
        print(f"Patched {path}")
    else:
        print(f"No changes: {path}")

print("\nDone.")
print("Now rename the files:")
print("  TunnelProvider.kt     -> CustomTunnelProvider.kt")
print("After renaming, rebuild.")
