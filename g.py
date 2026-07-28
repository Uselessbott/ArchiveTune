from pathlib import Path

path = Path("app/src/main/kotlin/moe/rukamori/archivetune/App.kt")
text = path.read_text(encoding="utf-8")

old = "        NgrokProbe.run()\n"
text = text.replace(old, "", 1)

marker = "        BotGuardTokenGenerator.initialize(this)\n"

if marker not in text:
    raise SystemExit("Couldn't find BotGuardTokenGenerator.initialize(this)")

text = text.replace(
    marker,
    marker + "        NgrokProbe.run()\n",
    1,
)

path.write_text(text, encoding="utf-8")

print("✓ Moved NgrokProbe.run() into the normal app startup path.")
