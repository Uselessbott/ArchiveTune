from pathlib import Path

path = Path("app/src/main/kotlin/moe/rukamori/archivetune/together/webrtc/WebRtcTransport.kt")

text = path.read_text(encoding="utf-8")

if "import kotlinx.coroutines.cancelChildren" not in text:
    lines = text.splitlines()

    for i, line in enumerate(lines):
        if line.startswith("import ") and "kotlinx.coroutines.cancelChildren" not in line:
            last_import = i

    lines.insert(last_import + 1, "import kotlinx.coroutines.cancelChildren")

    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("✓ Added import kotlinx.coroutines.cancelChildren")
else:
    print("✓ Import already exists")
