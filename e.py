from pathlib import Path
import re

path = Path("app/src/main/kotlin/moe/rukamori/archivetune/together/ngrok/NgrokProbe.kt")

text = path.read_text(encoding="utf-8")

pattern = re.compile(
    r"fun\s+run\s*\(\)\s*\{.*?\n\s*\}",
    re.DOTALL,
)

replacement = """fun run() {
        throw RuntimeException("NGROK PROBE EXECUTED")
    }"""

new_text, count = pattern.subn(replacement, text, count=1)

if count != 1:
    raise SystemExit("Couldn't find NgrokProbe.run()")

path.write_text(new_text, encoding="utf-8")

print("✓ NgrokProbe now deliberately crashes on startup.")
