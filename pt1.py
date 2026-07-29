# ==========================================================================
# PART 1 - MusicService.kt
# ==========================================================================

import re

# ---------- Import ----------
modify_file(
    "playback/MusicService.kt",
    r"import moe\.rukamori\.archivetune\.together\.TogetherOnlineHost",
    "import moe.rukamori.archivetune.together.TogetherOnlineHost\n"
    "import moe.rukamori.archivetune.together.webrtc.WebRtcTransport",
    required=True,
)

# ---------- Inject WebRtcTransport ----------
modify_file(
    "playback/MusicService.kt",
    r"(@Inject\s*\n\s*lateinit var database: MusicDatabase)",
    r"""\1

    @Inject
    lateinit var webRtcTransport: WebRtcTransport""",
    required=True,
)

# ---------- startTogetherOnlineHost signature ----------
modify_file(
    "playback/MusicService.kt",
    r"""fun startTogetherOnlineHost\(
\s*displayName: String,
\s*settings: moe\.rukamori\.archivetune\.together\.TogetherRoomSettings,
\s*\)""",
    """fun startTogetherOnlineHost(
        displayName: String,
        settings: moe.rukamori.archivetune.together.TogetherRoomSettings,
        useWebRtc: Boolean = false,
    )""",
    required=True,
)

# ---------- joinTogether signature ----------
modify_file(
    "playback/MusicService.kt",
    r"""fun joinTogether\(
\s*rawLink: String,
\s*displayName: String,
\s*\)""",
    """fun joinTogether(
        rawLink: String,
        displayName: String,
        useWebRtc: Boolean = false,
    )""",
    required=True,
)

# ---------- joinTogetherOnline signature ----------
modify_file(
    "playback/MusicService.kt",
    r"""fun joinTogetherOnline\(
\s*code: String,
\s*displayName: String,
\s*\)""",
    """fun joinTogetherOnline(
        code: String,
        displayName: String,
        useWebRtc: Boolean = false,
    )""",
    required=True,
)

# ---------- TogetherOnlineHost constructor ----------
modify_file(
    "playback/MusicService.kt",
    re.escape("""val onlineHost =
                moe.rukamori.archivetune.together.TogetherOnlineHost(
                    externalScope = ioScope,
                    sessionId = created.sessionId,
                    sessionKey = created.hostKey,
                    hostId = togetherHostId,
                    hostDisplayName = hostName,
                    initialSettings = created.settings,
                    clientId = getOrCreateTogetherClientId(),
                    bearerToken = togetherToken,
                )"""),
    """val onlineHost =
                moe.rukamori.archivetune.together.TogetherOnlineHost(
                    externalScope = ioScope,
                    sessionId = created.sessionId,
                    sessionKey = created.hostKey,
                    hostId = togetherHostId,
                    hostDisplayName = hostName,
                    initialSettings = created.settings,
                    clientId = getOrCreateTogetherClientId(),
                    bearerToken = togetherToken,
                    webRtcTransport = webRtcTransport,
                    useWebRtc = useWebRtc,
                )""",
    required=True,
)

# ---------- LAN TogetherClient constructor ----------
modify_file(
    "playback/MusicService.kt",
    re.escape("""val client =
                moe.rukamori.archivetune.together.TogetherClient(
                    ioScope,
                    clientId = getOrCreateTogetherClientId(),
                )"""),
    """val client =
                moe.rukamori.archivetune.together.TogetherClient(
                    ioScope,
                    clientId = getOrCreateTogetherClientId(),
                    webRtcTransport = webRtcTransport,
                    useWebRtc = useWebRtc,
                )""",
    required=True,
)

# ---------- Online TogetherClient constructor ----------
modify_file(
    "playback/MusicService.kt",
    re.escape("""val client =
                moe.rukamori.archivetune.together.TogetherClient(
                    ioScope,
                    clientId = getOrCreateTogetherClientId(),
                    bearerToken = togetherToken,
                )"""),
    """val client =
                moe.rukamori.archivetune.together.TogetherClient(
                    ioScope,
                    clientId = getOrCreateTogetherClientId(),
                    bearerToken = togetherToken,
                    webRtcTransport = webRtcTransport,
                    useWebRtc = useWebRtc,
                )""",
    required=True,
)

print("MusicService.kt patched successfully.")
