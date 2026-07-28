from pathlib import Path
import re

gradle = Path("app/build.gradle.kts")

text = gradle.read_text(encoding="utf-8")

dependency = '    implementation("org.webrtc:google-webrtc:1.0.32006")\n'

if "org.webrtc:google-webrtc" not in text:
    m = re.search(r'dependencies\s*\{\n', text)
    if not m:
        raise RuntimeError("Couldn't locate dependencies block.")

    insert = m.end()

    text = text[:insert] + dependency + text[insert:]

    gradle.write_text(text, encoding="utf-8")
    print("✓ Added Google WebRTC dependency.")
else:
    print("✓ Dependency already exists.")
