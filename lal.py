#!/usr/bin/env python3
import sys

FILE_PATH = "app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt"

# The correct version (first occurrence) – we will keep this exact text.
# We need to extract it from the file, but we can't because we don't have the exact text.
# Instead, we'll use a Python script that deletes the duplicates using line-based matching.

def read_file():
    with open(FILE_PATH, 'r') as f:
        return f.readlines()

def write_file(lines):
    with open(FILE_PATH, 'w') as f:
        f.writelines(lines)

def find_function_start(lines, signature):
    for i, line in enumerate(lines):
        if signature in line:
            return i
    return -1

def find_function_end(lines, start_idx):
    # Find matching brace
    brace_count = 0
    for i in range(start_idx, len(lines)):
        for ch in lines[i]:
            if ch == '{':
                brace_count += 1
            elif ch == '}':
                brace_count -= 1
                if brace_count == 0:
                    return i
    return -1

def main():
    lines = read_file()

    # Find all occurrences of "fun startTogetherPersonalHost"
    indices = []
    for i, line in enumerate(lines):
        if "fun startTogetherPersonalHost" in line:
            indices.append(i)

    if len(indices) < 3:
        print("Expected at least 3 occurrences, found", len(indices))
        sys.exit(1)

    # Keep the first one (index 0)
    # Delete from the start of the second (index 1) to the end of the third (index 2)
    # Actually, we need to delete the second and third functions entirely.
    # Let's find the end of each function.
    end_indices = []
    for idx in indices:
        end = find_function_end(lines, idx)
        if end == -1:
            print("Could not find end for function at line", idx)
            sys.exit(1)
        end_indices.append(end)

    # We will delete from the start of the second function to the end of the third function.
    # But we also need to delete the stray ioScope.launch block that appears after the helpers.
    # So we'll delete from the start of the second function (indices[1]) to the end of the stray block.

    # First, find the stray block after the helpers.
    # The helpers are createTogetherServer and startBroadcastLoop.
    # We need to locate them and then find the stray block after them.
    # We can find the start of createTogetherServer and startBroadcastLoop.
    # But we already have them in the file.
    # We'll delete everything from indices[1] to the end of the stray block.

    # To find the stray block: after the helpers, there is an ioScope.launch block.
    # Let's find the start of the stray block after the helpers.
    # We'll locate the line with "ioScope.launch(SilentHandler) {" after the helpers.
    helper_end = end_indices[2]  # end of the third function (which is the old placeholder)
    # Actually, the helpers are after the third function? No, in the output, the helpers appear after the third function.
    # Let's look at the output: after the third function, there is the helper createTogetherServer, then startBroadcastLoop, then another ioScope.launch block.
    # So we need to find the start of that stray ioScope.launch block.
    stray_start = -1
    for i in range(helper_end + 1, len(lines)):
        if "ioScope.launch(SilentHandler) {" in lines[i]:
            stray_start = i
            break
    if stray_start == -1:
        print("Could not find stray ioScope.launch block after helpers")
        sys.exit(1)

    # Find the end of the stray block
    stray_end = find_function_end(lines, stray_start)
    if stray_end == -1:
        print("Could not find end of stray block")
        sys.exit(1)

    # Now we delete from the start of the second function (indices[1]) to the end of the stray block (stray_end)
    # But we must keep the helpers! The helpers are between the third function and the stray block.
    # Actually, the helpers are after the third function and before the stray block.
    # So we need to delete:
    # - the second function (indices[1] to end_indices[1])
    # - the third function (indices[2] to end_indices[2])
    # - but keep the helpers that come after the third function.
    # The helpers start after end_indices[2] + 1.
    # So we delete from indices[1] to end_indices[2], then keep the helpers, then delete the stray block.
    # We'll do it in steps.

    # First, remove the second function.
    # We'll find the start of the second function and delete it.
    del lines[indices[1]:end_indices[1]+1]

    # After deletion, the indices shift. The third function is now at a new index.
    # We need to find it again.
    # Let's re-scan for "fun startTogetherPersonalHost" to get the new indices.
    new_indices = []
    for i, line in enumerate(lines):
        if "fun startTogetherPersonalHost" in line:
            new_indices.append(i)

    # Now we have two occurrences: the first (correct) and the second (which was the third originally).
    # Delete the second occurrence.
    if len(new_indices) >= 2:
        start2 = new_indices[1]
        end2 = find_function_end(lines, start2)
        if end2 != -1:
            del lines[start2:end2+1]

    # Now we have only the correct function.
    # Next, find the stray ioScope.launch block after the helpers and delete it.
    # We'll locate the helpers first.
    # Find createTogetherServer
    helper_start = -1
    for i, line in enumerate(lines):
        if "private suspend fun createTogetherServer" in line:
            helper_start = i
            break
    if helper_start == -1:
        print("Could not find createTogetherServer helper")
        sys.exit(1)

    # Find the start of the stray block after the helpers.
    stray_start = -1
    for i in range(helper_start + 1, len(lines)):
        if "ioScope.launch(SilentHandler) {" in lines[i]:
            stray_start = i
            break
    if stray_start == -1:
        print("Could not find stray ioScope.launch block")
        sys.exit(1)

    stray_end = find_function_end(lines, stray_start)
    if stray_end == -1:
        print("Could not find end of stray block")
        sys.exit(1)

    del lines[stray_start:stray_end+1]

    write_file(lines)
    print("Patch applied successfully. Removed duplicate functions and stray block.")

if __name__ == "__main__":
    main()
