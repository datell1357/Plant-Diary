#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/planterior-release-auth.XXXXXX")"
EMPTY_CONFIG="$TMP_DIR/empty.properties"
MALFORMED_CONFIG="$TMP_DIR/malformed.properties"
VALID_CONFIG="$TMP_DIR/valid.properties"
KEYSTORE="$TMP_DIR/verification.keystore"
STORE_PASSWORD="verification-store-password"
KEY_PASSWORD="$STORE_PASSWORD"
KEY_ALIAS="verification"
GOOGLE_CLIENT_ID="123456789012-verificationonlyclient.apps.googleusercontent.com"
FIREBASE_PROJECT_ID="demo-planterior-release"
FIREBASE_APP_ID="1:123456789012:android:0123456789abcdef"
FIREBASE_API_KEY="AIza$(printf 'v%.0s' {1..35})"
GRADLE=("$ROOT_DIR/gradlew" --no-daemon --console=plain)
AUTH_ENV=(
    -u PLANTERIOR_GOOGLE_WEB_CLIENT_ID
    -u PLANTERIOR_FIREBASE_PROJECT_ID
    -u PLANTERIOR_FIREBASE_APP_ID
    -u PLANTERIOR_FIREBASE_API_KEY
    -u PLANTERIOR_RELEASE_STORE_FILE
    -u PLANTERIOR_RELEASE_STORE_PASSWORD
    -u PLANTERIOR_RELEASE_KEY_ALIAS
    -u PLANTERIOR_RELEASE_KEY_PASSWORD
)

cleanup() {
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

fail() {
    printf 'release auth contract failed: %s\n' "$1" >&2
    exit 1
}

run_isolated() {
    env "${AUTH_ENV[@]}" "${GRADLE[@]}" "$@"
}

assert_redacted() {
    local log_file="$1"
    for value in \
        "$GOOGLE_CLIENT_ID" \
        "$FIREBASE_PROJECT_ID" \
        "$FIREBASE_APP_ID" \
        "$FIREBASE_API_KEY" \
        "$STORE_PASSWORD" \
        'not-a-client-quote-"-redaction-check' \
        "INVALID_PROJECT" \
        "not-an-app" \
        "not-an-api-key"; do
        if grep -Fq "$value" "$log_file"; then
            fail "configuration value appeared in build output"
        fi
    done
}

: > "$EMPTY_CONFIG"
cat > "$MALFORMED_CONFIG" <<'PROPERTIES'
planterior.release.googleWebClientId=not-a-client-quote-"-redaction-check
planterior.release.firebaseProjectId=INVALID_PROJECT
planterior.release.firebaseAppId=not-an-app
planterior.release.firebaseApiKey=not-an-api-key
PROPERTIES

if run_isolated \
    -Pplanterior.release.configFile="$EMPTY_CONFIG" \
    :app:assembleRelease > "$TMP_DIR/missing.log" 2>&1; then
    fail "missing release authentication configuration was accepted"
fi
grep -Fq "Release authentication configuration is incomplete" "$TMP_DIR/missing.log" ||
    fail "missing configuration error is not explicit"
assert_redacted "$TMP_DIR/missing.log"

env "${AUTH_ENV[@]}" \
    PLANTERIOR_GOOGLE_WEB_CLIENT_ID="$GOOGLE_CLIENT_ID" \
    PLANTERIOR_FIREBASE_PROJECT_ID="$FIREBASE_PROJECT_ID" \
    PLANTERIOR_FIREBASE_APP_ID="$FIREBASE_APP_ID" \
    PLANTERIOR_FIREBASE_API_KEY="$FIREBASE_API_KEY" \
    "${GRADLE[@]}" \
    -Pplanterior.release.configFile="$EMPTY_CONFIG" \
    :app:validateReleaseAuthConfiguration > "$TMP_DIR/environment.log" 2>&1 ||
    fail "valid environment configuration was rejected"
assert_redacted "$TMP_DIR/environment.log"

run_isolated \
    -Pplanterior.release.configFile="$EMPTY_CONFIG" \
    -Pplanterior.release.googleWebClientId="$GOOGLE_CLIENT_ID" \
    -Pplanterior.release.firebaseProjectId="$FIREBASE_PROJECT_ID" \
    -Pplanterior.release.firebaseAppId="$FIREBASE_APP_ID" \
    -Pplanterior.release.firebaseApiKey="$FIREBASE_API_KEY" \
    :app:validateReleaseAuthConfiguration > "$TMP_DIR/gradle-properties.log" 2>&1 ||
    fail "valid Gradle property configuration was rejected"
assert_redacted "$TMP_DIR/gradle-properties.log"

if run_isolated \
    -Pplanterior.release.configFile="$MALFORMED_CONFIG" \
    :app:assembleRelease > "$TMP_DIR/malformed.log" 2>&1; then
    fail "malformed release authentication configuration was accepted"
fi
grep -Fq "Release authentication configuration is malformed" "$TMP_DIR/malformed.log" ||
    fail "malformed configuration error is not explicit"
assert_redacted "$TMP_DIR/malformed.log"

keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass "$STORE_PASSWORD" \
    -keypass "$KEY_PASSWORD" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 2 \
    -dname "CN=Planterior Verification,O=Planterior,C=KR" \
    -noprompt > /dev/null 2>&1

cat > "$VALID_CONFIG" <<PROPERTIES
planterior.release.googleWebClientId=$GOOGLE_CLIENT_ID
planterior.release.firebaseProjectId=$FIREBASE_PROJECT_ID
planterior.release.firebaseAppId=$FIREBASE_APP_ID
planterior.release.firebaseApiKey=$FIREBASE_API_KEY
planterior.release.storeFile=$KEYSTORE
planterior.release.storePassword=$STORE_PASSWORD
planterior.release.keyAlias=$KEY_ALIAS
planterior.release.keyPassword=$KEY_PASSWORD
PROPERTIES

run_isolated \
    -Pplanterior.release.configFile="$VALID_CONFIG" \
    :app:assembleRelease > "$TMP_DIR/release.log" 2>&1 || {
        cat "$TMP_DIR/release.log" >&2
        fail "valid external verification configuration did not build"
    }
assert_redacted "$TMP_DIR/release.log"

APK="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
[[ -f "$APK" ]] || fail "release APK was not produced"

APK_ANALYZER="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/cmdline-tools/latest/bin/apkanalyzer"
APK_SIGNER="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/build-tools/37.0.0/apksigner"
[[ -x "$APK_ANALYZER" ]] || fail "apkanalyzer is unavailable"
[[ -x "$APK_SIGNER" ]] || fail "apksigner is unavailable"

"$APK_SIGNER" verify "$APK" > /dev/null || fail "ephemeral release APK is not signed"
"$APK_ANALYZER" dex packages --defined-only "$APK" > "$TMP_DIR/packages.txt"
for class_name in \
    "com.planterior.helper.auth.AuthRuntime" \
    "com.planterior.helper.feature.auth.GoogleCredentialProvider" \
    "com.planterior.helper.feature.auth.AppleWebAuthProvider" \
    "com.planterior.helper.feature.auth.FirebaseIdentityAdapter" \
    "com.planterior.helper.feature.auth.FirebaseAppleCallable"; do
    grep -Fq "$class_name" "$TMP_DIR/packages.txt" || fail "$class_name was stripped from release DEX"
    printf 'retained release class: %s\n' "$class_name"
done

unzip -p "$APK" 'classes*.dex' > "$TMP_DIR/release.dex"
for configured_value in "$GOOGLE_CLIENT_ID" "$FIREBASE_PROJECT_ID" "$FIREBASE_APP_ID" "$FIREBASE_API_KEY"; do
    grep -aFq "$configured_value" "$TMP_DIR/release.dex" ||
        fail "external authentication configuration was not packaged"
done

unzip -p "$APK" > "$TMP_DIR/release.contents"
if grep -aEq 'DebugAuthHarness|DebugAuthControls|QA 인증 시나리오|qa-google-account-a|KAKAO|Kakao|kakao|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY' "$TMP_DIR/release.contents"; then
    fail "release artifact contains debug auth, Kakao, or private-key material"
fi

printf 'release auth contract passed: missing/malformed rejected; signed supplied build retained Google+Apple production paths\n'
