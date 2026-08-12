#!/bin/sh

set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPERTIES="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"
GRADLE_VERSION=9.7.0
EXPECTED_SHA=84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae
DOWNLOAD_URL=https://services.gradle.org/distributions/gradle-9.7.0-bin.zip
GRADLE_USER_HOME=${GRADLE_USER_HOME:-"$HOME/.gradle"}
DIST_ROOT="$GRADLE_USER_HOME/wrapper/dists/gradle-9.7.0-bin/$EXPECTED_SHA"
GRADLE_HOME="$DIST_ROOT/gradle-$GRADLE_VERSION"

if [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/Library/Android/sdk" ]; then
    ANDROID_HOME="$HOME/Library/Android/sdk"
    export ANDROID_HOME
fi

if [ ! -f "$PROPERTIES" ]; then
    echo "Missing Gradle wrapper properties: $PROPERTIES" >&2
    exit 1
fi

if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
    mkdir -p "$DIST_ROOT"
    ARCHIVE=$(mktemp "${TMPDIR:-/tmp}/gradle-9.7.0.XXXXXX.zip")
    EXTRACT_DIR=$(mktemp -d "${TMPDIR:-/tmp}/gradle-9.7.0.XXXXXX")
    trap 'rm -f "$ARCHIVE"; rm -rf "$EXTRACT_DIR"' EXIT HUP INT TERM

    curl --fail --location --retry 3 --output "$ARCHIVE" "$DOWNLOAD_URL"
    if command -v sha256sum >/dev/null 2>&1; then
        ACTUAL_SHA=$(sha256sum "$ARCHIVE" | awk '{print $1}')
    else
        ACTUAL_SHA=$(shasum -a 256 "$ARCHIVE" | awk '{print $1}')
    fi
    if [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
        echo "Gradle distribution checksum mismatch." >&2
        exit 1
    fi

    unzip -q "$ARCHIVE" -d "$EXTRACT_DIR"
    if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
        rm -rf "$GRADLE_HOME"
        mv "$EXTRACT_DIR/gradle-$GRADLE_VERSION" "$GRADLE_HOME"
    fi
fi

exec "$GRADLE_HOME/bin/gradle" "$@"
