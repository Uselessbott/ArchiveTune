package moe.rukamori.archivetune.together

sealed interface ManualQrState {
    data object Idle : ManualQrState
    data object HostReadyToGenerate : ManualQrState
    data object ShowingOfferQr : ManualQrState
    data object GuestReadyToImport : ManualQrState
    data object ShowingAnswerQr : ManualQrState
    data object ExchangingIce : ManualQrState
}
