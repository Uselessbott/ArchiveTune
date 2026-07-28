from pathlib import Path

# ------------------------------------------------------------------
# Patch MusicTogetherRepository.kt
# ------------------------------------------------------------------

repo = Path(
    "app/src/main/kotlin/moe/rukamori/archivetune/together/MusicTogetherRepository.kt"
)

text = repo.read_text(encoding="utf-8")

old_enum = """enum class MusicTogetherConnectionMode {
    LAN,
    ONLINE,
}"""

new_enum = """enum class MusicTogetherConnectionMode {
    LAN,
    ONLINE,
    CUSTOM,
}"""

if old_enum in text:
    text = text.replace(old_enum, new_enum, 1)
else:
    print("Enum already patched or not found.")

old_start = """                MusicTogetherConnectionMode.ONLINE -> {
                    service.startTogetherOnlineHost(
                        displayName = displayName,
                        settings = settings,
                    )
                }
            }"""

new_start = """                MusicTogetherConnectionMode.ONLINE -> {
                    service.startTogetherOnlineHost(
                        displayName = displayName,
                        settings = settings,
                    )
                }

                MusicTogetherConnectionMode.CUSTOM -> {
                    service.startTogetherCustomHost(
                        port = port,
                        displayName = displayName,
                        settings = settings,
                    )
                }
            }"""

if old_start in text:
    text = text.replace(old_start, new_start, 1)
else:
    print("startSession already patched or not found.")

old_join = """            when (mode) {
                MusicTogetherConnectionMode.LAN -> service.joinTogether(rawInput, displayName)
                MusicTogetherConnectionMode.ONLINE -> service.joinTogetherOnline(rawInput, displayName)
            }"""

new_join = """            when (mode) {
                MusicTogetherConnectionMode.LAN -> service.joinTogether(rawInput, displayName)
                MusicTogetherConnectionMode.ONLINE -> service.joinTogetherOnline(rawInput, displayName)
                MusicTogetherConnectionMode.CUSTOM -> service.joinTogetherCustom(rawInput, displayName)
            }"""

if old_join in text:
    text = text.replace(old_join, new_join, 1)
else:
    print("joinSession already patched or not found.")

repo.write_text(text, encoding="utf-8")


# ------------------------------------------------------------------
# Patch MusicService.kt
# ------------------------------------------------------------------

service = Path(
    "app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt"
)

text = service.read_text(encoding="utf-8")

if "fun startTogetherCustomHost(" not in text:

    anchor = "    fun joinTogether("

    stubs = """
    fun startTogetherCustomHost(
        port: Int,
        displayName: String,
        settings: moe.rukamori.archivetune.together.TogetherRoomSettings,
    ) {
        throw NotImplementedError("Custom Together host not implemented yet")
    }

    fun joinTogetherCustom(
        rawInput: String,
        displayName: String,
    ) {
        throw NotImplementedError("Custom Together join not implemented yet")
    }

"""

    if anchor in text:
        text = text.replace(anchor, stubs + anchor, 1)
    else:
        raise RuntimeError("Couldn't find insertion point in MusicService.kt")

service.write_text(text, encoding="utf-8")

print("✅ Commit 1 patch applied successfully.")
