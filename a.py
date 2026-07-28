from pathlib import Path

root = Path("app/src/main/kotlin/moe/rukamori/archivetune/together/ngrok")
root.mkdir(parents=True, exist_ok=True)

(root / "NgrokManager.kt").write_text(
"""package moe.rukamori.archivetune.together.ngrok

import timber.log.Timber

class NgrokManager {

    fun initialize() {
        Timber.i("NgrokManager initialized")
    }
}
""",
encoding="utf-8"
)

(root / "NgrokState.kt").write_text(
"""package moe.rukamori.archivetune.together.ngrok

sealed interface NgrokState {
    data object Idle : NgrokState
    data object Starting : NgrokState
    data class Running(val publicUrl: String) : NgrokState
    data class Error(val message: String) : NgrokState
}
""",
encoding="utf-8"
)

print("✓ Created NgrokManager and NgrokState.")

