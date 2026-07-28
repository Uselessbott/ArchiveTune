package moe.rukamori.archivetune.together.ngrok

sealed interface NgrokState {
    data object Idle : NgrokState
    data object Starting : NgrokState
    data class Running(val publicUrl: String) : NgrokState
    data class Error(val message: String) : NgrokState
}
