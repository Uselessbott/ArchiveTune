package moe.rukamori.archivetune.together.webrtc

data class IceCandidateDto(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int
)
