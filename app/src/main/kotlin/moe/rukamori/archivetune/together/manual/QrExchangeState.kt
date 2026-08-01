package moe.rukamori.archivetune.together.manual

sealed interface QrExchangeState {

    data object Idle : QrExchangeState

    data object CreatingOffer : QrExchangeState

    data object WaitingForOffer : QrExchangeState

    data object WaitingForRemoteAnswer : QrExchangeState

    data object CreatingAnswer : QrExchangeState

    data object Connecting : QrExchangeState

    data object Connected : QrExchangeState

    data class Failed(
        val message: String,
    ) : QrExchangeState
}
