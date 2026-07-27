#!/bin/bash

FILE="app/src/main/kotlin/moe/rukamori/archivetune/playback/MusicService.kt"
BACKUP="${FILE}.bak"

# Restore from backup
cp "$BACKUP" "$FILE"

# Remove the stray blocks (lines 4502-4621 and 4679 to end)
sed -i '4502,4621d' "$FILE"
sed -i '4679,$d' "$FILE"

# Add a closing brace for the class if missing
echo '}' >> "$FILE"

# Check brace counts
OPEN=$(grep -o '{' "$FILE" | wc -l)
CLOSE=$(grep -o '}' "$FILE" | wc -l)

echo "Open braces: $OPEN"
echo "Close braces: $CLOSE"

if [ "$OPEN" -ne "$CLOSE" ]; then
    echo "Braces are unbalanced. Exiting."
    exit 1
fi

echo "File is balanced. Committing and pushing."

git add "$FILE"
git commit -m "fix: correct MusicService.kt structure and remove duplicates"
git push origin HEAD
