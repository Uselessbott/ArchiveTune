package moe.rukamori.archivetune.together.manual

import kotlinx.serialization.Serializable
import moe.rukamori.archivetune.together.webrtc.IceCandidateDto

@Serializable
data class ManualIceCandidate(
    val version:Int=1,
    val sessionId:String,
    val candidate:IceCandidateDto,
)
