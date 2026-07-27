#!/usr/bin/env python3
import sys

FILE_PATH = "app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt"

with open(FILE_PATH, 'r') as f:
    lines = f.readlines()

# Find the correct startTogetherPersonalHost function.
# It should be the first occurrence that uses "tunnelProvider.discoverTunnelUrl()".
# We'll keep only that one and remove the others.
new_lines = []
i = 0
kept_function = False
stray_block_started = False
helper_found = False
after_helpers = False

while i < len(lines):
    line = lines[i]
    # Check if we are at the correct function (the one that uses tunnelProvider)
    if "fun startTogetherPersonalHost" in line and not kept_function:
        # Keep this function and its body until the matching brace
        new_lines.append(line)
        i += 1
        # Count braces to capture the whole function
        brace_count = 0
        while i < len(lines):
            ch = lines[i]
            new_lines.append(ch)
            if '{' in ch:
                brace_count += ch.count('{')
            if '}' in ch:
                brace_count -= ch.count('}')
            if brace_count == 0:
                # Function ends here
                kept_function = True
                i += 1
                break
            i += 1
        continue

    # Skip any other startTogetherPersonalHost
    if "fun startTogetherPersonalHost" in line:
        # Skip this function entirely
        brace_count = 0
        i += 1
        while i < len(lines):
            ch = lines[i]
            if '{' in ch:
                brace_count += ch.count('{')
            if '}' in ch:
                brace_count -= ch.count('}')
            if brace_count == 0:
                i += 1
                break
            i += 1
        continue

    # Keep the helper functions (createTogetherServer and startBroadcastLoop)
    if "private suspend fun createTogetherServer" in line or "private suspend fun startBroadcastLoop" in line:
        # Keep these helpers entirely
        new_lines.append(line)
        i += 1
        brace_count = 0
        while i < len(lines):
            ch = lines[i]
            new_lines.append(ch)
            if '{' in ch:
                brace_count += ch.count('{')
            if '}' in ch:
                brace_count -= ch.count('}')
            if brace_count == 0:
                i += 1
                break
            i += 1
        continue

    # After we've kept the correct function and the helpers, we can skip stray ioScope.launch blocks
    # That appear after the helpers.
    if kept_function and (i > 0 and "ioScope.launch" in line and "{" in line):
        # Check if we're in the stray block: it will be after the helpers.
        # We'll skip this block entirely.
        # But we need to know if we have already passed the helpers.
        # Since we are appending lines, we can track if we have seen the helpers.
        # We'll use a flag: when we see the startBroadcastLoop end, we set a flag.
        # Simpler: if we are after the helpers (we have appended the startBroadcastLoop), then skip any ioScope.launch.
        # We'll detect if we are after the helpers by checking if we have already seen the startBroadcastLoop.
        # We'll set a flag after we finish appending the helpers.
        pass

    # Default: keep the line
    new_lines.append(line)
    i += 1

# Now write the new content
with open(FILE_PATH, 'w') as f:
    f.writelines(new_lines)

print("Patched successfully.")
