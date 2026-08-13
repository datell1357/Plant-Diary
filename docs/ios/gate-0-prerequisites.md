# iOS Gate 0 prerequisites

Implementation remains blocked until the following command exits `0`:

```sh
./ios/scripts/preflight-release-assets.sh \
  --require-device \
  --require-signing \
  --require-firebase \
  --require-asc
```

## Required external assets

| Asset | Required input | Current status |
| --- | --- | --- |
| Physical iPhone | `IOS_PHYSICAL_UDID` for a registered device visible to `xcrun devicectl` | Missing |
| Signing identity | Valid Apple Development and Distribution identities in Keychain | Missing |
| Provisioning | Matching profiles in Xcode's provisioning profile directories | Missing |
| Firebase development app | `IOS_FIREBASE_PLIST_DEV` pointing to a Planterior iOS plist | Missing |
| Firebase production app | `IOS_FIREBASE_PLIST_PROD` pointing to a Planterior iOS plist | Missing |
| App Store Connect API | `APP_STORE_CONNECT_API_KEY_PATH`, issuer ID, key ID, and app ID | Missing |
| Apple team | `APPLE_TEAM_ID` | Missing |
| APNs | `APNS_KEY_PATH` and `APNS_KEY_ID` | Missing |

Both Firebase plists must contain bundle ID `com.planterior.helper`, a project ID,
Google app ID, and reversed client ID. The referenced Firebase project must be
accessible to the active Firebase CLI account and contain the matching registered
iOS app.

## Secure resume procedure

1. Register the iOS bundle ID and app in Apple Developer, App Store Connect, and
   the intended Firebase development and production projects.
2. Connect and trust the physical iPhone so `xcrun devicectl list devices` reports
   its identifier.
3. Install signing identities and provisioning profiles through Xcode.
4. Store Firebase plists and private keys outside Git.
5. Export only paths and non-secret identifiers in the execution environment.
6. Run the preflight command above from the `feat-ios-app` worktree.
7. Resume `$start-work`; do not mark Todo 1 complete unless preflight exits `0`.

Do not commit plist contents, private keys, certificates, profiles, access tokens,
or API credentials. Simulator push, mock authentication, and unsigned archives do
not satisfy Gate 0.
