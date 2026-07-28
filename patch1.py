from pathlib import Path

BASE = Path("app/src/main/kotlin/moe/rukamori/archivetune/together/webrtc")
BASE.mkdir(parents=True, exist_ok=True)

files = {
    "WebRtcTransport.kt": r'''package moe.rukamori.archivetune.together.webrtc

class WebRtcTransport
''',

    "WebRtcPeer.kt": r'''package moe.rukamori.archivetune.together.webrtc

class WebRtcPeer
''',

    "WebRtcSignallingApi.kt": r'''package moe.rukamori.archivetune.together.webrtc

class WebRtcSignallingApi
''',

    "WebRtcSession.kt": r'''package moe.rukamori.archivetune.together.webrtc

data class WebRtcSession(
    val sessionCode: String
)
''',

    "IceCandidateDto.kt": r'''package moe.rukamori.archivetune.together.webrtc

data class IceCandidateDto(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int
)
''',

    "SessionDescriptionDto.kt": r'''package moe.rukamori.archivetune.together.webrtc

data class SessionDescriptionDto(
    val type: String,
    val sdp: String
)
'''
}

for name, content in files.items():
    (BASE / name).write_text(content, encoding="utf-8")

print("Created WebRTC scaffolding.")
