package moe.rukamori.archivetune.together.manual

import kotlinx.serialization.Serializable

@Serializable
data class ManualQrPacket(
    val sessionId: String,
    val sequence: Int,
    val total: Int,
    val payload: String,
)
