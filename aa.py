#!/usr/bin/env python3
import sys
import re

FILE_PATH = "app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt"

def find_function_body(lines, start_pattern):
    """Find a function by its signature and return its line range [start, end] (inclusive)."""
    start_idx = None
    for i, line in enumerate(lines):
        if start_pattern in line:
            start_idx = i
            break
    if start_idx is None:
        return None, None

    brace_count = 0
    for i in range(start_idx, len(lines)):
        for ch in lines[i]:
            if ch == '{':
                brace_count += 1
            elif ch == '}':
                brace_count -= 1
                if brace_count == 0:
                    return start_idx, i
    return start_idx, None

def find_block(lines, start_pattern):
    """Find a block that starts with a pattern and find its matching brace."""
    start_idx = None
    for i, line in enumerate(lines):
        if start_pattern in line and not any(keyword in line for keyword in ["fun", "private suspend fun"]):
            # It's a stray block if it's not part of a function definition
            start_idx = i
            break
    if start_idx is None:
        return None, None
    brace_count = 0
    for i in range(start_idx, len(lines)):
        for ch in lines[i]:
            if ch == '{':
                brace_count += 1
            elif ch == '}':
                brace_count -= 1
                if brace_count == 0:
                    return start_idx, i
    return start_idx, None

def main():
    with open(FILE_PATH, 'r') as f:
        lines = f.readlines()

    # Find all startTogetherPersonalHost occurrences
    indices = []
    for i, line in enumerate(lines):
        if "fun startTogetherPersonalHost" in line:
            indices.append(i)

    if len(indices) >= 2:
        # Keep the first one (the correct one) and delete the others
        # The correct one is the first occurrence (index 0)
        for idx in reversed(indices[1:]):
            end = find_function_body(lines, "fun startTogetherPersonalHost")[1] if idx == indices[1] else find_function_body(lines, "fun startTogetherPersonalHost")[1]
            # Actually, we need to find the end for each occurrence, but the function above finds the first.
            # Let's use the generic find.
            start, end = find_function_body(lines, "fun startTogetherPersonalHost")
            # But we need to delete the specific one. We'll delete from the start to the end of that function.
            # Since the function bodies are nested, we can't simply delete by range without breaking.
            # Instead, we'll rebuild the file and skip the unwanted ones.
            pass

    # Simpler: use sed with exact line numbers that we already know from the backup.
    print("Use the following commands:")
    print("cp app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt.bak app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt")
    print("sed -i '4502,4621d' app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt")
    print("sed -i '4679,$d' app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt")
    print("echo '}' >> app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt")
    print("echo 'Done.'")

if __name__ == "__main__":
    main()
