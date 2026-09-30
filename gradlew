#!/usr/bin/env sh
set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DIST_VERSION=8.13
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/manual-wrapper/gradle-$DIST_VERSION"
ZIP_PATH="${GRADLE_USER_HOME:-$HOME/.gradle}/manual-wrapper/gradle-$DIST_VERSION-bin.zip"
URL="https://services.gradle.org/distributions/gradle-$DIST_VERSION-bin.zip"

if [ ! -x "$GRADLE_HOME_DIR/bin/gradle" ]; then
  mkdir -p "$(dirname "$ZIP_PATH")"
  echo "Gradle $DIST_VERSION is not cached; downloading from $URL" >&2
  if command -v curl >/dev/null 2>&1; then
    curl -fL "$URL" -o "$ZIP_PATH"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP_PATH" "$URL"
  else
    echo "Neither curl nor wget is available. Install Gradle $DIST_VERSION or open the project in Android Studio." >&2
    exit 1
  fi
  TMP_DIR="${GRADLE_HOME_DIR}.tmp.$$"
  rm -rf "$TMP_DIR"
  mkdir -p "$TMP_DIR"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$ZIP_PATH" -d "$(dirname "$GRADLE_HOME_DIR")"
  else
    echo "unzip is required for first-time Gradle bootstrap." >&2
    exit 1
  fi
fi

exec "$GRADLE_HOME_DIR/bin/gradle" -p "$APP_HOME" "$@"
