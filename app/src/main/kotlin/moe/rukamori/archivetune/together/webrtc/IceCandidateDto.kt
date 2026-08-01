package moe.rukamori.archivetune.together.webrtc

import kotlinx.serialization.Serializable

@Serializable
data class IceCandidateDto(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int
)
