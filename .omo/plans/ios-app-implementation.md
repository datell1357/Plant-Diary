# Plant Diary iOS Implementation Plan

## Status

- Branch: `feat/ios-app`
- Worktree: `/Users/yeoreum/Documents/Plant Diary/worktree`
- Restored from the approved iOS implementation session, repository ledger,
  PRD, user-flow document, backend contract, and Git history.
- Todos 1-11 are complete through
  `b4d56ab2baab09231adc34c92e96b411f6eaf0ee`.
- Todos 12-21 remain ordered and must be completed sequentially.

## Global execution contract

For every open Todo:

1. Add a deterministic failing test or machine-readable RED QA scenario.
2. Implement only the current Todo.
3. Run SwiftFormat, strict SwiftLint, relevant package tests, XCTest/XCUITest,
   and `ios/scripts/qa-task.sh <N> --attempt-dir "$ATTEMPT_DIR"`.
4. Exercise the real user surface and capture stable evidence.
5. Bind evidence to one frozen Git tree and include runtime cleanup.
6. Commit only that Todo, push to `origin/feat/ios-app`, and verify equality.

Tests must subscribe to exact state changes rather than use fixed sleeps.
Unavailable integrations fail closed and must never be represented as live.
No Android-owned file, secret, certificate, profile, Firebase plist, or API
credential may be committed from this worktree.

## Gate 0 and non-substitution contract

Todos 12-19 may use deterministic protocol fakes and simulators. This proves
implementation behavior only. It does not prove live Firebase, physical-device,
APNs, signing, archive, or TestFlight behavior.

Todo 20 may start only after:

```sh
./ios/scripts/preflight-release-assets.sh \
  --require-device \
  --require-signing \
  --require-firebase \
  --require-asc
```

exits `0`.

Required external inputs:

- `IOS_PHYSICAL_UDID`
- Development and Distribution signing identities and matching profiles
- `IOS_FIREBASE_PLIST_DEV`
- `IOS_FIREBASE_PLIST_PROD`
- Firebase access for both registered iOS apps
- `APP_STORE_CONNECT_API_KEY_PATH`
- `APP_STORE_CONNECT_ISSUER_ID`
- `APP_STORE_CONNECT_KEY_ID`
- `APP_STORE_CONNECT_APP_ID`
- `APPLE_TEAM_ID`
- `APNS_KEY_PATH`
- `APNS_KEY_ID`

The following may not be replaced by simulator, fake, placeholder, unsigned,
or synthetic evidence:

- Physical-device camera and PhotosPicker behavior
- Real Apple and Google sign-in
- Real APNs foreground/background/terminated delivery and action handling
- Signed physical-device installation
- Signed App Store archive/export
- TestFlight upload, installation, and launch

## Completed foundation

### [x] Todo 1 — Branch, worktree, ownership, and Gate 0 bootstrap

Established iOS path ownership, release-asset preflight, QA bootstrap, and the
non-substitutable Gate 0 blocker. Completed by `01708fc`, `bd594d0`, and
`2ad348e`.

### [x] Todo 2 — Xcode project and quality foundation

Created the Swift 6/iOS 17 app, local packages, test targets, XcodeGen project,
CI, formatting, linting, coverage, and verification scripts. Completed by
`f2cc6c5`.

### [x] Todo 3 — Deterministic domain and state contracts

Added opaque IDs, temporal values, closed wire enums, typed entities, errors,
and deterministic clocks. Completed by `0ebf92b` and `f9bd3c9`.

### [x] Todo 4 — Design system and typed navigation

Added Figma-derived tokens, reusable components, independent tab stacks,
camera action, pending-login routes, and safe invalid/deleted fallbacks.
Completed by `796e371` and `0b1b236`.

### [x] Todo 5 — Firebase contract and fail-closed boundary

Pinned and validated the shared backend contract, ownership, idempotency,
revision behavior, server-only paths, and unavailable integrations. Completed
by `98344ea` and `ac0e207`.

### [x] Todo 6 — Social authentication and session boundaries

Implemented onboarding, Apple/Google protocol boundaries, restoration, logout,
route resume, and account isolation. Live account verification remains part of
Todo 20. Completed by `74d952b`.

### [x] Todo 7 — Camera, PhotosPicker, and disclosure

Implemented capture/picker input, normalization, review/replace, denial
fallbacks, and disclosure acknowledgement. Physical-device verification
remains part of Todo 20. Completed by `ec1ac2d`.

### [x] Todo 8 — Account-partitioned cache and sync

Implemented SwiftData account partitions, outbox/listener behavior, retries,
conflicts, stale states, account switching, and logout choices. Completed by
`e807005`.

### [x] Todo 9 — Plant identification and registration

Implemented pending/candidate/empty/failure states, top-three selection,
confirmation, retry/replace/manual registration, duplicate handling, and draft
restoration. Completed by `65b2568`.

### [x] Todo 10 — Personal collection and care detail

Implemented collection states, filtering, detail editing, health notes, safe
content access, deletion confirmation, and list restoration. Completed by
`03602f9`.

### [x] Todo 11 — Watering schedules and completion

Implemented unavailable/upcoming/due/overdue calculation, completion,
same-day idempotency, missing baseline setup, future-date rejection,
per-plant isolation, Gregorian local dates, registration persistence, and
draft preview before save. Completed by `52cf75d`, `07805ab`, and `b4d56ab`.

## Remaining implementation

### [x] Todo 12 — Home dashboard and notification states

#### Scope

- Logged-out, sign-in, and authenticated Home states
- Greeting, committed mini-home preview, identify CTA, and sync freshness
- Today's care ordered deterministically by overdue, due, then upcoming
- Weather-risk priority without turning a section failure into full-screen failure
- Global reminder time and per-plant override
- Notification authorization and endpoint states
- Due-day and one next-day delivery, deduplication, and action handling
- Cold/warm/logged-out/deleted-target routes
- Permission denial must not block Home, collection, schedules, or completion

#### Deliverables

- `HomeDashboardView.swift`
- `HomeDashboardStore.swift`
- `NotificationRuntime.swift`
- `NotificationCoordinator.swift`
- `NotificationRouter.swift`
- Domain, routing, contract, and QA fixture extensions
- Home and notification unit/UI tests

#### Verification

- Empty/content/partial/stale Home tests
- Fixed-clock ordering and independent section cancellation
- Global/per-plant reminder precedence
- Due/next-day/complete-stop and duplicate action tests
- Cold/warm/login-resume/deleted-target route tests
- `qa-task.sh 12` with settled Home screenshot and route trace
- Real APNs remains deferred without substitution to Todo 20

#### Commit

`feat(home): iOS 홈 대시보드와 알림 상태 구현`

### [x] Todo 13 — Weather, regional risk, and alerts

#### Scope

- Explain the weather feature purpose
- Reduced-accuracy When-In-Use location or manual region search
- Manual region takes precedence over location
- Canonical weather snapshots
- Strict-boundary high/low temperature, dry, and overwatered risks
- Multiple simultaneous risks
- Data older than three hours may display but must not alert
- Global off overrides per-plant on
- One alert per safe-to-risk episode

#### Deliverables

- `WeatherRuntime.swift`
- `WeatherRepository.swift`
- `RegionSettingsView.swift`
- `WeatherRiskView.swift`
- Weather/risk domain, settings, notification, sync, and contract extensions
- Weather, location, risk, and UI tests

#### Verification

- Full/reduced/denied/revoked location
- Manual precedence, timeout, stale, equality-safe boundaries, multiple risks
- Revocation produces zero further location calls
- Stale weather produces zero alerts
- `qa-task.sh 13` with risk screenshot and location trace
- Physical location and live APNs repeat in Todo 20

#### Commit

`feat(weather): iOS 날씨 기반 식물 위험 안내 구현`

### [x] Todo 14 — Mini-home committed room state

#### Scope

- Figma isometric room rendered with SwiftUI Canvas
- Room naming and draft versus committed revision
- Normalized geometry, drag, clamp, and deterministic z-order
- Explicit save
- Revision conflict and Save/Discard/Cancel handling
- Only committed revisions appear on Home or sharing surfaces

#### Deliverables

- `MiniHomeView.swift`
- `MiniHomeEditorView.swift`
- `MiniHomeStore.swift`
- `MiniHomeGeometry.swift`
- Domain, sync, route, geometry, state, snapshot, and UI tests

#### Verification

- Clamp and z-order tests
- Save/relaunch and unsaved-dialog tests
- Failed save preserves committed room
- Two-device revision conflict and explicit reapply
- `qa-task.sh 14` with room screenshot, geometry JSON, and conflict trace

#### Commit

`feat(minihome): iOS 미니홈피 공간과 저장 상태 구현`

### [x] Todo 15 — Inventory, plant/item placement, and warehouse

#### Scope

- Plant miniatures and public background/furniture/decoration catalog
- Ownership and acquisition conditions
- Warehouse apply/remove
- Atomic placement
- Limits: one background, ten furniture, ten decoration items
- Removal preserves ownership
- No payments, currency, or client-created ownership

#### Deliverables

- `PlantMiniaturePicker.swift`
- `InventoryView.swift`
- `ShopView.swift`
- `InventoryRepository.swift`
- `ItemPlacementCoordinator.swift`
- Inventory, item, placement, sync, contract, and emulator tests

#### Verification

- Public filtering and met/unmet acquisition conditions
- Duplicate acquisition and unowned-apply denial
- Plant/item target exclusivity and placement limits
- Removal ownership preservation and partial failure
- `qa-task.sh 15` for acquire → warehouse → apply → remove

#### Commit

`feat(shop): iOS 아이템 창고와 미니홈피 배치 구현`

### [x] Todo 16 — XP and customization milestones

#### Scope

- Server-authoritative XP and milestones for approved actions
- Registration, watering, mini-home save, and sharing events
- Thresholds, unlock conditions, idempotent receipts
- Current/earned/claimed states
- Account-scoped cross-device reconciliation
- No client-only XP, duplicate awards, currency, payments, ads, or unpublished rewards

#### Deliverables

- `ProgressionEntities.swift`
- `MilestoneRepository.swift`
- `ProgressionCoordinator.swift`
- `MilestoneProgressView.swift`
- Inventory-condition and backend-manifest extensions
- Progression, receipt, reconciliation, and UI tests

#### Verification

- Threshold boundaries and multiple thresholds per operation
- Duplicate and out-of-order events
- Conflict reconciliation, unpublished rewards, foreign-owner denial
- Already-claimed and offline queued projection
- `qa-task.sh 16` with before/after progress and duplicate counts

#### Commit

`feat(rewards): iOS XP와 꾸미기 마일스톤 구현`

### [x] Todo 17 — Sharing consistency for images and links

#### Scope

- Render only the latest committed room to a fixed-size image
- Share through `UIActivityViewController`
- Create immutable, private-field-stripped links through the shared callable
- Unguessable token, 30-day expiry, and revoke state
- Drafts and later edits must not mutate existing shared snapshots
- Share cancellation is not failure

#### Deliverables

- `MiniHomeShareView.swift`
- `MiniHomeShareRenderer.swift`
- `ShareRepository.swift`
- `ShareSheet.swift`
- Share domain, routes, contract, privacy filters, rendering, and UI tests

#### Verification

- Committed-only source revision and deterministic dimensions
- Offline image and online-only link behavior
- Token uniqueness, expiry, revoke, immutable snapshot, private-field exclusion
- `qa-task.sh 17` with image digest and redaction report
- Physical share-sheet behavior repeats in Todo 20

#### Commit

`feat(share): iOS 미니홈피 이미지와 링크 공유 구현`

### [x] Todo 18 — Settings, policy, consent, and account deletion

#### Scope

- Camera, notification, and location status
- Alert toggles, region, last sync, disclosure acknowledgement, privacy policy
- Server-calculated deletion scope
- Recent reauthentication and explicit final confirmation
- Received/Processing/Completed/Failed/PartiallyFailed/Cancelled states
- Seven-day grace period and cancellation
- Auth, Keychain, SwiftData, and private-route cleanup only after completion

#### Deliverables

- `SettingsView.swift`
- `PrivacyPolicyView.swift`
- `AccountDeletionView.swift`
- `AccountDeletionCoordinator.swift`
- `ConsentRepository.swift`
- Consent, deletion, auth cleanup, contract, emulator, and UI tests

#### Verification

- Cancellation creates zero requests
- Duplicate submit is idempotent
- Reauthentication, grace recovery, seven-day execution
- Partial failure never appears completed
- Completion cleanup and foreign-owner denial
- `qa-task.sh 18` with deletion-state and cleanup receipts

#### Commit

`feat(settings): iOS 개인정보와 계정 삭제 흐름 구현`

### [x] Todo 19 — Observability, accessibility, privacy, and security

#### Scope

- Typed allowlisted analytics
- Sensitive log redaction
- 24-hour original-photo cleanup with representative-photo preservation
- Firebase App Check
- Privacy manifest and required-reason APIs
- Dependency, license, and secret scans
- VoiceOver, Dynamic Type, Reduce Motion, Switch Control, and contrast coverage
- Analytics must never contain raw images, exact coordinates, notes, auth data,
  push/share tokens, private URLs, or unrestricted payloads

#### Deliverables

- `AnalyticsEvent.swift`
- `AnalyticsRecorder.swift`
- `SensitiveDataRedactor.swift`
- `PhotoRetentionCoordinator.swift`
- `PrivacyInfo.xcprivacy`
- App Check, CI, verification, accessibility, privacy, and security tests

#### Verification

- Event allowlist and forbidden-field scans
- 23:59 retained / 24:00 deleted boundary
- Cleanup retry and App Check rejection
- Strict accessibility audit and AX5/VoiceOver journey
- Privacy, gitleaks, dependency, and license reports
- `qa-task.sh 19` with event export and zero forbidden matches

#### Commit

`feat(privacy): iOS 관측성·개인정보 보호와 접근성 강화`

## Release gates

### [ ] Todo 20 — Gate 0 physical-device and release verification

#### Entry gate

The full preflight command must exit `0`. Missing assets create a blocked
checkpoint, never a partial pass.

#### Scope

- Re-diff the pinned Android contract against the final shared backend
- Require every release integration used by the app to be available
- Verify enforcement for 24-hour cleanup, three-hour weather staleness,
  30-day links, and seven-day deletion grace
- Install a signed build on the registered physical iPhone
- Exercise real Apple/Google auth, camera, PhotosPicker, location grant/revoke,
  share sheet, Firebase dev/prod configuration, and APNs foreground/background/
  terminated delivery and action
- Run happy and failure journeys for offline, 429/500, stale, deleted target,
  conflict, unsaved room, expired share, and partial deletion
- Produce a signed Release archive/export with correct entitlements,
  provisioning, and privacy manifest

#### Verification

```sh
./ios/scripts/qa-task.sh 20 \
  --attempt-dir "$ATTEMPT_DIR" \
  --device "$IOS_PHYSICAL_UDID"
```

Evidence must include physical UDID, signing identity, real auth observations,
APNs delivery/action IDs, camera/location/share outcomes, archive/export
receipts, entitlements, provisioning, backend availability, and secret scan.

#### Commit

`test(release): iOS Gate 0 실기기 검증 완료`

### [ ] Todo 21 — TestFlight and final release checklist

#### Entry gate

Todo 20 must be complete with no substituted evidence.

#### Scope

- Validate archive/export and upload through App Store Connect credentials
- Confirm TestFlight processing and internal availability
- Install the TestFlight build on the registered iPhone
- Relaunch and repeat real Apple/Google login, APNs, camera, PhotosPicker,
  location, sharing, sync, offline/reconnect, and account-deletion checks
- Verify launch time, crash-free startup, review metadata, privacy labels,
  support/privacy URLs, beta notes, and internal tester checklist
- Record final repository, backend contract, version/build, archive, upload,
  install, and release evidence

#### Verification

- App Store Connect upload receipt
- Processed build and internal-testing availability
- TestFlight install and launch receipt
- Physical-device smoke-test manifest
- Final full test/quality/security rerun
- No placeholder, unsigned, simulator, or fake evidence

#### Commit

`test(release): iOS TestFlight 출시 체크리스트 완료`

## Final verification

After Todo 21:

1. Re-run the complete iOS verification suite.
2. Re-run backend contract parity against the final shared backend.
3. Run final code, security, and hands-on physical-device reviews.
4. Confirm every Todo has canonical, non-empty, tree-bound evidence.
5. Confirm `HEAD == origin/feat/ios-app`.
6. Confirm no Android-owned or secret file is tracked.
7. Mark the plan complete only when Todo 20 and Todo 21 real-world gates pass.
