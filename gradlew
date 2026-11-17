#!/usr/bin/env sh
set -eu
VERSION=8.9
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/ikev2-manager"
DIST="$BASE/gradle-$VERSION"
if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$BASE"
  TMP="${TMPDIR:-/tmp}/gradle-$VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fL "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$TMP"
  else
    wget -O "$TMP" "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
  fi
  unzip -q -o "$TMP" -d "$BASE"
  rm -f "$TMP"
fi
exec "$DIST/bin/gradle" "$@"
