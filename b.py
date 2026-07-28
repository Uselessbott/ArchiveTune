from pathlib import Path

ROOT = Path("app/src/main/kotlin/moe/rukamori/archivetune/together/tunnel")

files = [
    ROOT / "NgrokTunnelProvider.kt",
    ROOT / "NoOpTunnelProvider.kt",
]

replacements = {
    "CustomTunnelProvider": "TunnelProvider",
    "CustomTunnelResult": "TunnelResult",
}

for file in files:
    text = file.read_text(encoding="utf-8")
    for old, new in replacements.items():
        text = text.replace(old, new)
    file.write_text(text, encoding="utf-8")

print("✓ Restored upstream tunnel type names.")
