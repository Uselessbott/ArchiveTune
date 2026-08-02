package moe.rukamori.archivetune.together.manual

fun QrExchangeState.toProgress(): QrExchangeProgress =
    when (this) {

        QrExchangeState.Idle ->
            QrExchangeProgress(
                "Ready",
                "Waiting to begin pairing.",
                0f,
            )

        QrExchangeState.CreatingOffer ->
            QrExchangeProgress(
                "Creating Offer",
                "Generating SDP...",
                0.1f,
            )

        QrExchangeState.WaitingForOffer ->
            QrExchangeProgress(
                "Waiting for Offer",
                "Import host screenshot.",
                0.25f,
            )

        QrExchangeState.CreatingAnswer ->
            QrExchangeProgress(
                "Creating Answer",
                "Generating response...",
                0.5f,
            )

        QrExchangeState.WaitingForRemoteAnswer ->
            QrExchangeProgress(
                "Waiting for Answer",
                "Waiting for remote screenshot.",
                0.75f,
            )

        QrExchangeState.Connecting ->
            QrExchangeProgress(
                "Connecting",
                "Exchanging ICE candidates...",
                0.9f,
            )

        QrExchangeState.Connected ->
            QrExchangeProgress(
                "Connected",
                "Pairing complete.",
                1f,
            )

        is QrExchangeState.Failed ->
            QrExchangeProgress(
                "Failed",
                message,
                0f,
            )
    }
