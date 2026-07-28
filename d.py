from pathlib import Path

app = Path("app/src/main/kotlin/moe/rukamori/archivetune/App.kt")

text = app.read_text(encoding="utf-8")

IMPORT = "import moe.rukamori.archivetune.together.ngrok.NgrokProbe"

if IMPORT not in text:
    marker = "import moe.rukamori.archivetune.storage.StorageLocationRepository"
    if marker not in text:
        raise SystemExit(f"Couldn't find import marker:\n{marker}")
    text = text.replace(
        marker,
        marker + "\n" + IMPORT,
        1,
    )

CALL = "        NgrokProbe.run()"

if CALL not in text:
    marker = "        Timber.plant(Timber.DebugTree())"
    if marker not in text:
        raise SystemExit(f"Couldn't find insertion marker:\n{marker}")

    text = text.replace(
        marker,
        marker + "\n" + CALL,
        1,
    )

app.write_text(text, encoding="utf-8")

print("✓ Injected NgrokProbe.run() into App.kt")
