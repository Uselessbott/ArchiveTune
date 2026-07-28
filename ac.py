from pathlib import Path

path = Path(".github/workflows/android.yml")
text = path.read_text(encoding="utf-8")

old = """      - name: Build APK
        run: ./gradlew assembleGmsMobileUniversalDebug"""

new = """      - name: Print Runtime Classpath
        run: ./gradlew printRuntimeClasspath

      - name: Build APK
        run: ./gradlew assembleGmsMobileUniversalDebug"""

if old not in text:
    raise SystemExit("Couldn't find build step.")

text = text.replace(old, new, 1)

path.write_text(text, encoding="utf-8")

print("✓ Patched GitHub Actions workflow.")
