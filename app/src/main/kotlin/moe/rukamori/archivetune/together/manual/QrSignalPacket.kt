package moe.rukamori.archivetune.together.manual

import kotlinx.serialization.Serializable
import moe.rukamori.archivetune.together.ManualQrProtocol

@Serializable
data class QrSignalPacket(
    val version: Int = ManualQrProtocol.VERSION,
    val sessionId: String,
    val kind: QrSignalKind,
    val part: Int,
    val total: Int = ManualQrProtocol.QR_COUNT,
    val payload: String,
)
