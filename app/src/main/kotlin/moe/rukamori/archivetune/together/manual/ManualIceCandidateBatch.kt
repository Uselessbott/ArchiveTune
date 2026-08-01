package moe.rukamori.archivetune.together.manual

import kotlinx.serialization.Serializable
import moe.rukamori.archivetune.together.ManualQrProtocol

@Serializable
data class ManualIceCandidateBatch(
    val version: Int = ManualQrProtocol.VERSION,
    val sessionId: String,
    val candidates: List<ManualIceCandidate>,
)
