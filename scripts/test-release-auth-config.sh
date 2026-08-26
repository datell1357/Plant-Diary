#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/planterior-release-auth.XXXXXX")"
EMPTY_CONFIG="$TMP_DIR/empty.properties"
MALFORMED_CONFIG="$TMP_DIR/malformed.properties"
VALID_CONFIG="$TMP_DIR/valid.properties"
KEYSTORE="$TMP_DIR/verification.keystore"
SEPARATE_KEYSTORE="$TMP_DIR/separate-key-password.jks"
CERTIFICATE_ONLY_KEYSTORE="$TMP_DIR/certificate-only.jks"
CORRUPT_KEYSTORE="$TMP_DIR/corrupt.keystore"
STORE_PASSWORD="verification-store-password"
KEY_PASSWORD="$STORE_PASSWORD"
SEPARATE_STORE_PASSWORD="separate-store-password"
SEPARATE_KEY_PASSWORD="separate-key-password"
KEY_ALIAS="planterior-release-key-alias-7f3c2d"
CERTIFICATE_ALIAS="certificate-only"
WRONG_SECRET="deliberately-wrong-secret"
MISSING_ALIAS="missing-private-key-alias"
GOOGLE_CLIENT_ID="123456789012-verificationonlyclient.apps.googleusercontent.com"
FIREBASE_PROJECT_ID="demo-planterior-release"
FIREBASE_APP_ID="1:123456789012:android:0123456789abcdef"
FIREBASE_API_KEY="AIza$(printf 'v%.0s' {1..35})"
FIREBASE_STORAGE_BUCKET="demo-planterior-release.firebasestorage.app"
BUNDLETOOL_JAR="${BUNDLETOOL_JAR:-}"
BUNDLETOOL_CLASSPATH="${BUNDLETOOL_CLASSPATH:-}"
GRADLE=("$ROOT_DIR/gradlew" --no-daemon --console=plain)
CACHED_GRADLE=("$ROOT_DIR/gradlew" --console=plain --configuration-cache)
LOCAL_PROPERTIES="$ROOT_DIR/local.properties"
LOCAL_PROPERTIES_BACKUP="$TMP_DIR/local.properties.backup"
HAD_LOCAL_PROPERTIES=false
AUTH_ENV=(
    -u PLANTERIOR_GOOGLE_WEB_CLIENT_ID
    -u PLANTERIOR_FIREBASE_PROJECT_ID
    -u PLANTERIOR_FIREBASE_APP_ID
    -u PLANTERIOR_FIREBASE_API_KEY
    -u PLANTERIOR_FIREBASE_STORAGE_BUCKET
    -u PLANTERIOR_RELEASE_STORE_FILE
    -u PLANTERIOR_RELEASE_STORE_PASSWORD
    -u PLANTERIOR_RELEASE_KEY_ALIAS
    -u PLANTERIOR_RELEASE_KEY_PASSWORD
)

cleanup() {
    if [[ "$HAD_LOCAL_PROPERTIES" == true ]]; then
        cp "$LOCAL_PROPERTIES_BACKUP" "$LOCAL_PROPERTIES"
    else
        rm -f "$LOCAL_PROPERTIES"
    fi
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

run_cached_isolated() {
    env "${AUTH_ENV[@]}" "${CACHED_GRADLE[@]}" "$@"
}

assert_redacted() {
    local log_file="$1"
    for value in \
        "$GOOGLE_CLIENT_ID" \
        "$FIREBASE_PROJECT_ID" \
        "$FIREBASE_APP_ID" \
        "$FIREBASE_API_KEY" \
        "$FIREBASE_STORAGE_BUCKET" \
        "$STORE_PASSWORD" \
        "$SEPARATE_STORE_PASSWORD" \
        "$SEPARATE_KEY_PASSWORD" \
        "$WRONG_SECRET" \
        "$KEY_ALIAS" \
        "$CERTIFICATE_ALIAS" \
        "$MISSING_ALIAS" \
        "$KEYSTORE" \
        "$SEPARATE_KEYSTORE" \
        "$CERTIFICATE_ONLY_KEYSTORE" \
        "$CORRUPT_KEYSTORE" \
        'not-a-client-quote-"-redaction-check' \
        "INVALID_PROJECT" \
        "not-an-app" \
        "not-an-api-key" \
        "not-a-bucket/" \
        "quote-escape-marker" \
        "backslash-escape-marker" \
        "newline-escape-marker"; do
        if grep -Fq "$value" "$log_file"; then
            fail "configuration value appeared in build output"
        fi
    done
}

printf 'dependency verification initialized\n' > "$TMP_DIR/non-secret-tool-output.log"
assert_redacted "$TMP_DIR/non-secret-tool-output.log"

if [[ -f "$LOCAL_PROPERTIES" ]]; then
    cp "$LOCAL_PROPERTIES" "$LOCAL_PROPERTIES_BACKUP"
    HAD_LOCAL_PROPERTIES=true
fi

: > "$EMPTY_CONFIG"
cat > "$MALFORMED_CONFIG" <<'PROPERTIES'
planterior.release.googleWebClientId=not-a-client-quote-"-redaction-check
planterior.release.firebaseProjectId=INVALID_PROJECT
planterior.release.firebaseAppId=not-an-app
planterior.release.firebaseApiKey=not-an-api-key
planterior.release.firebaseStorageBucket=not-a-bucket/
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
    PLANTERIOR_FIREBASE_STORAGE_BUCKET="$FIREBASE_STORAGE_BUCKET" \
    "${GRADLE[@]}" \
    -Pplanterior.release.configFile="$EMPTY_CONFIG" \
    :app:validateReleaseAuthConfiguration > "$TMP_DIR/environment.log" 2>&1 ||
    fail "valid environment configuration was rejected"
assert_redacted "$TMP_DIR/environment.log"

env "${AUTH_ENV[@]}" \
    PLANTERIOR_GOOGLE_WEB_CLIENT_ID="  $GOOGLE_CLIENT_ID  " \
    PLANTERIOR_FIREBASE_PROJECT_ID="  $FIREBASE_PROJECT_ID  " \
    PLANTERIOR_FIREBASE_APP_ID="  $FIREBASE_APP_ID  " \
    PLANTERIOR_FIREBASE_API_KEY="  $FIREBASE_API_KEY  " \
    PLANTERIOR_FIREBASE_STORAGE_BUCKET="  $FIREBASE_STORAGE_BUCKET  " \
    "${GRADLE[@]}" \
    -Pplanterior.release.configFile="$EMPTY_CONFIG" \
    :app:validateReleaseAuthConfiguration > "$TMP_DIR/trimmed-environment.log" 2>&1 ||
    fail "surrounding configuration whitespace was not trimmed safely"
assert_redacted "$TMP_DIR/trimmed-environment.log"

run_isolated \
    -Pplanterior.release.configFile="$EMPTY_CONFIG" \
    -Pplanterior.release.googleWebClientId="$GOOGLE_CLIENT_ID" \
    -Pplanterior.release.firebaseProjectId="$FIREBASE_PROJECT_ID" \
    -Pplanterior.release.firebaseAppId="$FIREBASE_APP_ID" \
    -Pplanterior.release.firebaseApiKey="$FIREBASE_API_KEY" \
    -Pplanterior.release.firebaseStorageBucket="$FIREBASE_STORAGE_BUCKET" \
    :app:validateReleaseAuthConfiguration > "$TMP_DIR/gradle-properties.log" 2>&1 ||
    fail "valid Gradle property configuration was rejected"
assert_redacted "$TMP_DIR/gradle-properties.log"

printf '\n' >> "$LOCAL_PROPERTIES"
cat >> "$LOCAL_PROPERTIES" <<PROPERTIES
planterior.release.googleWebClientId=$GOOGLE_CLIENT_ID
planterior.release.firebaseProjectId=$FIREBASE_PROJECT_ID
planterior.release.firebaseAppId=$FIREBASE_APP_ID
planterior.release.firebaseApiKey=$FIREBASE_API_KEY
planterior.release.firebaseStorageBucket=$FIREBASE_STORAGE_BUCKET
PROPERTIES
run_isolated :app:validateReleaseAuthConfiguration > "$TMP_DIR/local-properties.log" 2>&1 ||
    fail "valid ignored local.properties configuration was rejected"
assert_redacted "$TMP_DIR/local-properties.log"
printf 'accepted release auth source modes: environment, Gradle properties, ignored local.properties\n'

cat >> "$LOCAL_PROPERTIES" <<'PROPERTIES'
planterior.release.googleWebClientId=invalid-local-precedence
planterior.release.firebaseProjectId=INVALID_LOCAL_PRECEDENCE
planterior.release.firebaseAppId=invalid-local-precedence
planterior.release.firebaseApiKey=invalid-local-precedence
planterior.release.firebaseStorageBucket=invalid/local/precedence
PROPERTIES
env "${AUTH_ENV[@]}" \
    PLANTERIOR_GOOGLE_WEB_CLIENT_ID="$GOOGLE_CLIENT_ID" \
    PLANTERIOR_FIREBASE_PROJECT_ID="$FIREBASE_PROJECT_ID" \
    PLANTERIOR_FIREBASE_APP_ID="$FIREBASE_APP_ID" \
    PLANTERIOR_FIREBASE_API_KEY="$FIREBASE_API_KEY" \
    PLANTERIOR_FIREBASE_STORAGE_BUCKET="$FIREBASE_STORAGE_BUCKET" \
    "${GRADLE[@]}" \
    :app:validateReleaseAuthConfiguration > "$TMP_DIR/environment-precedence.log" 2>&1 ||
    fail "environment configuration did not override local.properties"
assert_redacted "$TMP_DIR/environment-precedence.log"

env "${AUTH_ENV[@]}" \
    PLANTERIOR_GOOGLE_WEB_CLIENT_ID="invalid-environment-precedence" \
    PLANTERIOR_FIREBASE_PROJECT_ID="INVALID_ENVIRONMENT_PRECEDENCE" \
    PLANTERIOR_FIREBASE_APP_ID="invalid-environment-precedence" \
    PLANTERIOR_FIREBASE_API_KEY="invalid-environment-precedence" \
    PLANTERIOR_FIREBASE_STORAGE_BUCKET="invalid/environment/precedence" \
    "${GRADLE[@]}" \
    -Pplanterior.release.googleWebClientId="$GOOGLE_CLIENT_ID" \
    -Pplanterior.release.firebaseProjectId="$FIREBASE_PROJECT_ID" \
    -Pplanterior.release.firebaseAppId="$FIREBASE_APP_ID" \
    -Pplanterior.release.firebaseApiKey="$FIREBASE_API_KEY" \
    -Pplanterior.release.firebaseStorageBucket="$FIREBASE_STORAGE_BUCKET" \
    :app:validateReleaseAuthConfiguration > "$TMP_DIR/gradle-precedence.log" 2>&1 ||
    fail "Gradle properties did not override environment and local.properties"
assert_redacted "$TMP_DIR/gradle-precedence.log"
printf 'release auth source precedence passed: Gradle properties > environment > local.properties\n'

assert_malformed_environment_value() {
    local name="$1"
    local value="$2"
    local expected_error="${3:-Release authentication configuration is malformed}"
    local log_file="$TMP_DIR/$name.log"
    if env "${AUTH_ENV[@]}" \
        PLANTERIOR_GOOGLE_WEB_CLIENT_ID="$value" \
        PLANTERIOR_FIREBASE_PROJECT_ID="$FIREBASE_PROJECT_ID" \
        PLANTERIOR_FIREBASE_APP_ID="$FIREBASE_APP_ID" \
        PLANTERIOR_FIREBASE_API_KEY="$FIREBASE_API_KEY" \
        PLANTERIOR_FIREBASE_STORAGE_BUCKET="$FIREBASE_STORAGE_BUCKET" \
        "${GRADLE[@]}" \
        -Pplanterior.release.configFile="$EMPTY_CONFIG" \
        :app:validateReleaseAuthConfiguration > "$log_file" 2>&1; then
        fail "$name malformed authentication value was accepted"
    fi
    grep -Fq "$expected_error" "$log_file" ||
        fail "$name malformed authentication error is not explicit"
    assert_redacted "$log_file"
    printf 'rejected escaped malformed auth fixture safely: %s\n' "$name"
}

assert_malformed_environment_value quote-escaping '123456789012-quote-escape-marker".apps.googleusercontent.com'
assert_malformed_environment_value backslash-escaping '123456789012-backslash-escape-marker\.apps.googleusercontent.com'
assert_malformed_environment_value \
    whitespace-only \
    '   ' \
    'Release authentication configuration is incomplete'
assert_malformed_environment_value newline-escaping $'123456789012-newline-escape-marker\n.apps.googleusercontent.com'

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

keytool -genkeypair \
    -storetype JKS \
    -keystore "$SEPARATE_KEYSTORE" \
    -storepass "$SEPARATE_STORE_PASSWORD" \
    -keypass "$SEPARATE_KEY_PASSWORD" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 2 \
    -dname "CN=Planterior Separate Password Verification,O=Planterior,C=KR" \
    -noprompt > /dev/null 2>&1

keytool -exportcert \
    -keystore "$KEYSTORE" \
    -storepass "$STORE_PASSWORD" \
    -alias "$KEY_ALIAS" \
    -file "$TMP_DIR/verification.cer" > /dev/null 2>&1
keytool -importcert \
    -storetype JKS \
    -keystore "$CERTIFICATE_ONLY_KEYSTORE" \
    -storepass "$STORE_PASSWORD" \
    -alias "$CERTIFICATE_ALIAS" \
    -file "$TMP_DIR/verification.cer" \
    -noprompt > /dev/null 2>&1
printf 'not a keystore\000\377\n' > "$CORRUPT_KEYSTORE"

write_signing_config() {
    local output="$1"
    local store_file="$2"
    local store_password="$3"
    local key_alias="$4"
    local key_password="$5"
    cat > "$output" <<PROPERTIES
planterior.release.googleWebClientId=$GOOGLE_CLIENT_ID
planterior.release.firebaseProjectId=$FIREBASE_PROJECT_ID
planterior.release.firebaseAppId=$FIREBASE_APP_ID
planterior.release.firebaseApiKey=$FIREBASE_API_KEY
planterior.release.firebaseStorageBucket=$FIREBASE_STORAGE_BUCKET
planterior.release.storeFile=$store_file
planterior.release.storePassword=$store_password
planterior.release.keyAlias=$key_alias
planterior.release.keyPassword=$key_password
PROPERTIES
}

assert_signing_rejected() {
    local name="$1"
    local store_file="$2"
    local store_password="$3"
    local key_alias="$4"
    local key_password="$5"
    local config="$TMP_DIR/$name.properties"
    local log_file="$TMP_DIR/$name.log"
    write_signing_config "$config" "$store_file" "$store_password" "$key_alias" "$key_password"
    if run_isolated \
        -Pplanterior.release.configFile="$config" \
        :app:validateReleaseConfiguration > "$log_file" 2>&1; then
        fail "$name signing configuration was accepted"
    fi
    grep -Fq "Release signing credentials could not be verified" "$log_file" ||
        fail "$name signing error is not explicit"
    assert_redacted "$log_file"
    if grep -Eq ':app:(compile|lintVital|minify|package)Release' "$log_file"; then
        fail "$name continued into release compilation or packaging"
    fi
    printf 'rejected signing fixture early: %s\n' "$name"
}

assert_signing_rejected \
    wrong-store-password \
    "$KEYSTORE" \
    "$WRONG_SECRET" \
    "$KEY_ALIAS" \
    "$KEY_PASSWORD"
assert_signing_rejected \
    wrong-key-password \
    "$SEPARATE_KEYSTORE" \
    "$SEPARATE_STORE_PASSWORD" \
    "$KEY_ALIAS" \
    "$WRONG_SECRET"
assert_signing_rejected \
    missing-alias \
    "$KEYSTORE" \
    "$STORE_PASSWORD" \
    "$MISSING_ALIAS" \
    "$KEY_PASSWORD"
assert_signing_rejected \
    certificate-only-alias \
    "$CERTIFICATE_ONLY_KEYSTORE" \
    "$STORE_PASSWORD" \
    "$CERTIFICATE_ALIAS" \
    "$KEY_PASSWORD"
assert_signing_rejected \
    corrupt-keystore \
    "$CORRUPT_KEYSTORE" \
    "$STORE_PASSWORD" \
    "$KEY_ALIAS" \
    "$KEY_PASSWORD"

write_signing_config \
    "$TMP_DIR/separate-valid.properties" \
    "$SEPARATE_KEYSTORE" \
    "$SEPARATE_STORE_PASSWORD" \
    "$KEY_ALIAS" \
    "$SEPARATE_KEY_PASSWORD"
run_isolated \
    -Pplanterior.release.configFile="$TMP_DIR/separate-valid.properties" \
    :app:validateReleaseConfiguration > "$TMP_DIR/separate-valid.log" 2>&1 ||
    fail "valid separate store/key password configuration was rejected"
assert_redacted "$TMP_DIR/separate-valid.log"
printf 'accepted signing fixture: separate store and key passwords\n'

write_signing_config \
    "$TMP_DIR/stale.properties" \
    "$SEPARATE_KEYSTORE" \
    "$SEPARATE_STORE_PASSWORD" \
    "$KEY_ALIAS" \
    "$SEPARATE_KEY_PASSWORD"
run_cached_isolated \
    -Pplanterior.release.configFile="$TMP_DIR/stale.properties" \
    :app:validateReleaseConfiguration > "$TMP_DIR/cache-valid.log" 2>&1 ||
    fail "configuration-cache valid signing fixture was rejected"
assert_redacted "$TMP_DIR/cache-valid.log"
grep -Eq 'Configuration cache entry stored|Reusing configuration cache' "$TMP_DIR/cache-valid.log" ||
    fail "configuration cache was not exercised"
write_signing_config \
    "$TMP_DIR/stale.properties" \
    "$SEPARATE_KEYSTORE" \
    "$WRONG_SECRET" \
    "$KEY_ALIAS" \
    "$SEPARATE_KEY_PASSWORD"
if run_cached_isolated \
    -Pplanterior.release.configFile="$TMP_DIR/stale.properties" \
    :app:validateReleaseConfiguration > "$TMP_DIR/cache-mutated-store.log" 2>&1; then
    fail "persistent daemon/configuration cache reused stale store credentials"
fi
grep -Fq "Release signing credentials could not be verified" "$TMP_DIR/cache-mutated-store.log" ||
    fail "mutated cached store credential error is not explicit"
assert_redacted "$TMP_DIR/cache-mutated-store.log"
write_signing_config \
    "$TMP_DIR/stale.properties" \
    "$SEPARATE_KEYSTORE" \
    "$SEPARATE_STORE_PASSWORD" \
    "$KEY_ALIAS" \
    "$WRONG_SECRET"
if run_cached_isolated \
    -Pplanterior.release.configFile="$TMP_DIR/stale.properties" \
    :app:validateReleaseConfiguration > "$TMP_DIR/cache-mutated-key.log" 2>&1; then
    fail "persistent daemon/configuration cache reused stale key credentials"
fi
grep -Fq "Release signing credentials could not be verified" "$TMP_DIR/cache-mutated-key.log" ||
    fail "mutated cached key credential error is not explicit"
assert_redacted "$TMP_DIR/cache-mutated-key.log"
printf 'configuration-cache stale credential mutation rejected: store and key passwords\n'

cat > "$VALID_CONFIG" <<PROPERTIES
planterior.release.googleWebClientId=$GOOGLE_CLIENT_ID
planterior.release.firebaseProjectId=$FIREBASE_PROJECT_ID
planterior.release.firebaseAppId=$FIREBASE_APP_ID
planterior.release.firebaseApiKey=$FIREBASE_API_KEY
planterior.release.firebaseStorageBucket=$FIREBASE_STORAGE_BUCKET
planterior.release.storeFile=$KEYSTORE
planterior.release.storePassword=$STORE_PASSWORD
planterior.release.keyAlias=$KEY_ALIAS
planterior.release.keyPassword=$KEY_PASSWORD
PROPERTIES

run_isolated \
    -Pplanterior.release.configFile="$VALID_CONFIG" \
    :app:assembleRelease \
    :app:bundleRelease > "$TMP_DIR/release.log" 2>&1 ||
    fail "valid external verification configuration did not build"
assert_redacted "$TMP_DIR/release.log"

APK="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
AAB="$ROOT_DIR/app/build/outputs/bundle/release/app-release.aab"
APKS="$TMP_DIR/app-release.apks"
UNIVERSAL_APK="$TMP_DIR/universal.apk"
[[ -f "$APK" ]] || fail "release APK was not produced"
[[ -f "$AAB" ]] || fail "release AAB was not produced"
[[ -f "$BUNDLETOOL_JAR" ]] ||
    fail "BUNDLETOOL_JAR must point to the bundletool JAR for APKS verification"

BUNDLETOOL=(java -jar "$BUNDLETOOL_JAR")
if [[ -n "$BUNDLETOOL_CLASSPATH" ]]; then
    IFS=':' read -r -a bundletool_runtime_jars <<< "$BUNDLETOOL_CLASSPATH"
    for runtime_jar in "${bundletool_runtime_jars[@]}"; do
        [[ -f "$runtime_jar" ]] ||
            fail "BUNDLETOOL_CLASSPATH contains a missing runtime JAR"
    done
    BUNDLETOOL=(
        java
        -cp "$BUNDLETOOL_CLASSPATH"
        com.android.tools.build.bundletool.BundleToolMain
    )
fi

APK_ANALYZER="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/cmdline-tools/latest/bin/apkanalyzer"
APK_SIGNER="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/build-tools/37.0.0/apksigner"
AAPT2="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/build-tools/37.0.0/aapt2"
[[ -x "$APK_ANALYZER" ]] || fail "apkanalyzer is unavailable"
[[ -x "$APK_SIGNER" ]] || fail "apksigner is unavailable"
[[ -x "$AAPT2" ]] || fail "aapt2 is unavailable"
command -v jarsigner > /dev/null || fail "jarsigner is unavailable"
command -v openssl > /dev/null || fail "openssl is unavailable"

jarsigner -verify "$AAB" > "$TMP_DIR/aab-signature.log" 2>&1 ||
    fail "ephemeral release AAB is not signed"
assert_redacted "$TMP_DIR/aab-signature.log"

printf '%s' "$STORE_PASSWORD" > "$TMP_DIR/store-password.txt"
printf '%s' "$KEY_PASSWORD" > "$TMP_DIR/key-password.txt"
chmod 600 "$TMP_DIR/store-password.txt" "$TMP_DIR/key-password.txt"
"${BUNDLETOOL[@]}" build-apks \
    --bundle="$AAB" \
    --output="$APKS" \
    --mode=universal \
    --ks="$KEYSTORE" \
    --ks-key-alias="$KEY_ALIAS" \
    --ks-pass="file:$TMP_DIR/store-password.txt" \
    --key-pass="file:$TMP_DIR/key-password.txt" \
    --aapt2="$AAPT2" > "$TMP_DIR/bundletool.log" 2>&1 ||
    fail "bundletool could not produce verification APKS"
assert_redacted "$TMP_DIR/bundletool.log"
unzip -p "$APKS" universal.apk > "$UNIVERSAL_APK" ||
    fail "verification APKS does not contain universal.apk"

"$APK_SIGNER" verify "$APK" > /dev/null || fail "ephemeral release APK is not signed"
"$APK_SIGNER" verify "$UNIVERSAL_APK" > /dev/null ||
    fail "ephemeral universal APK is not signed"

keytool -exportcert \
    -keystore "$KEYSTORE" \
    -storepass "$STORE_PASSWORD" \
    -alias "$KEY_ALIAS" \
    -file "$TMP_DIR/expected-signing.cer" > /dev/null 2>&1
keytool -printcert -rfc -jarfile "$AAB" |
    awk '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/' > "$TMP_DIR/aab-signing.pem"
"$APK_SIGNER" verify --print-certs-pem "$UNIVERSAL_APK" |
    awk '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/' > "$TMP_DIR/apks-signing.pem"
openssl x509 -in "$TMP_DIR/aab-signing.pem" -outform DER > "$TMP_DIR/aab-signing.cer"
openssl x509 -in "$TMP_DIR/apks-signing.pem" -outform DER > "$TMP_DIR/apks-signing.cer"
EXPECTED_CERTIFICATE_SHA256="$(shasum -a 256 "$TMP_DIR/expected-signing.cer" | awk '{print $1}')"
AAB_CERTIFICATE_SHA256="$(shasum -a 256 "$TMP_DIR/aab-signing.cer" | awk '{print $1}')"
APKS_CERTIFICATE_SHA256="$(shasum -a 256 "$TMP_DIR/apks-signing.cer" | awk '{print $1}')"
[[ "$AAB_CERTIFICATE_SHA256" == "$EXPECTED_CERTIFICATE_SHA256" ]] ||
    fail "AAB signing certificate does not match the configured private-key alias"
[[ "$APKS_CERTIFICATE_SHA256" == "$EXPECTED_CERTIFICATE_SHA256" ]] ||
    fail "APKS signing certificate does not match the configured private-key alias"
printf 'verified release signatures: AAB and APKS match configured private-key alias\n'

"${BUNDLETOOL[@]}" dump manifest --bundle="$AAB" > "$TMP_DIR/release-manifest.xml"
grep -Fq 'package="com.planterior.helper"' "$TMP_DIR/release-manifest.xml" ||
    fail "release bundle application ID is incorrect"
grep -Fq 'android:versionCode="1"' "$TMP_DIR/release-manifest.xml" ||
    fail "release bundle version code is incorrect"
grep -Fq 'android:versionName="0.1.0"' "$TMP_DIR/release-manifest.xml" ||
    fail "release bundle version name is incorrect"
grep -Fq 'android:minSdkVersion="29"' "$TMP_DIR/release-manifest.xml" ||
    fail "release bundle minimum SDK is incorrect"
grep -Fq 'android:targetSdkVersion="37"' "$TMP_DIR/release-manifest.xml" ||
    fail "release bundle target SDK is incorrect"
printf 'verified release manifest: application ID, version, min SDK, target SDK\n'
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
for debug_class_name in \
    "com.planterior.helper.feature.auth.DebugAuthHarness" \
    "com.planterior.helper.feature.auth.DebugAuthControls"; do
    if grep -Fq "$debug_class_name" "$TMP_DIR/packages.txt"; then
        fail "$debug_class_name was packaged in release DEX"
    fi
done

unzip -p "$APK" 'classes*.dex' > "$TMP_DIR/release.dex"
for configured_value in \
    "$GOOGLE_CLIENT_ID" \
    "$FIREBASE_PROJECT_ID" \
    "$FIREBASE_APP_ID" \
    "$FIREBASE_API_KEY" \
    "$FIREBASE_STORAGE_BUCKET"; do
    grep -aFq "$configured_value" "$TMP_DIR/release.dex" ||
        fail "external authentication configuration was not packaged"
done

unzip -p "$APK" > "$TMP_DIR/release.contents"
unzip -Z1 "$AAB" |
    while IFS= read -r bundle_entry; do
        case "$bundle_entry" in
            base/*) unzip -p "$AAB" "$bundle_entry" >> "$TMP_DIR/release.contents" ;;
        esac
    done
unzip -p "$UNIVERSAL_APK" >> "$TMP_DIR/release.contents"
if grep -aEq 'DebugAuthHarness|DebugAuthControls|QA 인증 시나리오|qa-google-account-a|KAKAO|Kakao|kakao|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|PLANT_ID_API_KEY|OPENWEATHER_API_KEY|APPLE_PRIVATE_KEY|APPLE_ABUSE_HASH_KEY|MINI_HOME_SHARE_TOKEN_KEY' "$TMP_DIR/release.contents"; then
    fail "release artifacts contain debug auth or server-side provider secret material"
fi
printf 'scanned release APK, AAB, and APKS for debug and server-side provider secret material\n'

printf 'release auth contract passed: missing/malformed auth and invalid signing credentials rejected early; signed APK/AAB/APKS retained Google+Apple production paths\n'
