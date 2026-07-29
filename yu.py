#!/usr/bin/env python3

from pathlib import Path
import shutil
import sys

FILE = Path("app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt")
BACKUP = FILE.with_suffix(FILE.suffix + ".client2.bak")

shutil.copy2(FILE, BACKUP)
print(f"Backup created: {BACKUP}")

text = FILE.read_text(encoding="utf-8")

# ---------------- LAN ----------------

lan_old = (
    "clientId = getOrCreateTogetherClientId(),\n"
    "                )"
)

lan_new = (
    "clientId = getOrCreateTogetherClientId(),\n"
    "                    webRtcTransport = webRtcTransport,\n"
    "                    useWebRtc = useWebRtc,\n"
    "                )"
)

if "webRtcTransport = webRtcTransport" not in text[text.find("TogetherClient("):text.find("TogetherClient(")+300]:
    if lan_old not in text:
        sys.exit("Couldn't find LAN constructor anchor.")
    text = text.replace(lan_old, lan_new, 1)
    print("Patched LAN constructor.")
else:
    print("LAN already patched.")

# ---------------- ONLINE ----------------

online_old = (
    "bearerToken = togetherToken,\n"
    "                )"
)

online_new = (
    "bearerToken = togetherToken,\n"
    "                    webRtcTransport = webRtcTransport,\n"
    "                    useWebRtc = useWebRtc,\n"
    "                )"
)

idx = text.find("bearerToken = togetherToken")

if idx == -1:
    sys.exit("Couldn't find online constructor.")

window = text[max(0, idx-200):idx+300]

if "webRtcTransport = webRtcTransport" in window:
    print("Online already patched.")
else:
    if online_old not in text:
        sys.exit("Couldn't find online constructor anchor.")
    text = text.replace(online_old, online_new, 1)
    print("Patched online constructor.")

FILE.write_text(text, encoding="utf-8")
print("Done.")
