from pathlib import Path

path = Path("app/src/main/kotlin/moe/rukamori/archivetune/together/ngrok/NgrokProbe.kt")

path.write_text(
"""package moe.rukamori.archivetune.together.ngrok

import timber.log.Timber

object NgrokProbe {

    fun run() {
        Timber.i("NGROK PROBE: Application startup verified.")
    }
}
""",
encoding="utf-8",
)

print("✓ Restored NgrokProbe.kt")
