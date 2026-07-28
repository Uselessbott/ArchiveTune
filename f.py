from pathlib import Path

path = Path("app/src/main/kotlin/moe/rukamori/archivetune/together/ngrok/NgrokProbe.kt")

path.write_text(
"""package moe.rukamori.archivetune.together.ngrok

object NgrokProbe {

    fun run() {
        throw RuntimeException("NGROK PROBE EXECUTED")
    }
}
""",
encoding="utf-8",
)

print("✓ Rewrote NgrokProbe.kt")
