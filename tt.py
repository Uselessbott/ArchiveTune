from pathlib import Path

path = Path("app/src/main/kotlin/moe/rukamori/archivetune/together/tunnel/NgrokTunnelProvider.kt")
text = path.read_text(encoding="utf-8")

old = """            val publicUrl = tunnel.public_url.toHttpUrlOrNull()
                ?: return TunnelResult.Error("Invalid tunnel URL: $publicUrl")"""

new = """            val publicUrl = tunnel.public_url.toHttpUrlOrNull()
                ?: return TunnelResult.Error(
                    "Invalid tunnel URL: ${tunnel.public_url}"
                )"""

if old not in text:
    print("Pattern not found.")
else:
    path.write_text(text.replace(old, new), encoding="utf-8")
    print("✅ NgrokTunnelProvider.kt patched successfully.")
