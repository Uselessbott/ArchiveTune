from pathlib import Path

path = Path("settings.gradle.kts")
text = path.read_text(encoding="utf-8")

repo = """
        maven {
            name = "WebRTC"
            url = uri("https://repo.maven.apache.org/maven2")
        }

        maven {
            name = "Liferay"
            url = uri("https://repository-cdn.liferay.com/nexus/content/repositories/public")
        }
"""

marker = "        exclusiveContent {"

if "repository-cdn.liferay.com" in text:
    print("Repository already exists.")
    raise SystemExit

if marker not in text:
    raise SystemExit("Couldn't find insertion point.")

text = text.replace(marker, repo + "\n        exclusiveContent {", 1)

path.write_text(text, encoding="utf-8")

print("✓ Added WebRTC repository.")
