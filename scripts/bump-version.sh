#!/bin/bash

# Version bumping script for SovereignDroid
# Usage: ./scripts/bump-version.sh <patch|minor|major>

set -e

VERSION_FILE="app/build.gradle.kts"
TAG_PREFIX="v"

# Function to get the current version
get_current_version() {
    grep "versionName = " "$VERSION_FILE" | sed 's/.*"\(.*\)".*/\1/'
}

# Function to bump version code
bump_version_code() {
    local current_code=$(grep "versionCode = " "$VERSION_FILE" | grep -oE "[0-9]+")
    local new_code=$((current_code + 1))
    sed -i "s/versionCode = .*/versionCode = $new_code/" "$VERSION_FILE"
    echo "Bumped versionCode from $current_code to $new_code"
}

# Function to update version name
update_version_name() {
    local current_version=$(get_current_version)
    echo "Current version: $current_version"
    
    case $1 in
        patch)
            # Replace last component (or -alpha, -beta, etc.) with .X
            if [[ $current_version =~ ^([0-9]+\.[0-9]+\.[0-9]+)-(.*)$ ]]; then
                local base="${BASH_REMATCH[1]}"
                local suffix="${BASH_REMATCH[2]}"
                local new_patch=$((10#$base + 1))
                NEW_VERSION="$base.$new_patch-$suffix"
            else
                local parts=(${current_version//./ })
                NEW_VERSION="${parts[0]}.${parts[1]}.$((${parts[2]} + 1))"
            fi
            ;;
        minor)
            if [[ $current_version =~ ^([0-9]+\.[0-9]+)\.([0-9]+)(-.*)?$ ]]; then
                NEW_VERSION="${BASH_REMATCH[1]}.$((${BASH_REMATCH[2]} + 1)).0"
            else
                NEW_VERSION="0.1.0"
            fi
            ;;
        major)
            if [[ $current_version =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)(-.*)?$ ]]; then
                NEW_VERSION="$((${BASH_REMATCH[1]} + 1)).0.0"
            else
                NEW_VERSION="1.0.0"
            fi
            ;;
        *)
            echo "Usage: $0 <patch|minor|major>"
            exit 1
            ;;
    esac
    
    echo "New version: $NEW_VERSION"
    sed -i "s/versionName = .*/versionName = \"$NEW_VERSION\"/" "$VERSION_FILE"
}

# Main logic
echo ""
echo "=== SovereignDroid Version Bumper ==="
echo ""

bump_version_code
update_version_name "$1"

echo ""
echo "Version bump complete!"
echo ""
echo "Next steps:"
echo "1. git add $VERSION_FILE"
echo "2. git commit -m \"Bump version to $(get_current_version)\""
echo "3. git tag ${TAG_PREFIX}$(get_current_version)"
echo "4. git push origin ${TAG_PREFIX}$(get_current_version)"
