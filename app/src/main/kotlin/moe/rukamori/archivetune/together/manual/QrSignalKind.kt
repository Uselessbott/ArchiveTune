package moe.rukamori.archivetune.together.manual

import kotlinx.serialization.Serializable

@Serializable
enum class QrSignalKind {
    OFFER,
    ANSWER,
    ICE,
}
