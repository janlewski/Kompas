#!/usr/bin/env bash
# Fails when uses-permission names differ from scripts/allowed-permissions.txt.
# Pass a merged AndroidManifest.xml, or a release APK.
set -euo pipefail

INPUT="${1:?path to merged manifest or release apk}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ALLOW="$ROOT/scripts/allowed-permissions.txt"

found="$(mktemp)"
allowed="$(mktemp)"
trap 'rm -f "$found" "$allowed"' EXIT

if [[ "$INPUT" == *.apk ]]; then
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    SDK="$ANDROID_HOME"
  elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    SDK="$ANDROID_SDK_ROOT"
  else
    SDK="$HOME/Library/Android/sdk"
  fi
  AAPT="$(find "$SDK/build-tools" -name aapt -type f | sort | tail -1)"
  if [[ -z "$AAPT" ]]; then
    echo "aapt not found under $SDK/build-tools" >&2
    exit 2
  fi
  "$AAPT" dump permissions "$INPUT" | sed -n "s/.*name='\([^']*\)'.*/\1/p" | sort -u >"$found"
else
  sed -n 's/.*<uses-permission[^>]*android:name="\([^"]*\)".*/\1/p' "$INPUT" | sort -u >"$found"
fi

grep -v '^#' "$ALLOW" | grep -v '^[[:space:]]*$' | sort -u >"$allowed"

if ! diff -u "$allowed" "$found"; then
  echo "Permissions do not match the allow-list." >&2
  exit 1
fi

echo "Permissions match the allow-list."
