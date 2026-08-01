package moe.rukamori.archivetune.together.manual

import kotlinx.serialization.Serializable

@Serializable
data class ManualOffer(
    val version:Int=1,
    val sessionId:String,
    val type:String,
    val sdp:String,
)
