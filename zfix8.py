from pathlib import Path
import re

p = Path("app/src/main/kotlin/moe/rukamori/archivetune/together/manual/QrWebRtcSession.kt")
s = p.read_text(encoding="utf-8")

pattern = re.compile(
    r"""lastImportedFingerprint = fingerprint

\s*val signalPacket = QrCodec\.decode\(packets\)
\s*remoteSessionId = signalPacket\.sessionId""",
    re.MULTILINE,
)

replacement = """lastImportedFingerprint = fingerprint

        val signalPacket = QrCodec.decode(packets)

        val peer =
            hostPeers.getOrPut(signalPacket.sessionId) {
                HostPeerSession(
                    sessionId = signalPacket.sessionId,
                    transport = transportFactory(),
                )
            }

        peer.remoteSessionId = signalPacket.sessionId

        // Legacy compatibility
        remoteSessionId = signalPacket.sessionId"""

s, n = pattern.subn(replacement, s, count=1)

if n != 1:
    raise SystemExit("Couldn't patch submitPackets()")

p.write_text(s, encoding="utf-8")
print("Patch applied.")
