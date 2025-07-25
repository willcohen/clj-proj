#!/usr/bin/env bash
# Generate checksum for native library dependencies
# This helps verify when native rebuilds are needed

set -euo pipefail

# Files that affect native builds
NATIVE_DEPS=(
    "flake.nix"
    "flake.lock"
    "bb.edn"
)

# Extract versions from bb.edn
PROJ_VERSION=$(grep -oP 'proj-version\s*"\K[^"]+' bb.edn || echo "unknown")
SQLITE_VERSION=$(grep -oP 'sqlite-version-url\s*"\K[^"]+' bb.edn || echo "unknown")
LIBTIFF_VERSION=$(grep -oP 'libtiff-version\s*"\K[^"]+' bb.edn || echo "unknown")

echo "=== Native Build Dependencies ==="
echo "PROJ version: $PROJ_VERSION"
echo "SQLite version: $SQLITE_VERSION"
echo "LibTIFF version: $LIBTIFF_VERSION"
echo ""

# Generate hash of all dependency files
if command -v sha256sum >/dev/null 2>&1; then
    HASH_CMD="sha256sum"
elif command -v shasum >/dev/null 2>&1; then
    HASH_CMD="shasum -a 256"
else
    echo "Error: No SHA256 command found"
    exit 1
fi

NATIVE_HASH=$(cat ${NATIVE_DEPS[@]} 2>/dev/null | $HASH_CMD | cut -d' ' -f1)

echo "Files included in hash:"
for file in "${NATIVE_DEPS[@]}"; do
    if [[ -f "$file" ]]; then
        echo "  ✓ $file"
    else
        echo "  ✗ $file (missing)"
    fi
done

echo ""
echo "Native dependencies hash: $NATIVE_HASH"
echo ""
echo "Cache key: native-libs-${NATIVE_HASH}-proj${PROJ_VERSION}-sqlite${SQLITE_VERSION}-tiff${LIBTIFF_VERSION}"