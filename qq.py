#!/usr/bin/env python3
import sys

FILE_PATH = "app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt"

def read_file():
    with open(FILE_PATH, 'r') as f:
        return f.readlines()

def write_file(lines):
    with open(FILE_PATH, 'w') as f:
        f.writelines(lines)

def find_function_range(lines, start_pattern, end_pattern=None):
    """Find a block by start pattern and brace matching."""
    start_idx = None
    for i, line in enumerate(lines):
        if start_pattern in line:
            start_idx = i
            break
    if start_idx is None:
        return None, None
    # Find matching brace
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

def find_first_occurrence(lines, pattern):
    for i, line in enumerate(lines):
        if pattern in line:
            return i
    return -1

def main():
    lines = read_file()

    # Find the first correct function
    func_start = find_first_occurrence(lines, "fun startTogetherPersonalHost")
    if func_start == -1:
        print("Could not find startTogetherPersonalHost")
        sys.exit(1)
    func_end = find_function_range(lines, "fun startTogetherPersonalHost")[1]
    if func_end is None:
        print("Could not find end of function")
        sys.exit(1)

    # Find the helper createTogetherServer
    helper_start = find_first_occurrence(lines, "private suspend fun createTogetherServer")
    if helper_start == -1:
        print("Could not find createTogetherServer")
        sys.exit(1)
    helper_end = find_function_range(lines, "private suspend fun createTogetherServer")[1]
    if helper_end is None:
        print("Could not find end of createTogetherServer")
        sys.exit(1)

    # Find startBroadcastLoop
    loop_start = find_first_occurrence(lines, "private suspend fun startBroadcastLoop")
    if loop_start == -1:
        print("Could not find startBroadcastLoop")
        sys.exit(1)
    loop_end = find_function_range(lines, "private suspend fun startBroadcastLoop")[1]
    if loop_end is None:
        print("Could not find end of startBroadcastLoop")
        sys.exit(1)

    # We want: everything up to func_end (inclusive), then helpers (from helper_start to loop_end inclusive),
    # then discard everything after loop_end.
    # But there may be blank lines between; we'll keep them.

    # Build new list
    new_lines = []

    # Add lines from start of file to end of first function (inclusive)
    new_lines.extend(lines[:func_end+1])

    # Add a newline if needed
    if func_end+1 < helper_start:
        # Keep the lines between the function and the helpers (they might be blank)
        new_lines.extend(lines[func_end+1:helper_start])

    # Add helpers
    new_lines.extend(lines[helper_start:loop_end+1])

    # Add everything after loop_end? No, we discard everything after loop_end.
    # But we need to keep the rest of the file (the companion object, etc.).
    # Actually, after loop_end, there is the companion object and the rest of the class.
    # We need to keep that.
    # From the output, after the final stray block there is still the companion object.
    # So we need to find where the stray block ends and keep the rest.
    # But we don't know where the stray block ends exactly; we can find the next occurrence of "companion object" or the end of the file.
    # Let's look for "companion object" after the helpers.
    comp_start = find_first_occurrence(lines, "companion object")
    if comp_start == -1:
        print("Could not find companion object")
        sys.exit(1)
    # Find the end of the stray block: it should be before the companion object.
    # We'll just keep everything from the companion object onward.
    new_lines.extend(lines[comp_start:])

    write_file(new_lines)
    print("Patch applied successfully.")

if __name__ == "__main__":
    main()

