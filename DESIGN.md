# Planterior Android Design System

이 문서는 Android 앱의 현재 Compose 구현을 설명하는 디자인 계약이다. 새 화면은 이 문서를 기준으로 만들고, 구현과 문서가 다르면 같은 작업에서 둘을 함께 수정한다.

## 1. 제품 인상

- 초보 식집사가 부담 없이 이해할 수 있는 차분하고 친근한 생활 도구다.
- 따뜻한 아이보리 배경과 식물을 연상시키는 짙은 녹색을 중심으로 사용한다.
- 정보는 전문 용어보다 짧은 행동 문장으로 전달한다.
- 장식보다 식물 사진, 오늘 할 일, 위험 안내와 다음 행동을 먼저 보여 준다.
- Figma `Page 1`이 있는 화면은 그 값을 따른다. Figma가 없는 화면은 아래 토큰과 공용 컴포넌트만 조합한다.

## 2. 색상

색상 리터럴은 화면 코드에 추가하지 않는다. `MaterialTheme.colorScheme` 또는 `PlanteriorTheme`의 확장 토큰을 사용한다.

| 용도 | 토큰 | 값 |
|---|---|---|
| 앱 배경 | `background` | `#FCFBF7` |
| 카드와 하단 바 | `surface` | `#FFFFFF` |
| 연한 녹색 표면 | `primaryContainer` | `#EEF3F0` |
| 주요 행동과 활성 상태 | `primary` | `#3D6642` |
| primary 위 전경 | `onPrimary` | `#FFFFFF` |
| 제목과 본문 | `onBackground`, `onSurface` | `#1F2937` |
| 보조 설명 | `onSurfaceVariant` | `#6B7280` |
| 비활성 탭 | `PlanteriorTheme.tertiaryText` | `#9CA3AF` |
| 구분선과 외곽선 | `outline` | `#E5E7EB` |
| 경고 강조 | `error` | `#D97706` |
| 경고 배경 | `errorContainer` | `#FEF3C7` |
| 경고 본문 | `onErrorContainer` | `#92400E` |
| 경고 테두리 | `PlanteriorTheme.warningBorder` | `#FDE68A` |

현재 Figma에는 dark 화면이 없으므로 앱은 임의의 dark 팔레트를 만들지 않고 light color scheme만 사용한다.

## 3. 타이포그래피

Inter의 한글 글리프 대체 결과와 맞도록 `FontFamily.SansSerif`를 사용한다.

| Material 3 슬롯 | 용도 | 크기 / 행간 / 굵기 |
|---|---|---|
| `titleLarge` | 화면 제목, 사용자 이름 | 17sp / 21sp / Bold |
| `titleMedium` | 카드·섹션 제목 | 16sp / 19sp / Bold |
| `bodyLarge` | 식물 이름, 강조 본문 | 14sp / 17sp / Bold |
| `bodyMedium` | 설명, 위치, 상태 | 13sp / 16sp / Normal |
| `labelSmall` | 하단 탭 라벨 | 10sp / 12sp / Bold |

- 화면 제목은 최대 두 줄이며 말줄임표를 사용한다.
- 오류와 빈 상태는 원인 코드보다 사용자가 할 수 있는 다음 행동을 설명한다.
- 버튼 라벨은 `다시 시도`, `직접 등록`, `도감에 등록`처럼 결과가 분명한 동사를 쓴다.

## 4. 간격, 모서리와 경계

화면 코드에서 임의의 `dp` 간격을 만들지 않는다. `PlanteriorTheme.spacing`의 4dp 기반 단계만 사용한다.

| 토큰 | 값 | 대표 용도 |
|---|---:|---|
| `extraSmall` | 2dp | 하단 바 아래 여백 |
| `small` | 4dp | 아이콘과 라벨 간격 |
| `medium` | 8dp | 조밀한 내부 여백 |
| `large` | 12dp | 카드 내부 요소 간격 |
| `extraLarge` | 16dp | 화면 좌우·카드 기본 여백 |
| `huge` | 24dp | 섹션 사이 간격 |

| 모서리 토큰 | 값 | 대표 용도 |
|---|---:|---|
| `PlanteriorRadius.Small` | 8dp | 작은 배지와 칩 |
| `PlanteriorRadius.Card` | 12dp | 관리 카드와 미니홈피 카드 |
| `PlanteriorRadius.Medium` | 16dp | 경고 배너와 버튼 |
| `PlanteriorRadius.Large` | 48dp | 큰 화면 컨테이너 |

경계선은 `PlanteriorBorderWidth` 1dp와 `outline` 색을 사용한다.

## 5. 공용 컴포넌트

### `PlanteriorScreenScaffold`

- 앱 배경, 상태 표시줄 inset, 화면 제목과 기본 여백을 책임진다.
- 기본 좌우 여백은 16dp, 세로 여백과 요소 간격은 12dp다.
- 화면 제목에는 heading semantics가 적용된다.
- 하단 탭이 필요한 최상위 화면만 `bottomBar`를 제공한다.

### `PlanteriorCard`

- 흰색 표면, 12dp 모서리와 16dp 기본 내부 여백을 사용한다.
- 이미지가 카드 가장자리까지 닿는 경우에만 내부 여백을 0으로 지정한다.
- 전체 카드가 눌리는 경우 `onClick`을 제공하며 button role을 유지한다.
- 같은 카드 표면을 화면마다 다시 구현하지 않는다.

### `PlanteriorBottomBar`

- 홈, 도감, 가운데 카메라 행동, 창고, 설정 순서를 유지한다.
- 바 높이는 62dp이며 system navigation bar inset을 포함한다.
- 일반 탭 아이콘은 24dp, 라벨은 `labelSmall`을 쓴다.
- 카메라 행동은 52dp 원형 primary 표면, 26dp 아이콘, 6dp 돌출과 elevation을 사용한다.
- 모든 탭은 최소 48dp 터치 영역, 선택 상태 semantics와 고유한 content description을 가진다.
- 선택된 탭만 primary, 나머지는 tertiary text 색을 사용한다.

### `CollectionStateBody`

- 도감의 empty/error/denied 상태에서 화면 제목과 하단 바는 고정하고 상태 본문 하나만 세로 스크롤을 소유한다.
- compact height와 큰 글꼴에서도 마지막 CTA까지 스크롤할 수 있어야 한다.
- StyleGallery `scroll-body-shell` 패턴을 따른다: https://github.com/changeroa/StyleGallery/blob/main/patterns/viewport-shell/scroll-body-shell.md

### `PlantThumbnail`

- 목록 이미지는 화면에서 Firebase를 직접 호출하지 않고 주입 가능한 loader를 거친다.
- loader는 원본 bytes 상한, 축소 decode, 메모리 cache를 책임진다.
- loading, loaded, failed, no-photo 상태를 서로 다른 semantics로 노출하며 실패는 no-photo와 구분되는 대체 이미지로 마무리한다.

### Callable and Apple session security

- 모든 exported Firebase `onCall`은 인증 여부와 관계없이 `enforceAppCheck: true`를 사용한다. Android debug는 Firebase Debug App Check provider, release는 Play Integrity provider를 설치하며 emulator smoke는 Functions emulator의 서명 검증 생략 기능에만 통하는 명시적 unsigned debug token을 전송한다. Release callable에는 우회 경로가 없다.
- Apple bootstrap도 App Check를 요구한다. callable context의 attested app ID와 플랫폼이 정규화한 remote IP를 서버 secret HMAC으로 결합해 원본 IP를 저장하지 않는 rate key를 만들고, 10분 window당 10회 생성 제한과 session create를 한 Firestore transaction에서 처리한다. NAT 주소나 App Check token 값, OAuth code는 오류나 client-readable 문서에 노출하지 않는다.
- Apple session은 server-owned `createdAt`/`expiresAt`, PKCE challenge, hashed state/nonce, one-time `usedAt`을 가진다. Callback code attachment와 completion consumption은 각각 expiry/replay를 transaction에서 재검증한다. TTL field override와 `expiresAt/__name__` index를 사용한 200개 bounded scheduled cleanup이 session/rate 문서를 정리하며 Rules는 두 collection의 모든 client read/write를 거부한다.
- authorize의 `response_type=code id_token`, `response_mode=form_post`, `scope=name email` 계약에 맞춰 callback은 success의 `state`/`code`와 optional `id_token`/first-login `user`, 또는 error의 `state`/`error`/optional description만 받는다. Provider metadata는 bounded schema로 파싱하지만 신뢰하거나 저장하지 않는다. Callback `id_token` claims와 `user` profile은 무시하고, PKCE code exchange 뒤 Apple JWKS로 검증한 issuer/audience/expiry/nonce의 token만 인증 결과로 사용한다.
- `appleOAuthCallback`만 callable이 아닌 Apple server OAuth redirect이므로 App Check 예외다. Strict random state lookup, TTL, one-time transactional attachment와 deny-all Rules가 이 HTTP endpoint의 경계다.

### Repository and route lifecycle

- Room과 Firebase-backed collection, detail, registration, watering, weather 저장소는 `PlanteriorApplication`이 소유한다. 저장소 그래프에는 application context만 들어가며 Activity, launcher, window를 보존하지 않는다. Compose main coroutine에서 호출되는 cache/outbox DAO 경계는 모두 Room generated suspend query/transaction을 사용한다. Collection detail의 초기 cache read와 watering server-success reconciliation도 blocking DAO를 호출하지 않으며 취소는 그대로 전파되고 plant/schedule/outbox commit은 한 transaction으로 유지된다.
- Activity 재생성은 인증 provider와 위치 gateway 같은 Activity-bound adapter만 다시 만들고 기존 application 저장소를 재사용한다. 따라서 복원된 NavController entry의 retained ViewModel은 닫힌 Room 참조를 갖지 않는다.
- 로그아웃과 계정 전환의 UID별 cache partition, outbox, notification 취소 순서는 기존 `AuthCoordinator` 경계를 그대로 따르며 route stack 제거가 ViewModel을 clear한다. Activity `onDestroy`는 Activity-owned scope/gateway만 닫는다.
- retained `RegistrationController`는 NavController나 Activity callback을 저장하지 않는다. 기존 식물 열기와 등록 완료는 owner, plant ID, stable event identity를 가진 pending navigation event로 저장되며 현재 STARTED route composition만 generation token으로 전달할 수 있다. 인증 ownership은 nullable UID가 아니라 `Restoring`, `Unknown`, `SignedOut`, `Authenticated(uid)`로 전달한다. `Restoring`/`Unknown`은 process-restored event를 그대로 보류하고, authoritative `SignedOut`은 취소하며, `Authenticated`는 같은 UID만 전달하고 다른 UID event는 격리 폐기한다. 전달 callback이 반환된 뒤 같은 identity를 지우므로 process 복원과 로그인 복귀는 미전달 event를 한 번 재개하고 stale collector/auth emission, 재전달, 다른 owner, 로그아웃/ViewModel clear는 navigation 없이 처리한다.
- 최종 process/test teardown은 application runtime store의 단일 shutdown 경계에서 Room을 정확히 한 번 닫는다. 명시적 shutdown 뒤 접근하면 새 generation을 만들 수 있어 계측 프로세스 안에서도 테스트 간 leak 없이 격리된다.

### `WateringNotificationSettingsScreen`

- 기존 `PlanteriorScreenScaffold`와 `PlanteriorCard`를 조합해 전역 기본 시간, OS 권한 상태, 식물별 활성화·시간 override를 한 세로 스크롤에 표시한다.
- 시간 선택은 48dp 이상의 명시적 버튼으로 열고, 식물별 override가 없으면 `기본 시간 사용`을 표시한다.
- Android 13+ 알림 권한은 최초 미요청 상태에서 `알림 허용`만, 요청 후 거부 상태에서 `기기 설정`만 보여 준다. 경고 카드는 일정 조회·물 주기 완료 컨트롤을 비활성화하지 않는다.
- 저장 중에는 전역·식물별 draft 전체를 읽기 전용으로 잠그고, 비활성화된 저장 버튼에 progress와 `저장 중, 편집 잠김` 상태를 함께 노출한다. 전송 snapshot과 화면 값이 달라지지 않게 하는 의도적 full-draft lock이며, 실패하면 서버 확정본과 exact draft를 함께 보존해 같은 값으로 재시도한다.
- 설정 저장은 서버 revision을 비교하고 응답의 authoritative committed revision을 채택한다. 다른 기기나 timezone/profile 트랜잭션이 먼저 끝나 `ABORTED` 또는 `FAILED_PRECONDITION`이 발생하면 stale draft를 재전송하지 않고 최신 설정을 다시 읽어 충돌 안내를 표시한다.
- 편집 draft와 저장 실패 draft는 `SavedStateHandle`에 보존한다. route 재생성은 retained state를 다시 load로 덮지 않으며, 명시적 새로고침이나 충돌 reconciliation만 서버 확정본으로 교체한다.
- 계정당 개인 식물은 서버 트랜잭션에서 최대 200개로 제한한다. 따라서 전체 식물 알림 설정 저장은 유효한 계정에서 항상 500-write Firestore 경계 안에 머문다.

### Watering notification lifecycle

- 알림 탭은 완료 화면이 아니라 식물 상세로 이동한다. 상세에서 식물명, 최근 물 준 날, 다음 예정일과 물 주기 행동을 함께 제공하며 삭제된 식물은 도감으로 바로 이동할 수 있다.
- 서버는 endpoint token을 노출하지 않는 owner-scoped `notificationHistory`에 append-only `SENT`/`FAILED` 결과와 확인 시각을 기록한다. 인증된 탭 확인은 전용 App Check callable만 `destinationOpened`와 `openedAt`을 한 번 추가한다.
- installation 소유권은 서버에 hash로 저장한 secret과 정확히 1씩 증가하는 generation으로 증명한다. 미확정 unregister는 같은 generation을 재사용하고, 계정 이전은 이전 소유자의 `UNREGISTERED` 경계에서만 가능하며 성공 시 secret을 회전한다.
- pre-send 검증은 endpoint owner 문서에 10분 send lease를 원자적으로 기록한다. unregister와 token rotation은 활성 lease 동안 재시도되고, 9분 함수 timeout 뒤에는 lease가 만료되어 중단된 실행이 FCM을 재개할 수 없다.
- delivery claim은 `CLAIMED` -> `AUTHORIZED_PRE_SEND` -> `SEND_MAY_HAVE_OCCURRED`로 전이한다. authorization에는 schedule/settings/plant preference revision과 유효 timezone/time을 함께 고정한다. FCM 직전 transaction은 이 version과 활성 상태, endpoint owner/version 및 send lease를 다시 함께 확인하고 하나라도 바뀌면 claim/lease만 회수해 history나 최신 schedule을 건드리지 않는다. terminal history는 항상 확정하되 schedule은 같은 revision이면 고정한 값으로, revision이 바뀌었으면 트랜잭션 안의 최신 설정으로 다음 시각을 다시 계산하며 비활성화·삭제된 schedule은 건드리지 않는다. active schedule query와 독립된 전역 orphan recovery는 `state/expiresAt/__name__` index와 durable cursor로 한 번에 지정된 page만 처리한다. 만료된 pre-send claim은 회수하고 send 경계를 지난 claim과 legacy `SENDING`은 `DELIVERED_AMBIGUOUS`로 종결하며, 계정이 삭제됐거나 claim identity가 잘못됐으면 history 경로를 만들지 않고 폐기한다. 탭 시 history가 아직 없으면 callable이 owner와 delivery ID로 claim 하나만 transaction 안에서 직접 확인한다. live claim은 재시도하고, 만료된 pre-send는 전송 이력을 만들지 않으며, 만료된 post-send/legacy claim은 전역 recovery page 순서와 무관하게 ambiguous history 생성과 OPENED 확인을 같은 transaction에서 끝낸다. 초기 not-found는 30분 동안 계정별 terminal absence로 저장하지 않는다.
- 계정 timezone/profile과 notification settings 및 모든 watering candidate는 전용 trusted callable의 한 Firestore transaction에서 함께 변경한다. 사용자 클라이언트는 account root를 직접 수정할 수 없다.
- 로그아웃과 명시적 계정 전환은 endpoint revocation 성공 뒤 기존 owner의 시스템 알림을 모두 취소한 다음 Firebase auth를 제거한다.

### `WeatherScreen`

- 홈의 날씨 위험 배너와 설정의 `날씨 지역 및 주의 알림`에서 같은 화면으로 진입한다. 알림 딥링크는 `Home -> Weather -> WeatherRisk(plantId)` 백스택을 만들며, 삭제되었거나 위험이 끝난 대상은 안전한 안내 상태를 표시한다.
- 현재 지역, 기온, 습도, 강수량, 관측 시각을 첫 카드에 표시한다. 관측 후 3시간까지는 최신이며 그 이후에는 마지막 관측 시각과 `최신 정보가 아니에요`를 함께 표시하고 새 위험 푸시를 만들지 않는다.
- 현재 위치를 선택하기 전 위치 사용 목적과 약 1km 단위 저장 범위를 설명한다. 거부 상태에는 직접 지역 검색을, 영구 거부 상태에는 기기 설정과 직접 검색을 함께 제공한다. 직접 선택한 지역은 늦게 도착한 위치 결과보다 항상 우선한다. Retained Controller는 Activity launcher/location client를 직접 보존하지 않고 generation이 있는 rebindable gateway만 가진다. 각 WeatherRoute composition은 현재 Activity gateway를 attach하고 dispose 시 identity-matched detach/cancel하며, 권한·위치 suspend 작업은 generation 변경 시 새 gateway에서 이어서 실행한다. 따라서 회전 중 permission callback은 새 Activity에서 정확히 한 번 완료되고 늦은 이전 callback/location은 무시되며 account switch/ViewModel clear는 작업 자체를 취소한다. Controller는 route resume의 OS capability event를 Loading 상태에서도 먼저 보존하되 Firebase account identity collection과 별도 reconciliation queue에서 처리한다. UID가 같으면 capability event나 account 재방출이 owner load와 permission/location action을 재시작하거나 취소하지 않으며, UID가 실제로 바뀔 때만 두 cancellation domain을 함께 정리한다. Gateway generation 변경은 pending permission/location operation만 새 Activity로 이동하고 action 자체는 유지한다. 따라서 account가 늦게 도착하거나 capability가 load 중 바뀌어도 최신 두 값이 준비되는 즉시 UID별 reconciliation을 실행하면서 동일 UID action의 consent와 좌표 refresh는 정확히 한 번 완료한다. SharedPreferences에는 UID별 OS permission capability와 app consent desired/acknowledged intent, pending command, monotonic command generation을 서로 구분해 원자적으로 저장한다. OS denial은 app consent를 false로 강제할 수 있지만 이후 OS grant/resume은 app consent를 다시 true로 바꾸지 않는다. 서버 grant는 사용자가 현재 위치 사용을 명시적으로 실행할 때만 생성되며, 명시적 app revoke는 OS permission이 계속 granted여도 recreation/restart를 넘어 유지된다. 기존 결합 capability 레코드는 OS 상태를 추정하지 않고 기존 app intent와 command 상태를 보존해 마이그레이션한다. 모든 grant/revoke는 UID별 mutex에서 직렬화되고 외부 commit 전후에 최신 desired/generation을 다시 확인한다. callable의 Firestore transaction은 최초 canonical generation 1 또는 현재 generation의 정확한 +1만 허용하고, 동일 generation과 동일 granted payload의 재전송만 idempotent 성공으로 처리한다. 임의 forward jump, MAX_SAFE_INTEGER 선점, altered replay, stale generation은 authoritative generation/granted를 포함한 typed conflict로 거부한다. explicit action과 lifecycle/OS permission 자동 reconciliation은 같은 bounded consent convergence loop를 사용해 conflict의 authoritative generation/granted를 UID별 capability에 원자적으로 채택하되 최신 local desired intent는 바꾸지 않고 exact-next command를 할당해 즉시 재시도한다. authoritative 값이 이미 desired와 같으면 추가 mutation 없이 수렴하며, 연속 conflict가 bounded attempt를 넘으면 retryable conflict UI를 표시한다. response loss에는 같은 exact-next payload를 유지하고 recreation/resume에서 재전송하며, UID switch는 이전 owner loop를 취소해 stale callback이 새 owner 상태를 바꾸지 못한다. 이미 MAX_SAFE_INTEGER이거나 숫자 형식이 비정상인 legacy 문서는 owner-authenticated App Check revoke-only recovery transaction만 generation 1/denied 상태로 재정규화하며 `legacyRecovery` marker로 응답 유실 재시도를 idempotent하게 처리한다. 이 recovery는 canonical 상태나 grant takeover에는 사용할 수 없다. 응답 유실과 실패는 pending generation을 유지해 다음 resume/프로세스 재시작에서 같은 command로 idempotent 재시도하고, acknowledged는 서버 authoritative generation/value가 일치한 뒤에만 전환한다. 반복 resume, 재허용, 계정 전환은 pending과 draft를 UID별로 격리한다. 명시적 동의 철회와 OS 권한 철회는 저장된 device region만 삭제하고 manual region, global/per-plant alert 설정은 유지한다. Weather UI는 OS 권한 상태와 app consent 상태를 별도 문구와 접근성 state description으로 표시하며, OS 권한이 있어도 app consent가 꺼져 있으면 철회 대신 명시적 재활성화 동작을 제공한다. 명시적 현재 위치 사용과 device source의 direct/scheduled refresh는 provider 호출 전에 granted consent 문서의 commandGeneration을 읽어 해당 실행에 바인딩한다. provider 성공 뒤 final transaction은 settings revision뿐 아니라 consent 문서를 다시 읽어 같은 generation과 granted=true인지 확인한 후에만 manualRegion 삭제, deviceRegion 저장, snapshot/evaluation/risk/alert 쓰기를 함께 수행한다. provider 실패의 stale 재계산도 같은 consent precondition을 검증한다. revocation은 deviceRegion 유무와 무관하게 consent generation을 전진시키므로 provider가 진행 중인 revoke, 응답 유실, 더 최신 generation 재허용은 stale 실행을 typed aborted consent-changed 결과로 종료하고 manual source와 기존 평가 상태를 보존한다. 따라서 provider 실패나 revision/consent conflict에는 manual source가 유지되고, 정상 commit 응답 유실 뒤 재시도/restart는 이미 저장된 device source로 수렴하며 이후 수동 지역 선택은 다시 manual source를 우선한다. Collection, watering, weather plant deep link는 동일한 path-safe opaque ID 계약(`[A-Za-z0-9_-]`, 1~128자)을 공유하고 그 외 입력은 Home으로 닫힌다.
- 식물별 고온·저온·건조·과습 위험은 동시에 모두 표시하며, 이유와 즉시 실행 가능한 행동을 각각 분리한다. 공개 온·습도 기준이 없는 owned plant ID는 최대 200개 path-safe ID로 snapshot evaluation revision과 같은 transaction에 저장한다. commit 직전 authoritative plant/content 기준이 provider 호출 전 평가 입력과 다르면 전체 평가를 중단하며, Android reload는 현재 owner 식물과 교집합만 복원한다. 따라서 삭제·타 owner ID는 제거되고 프로세스 재생성 뒤에도 안내 불가 개수가 유지되며, 안내 불가 식물이 있으면 전체 식물이 안전하다고 표시하지 않는다.
- 전체 날씨 알림 스위치가 식물별 스위치보다 우선한다. 날씨 설정은 물 주기 설정과 같은 lifecycle-refreshed 시스템 알림 capability를 표시한다. API 33+ 첫 거부 전에는 runtime 권한 CTA, 요청 이력 이후와 API 29~32 앱 알림 비활성 상태에는 앱 알림 설정 CTA를 제공하며, 권한이 없어도 날씨 조회·위험·설정 편집을 유지한다. 공통 capability publisher가 endpoint를 비활성화/재등록하므로 날씨만 별도 token 상태를 만들지 않는다. 저장 중에는 하나의 확정된 설정 스냅샷을 전송하고 계정 변경, 화면 이탈, 위치 동의 철회 시 진행 중인 위치 요청과 이전 계정 refresh를 취소한다.
- 날씨 API 실패는 마지막 성공 snapshot과 위험을 유지한 부분 오류로 표시하며 도감과 물 주기 화면을 차단하지 않는다. 3시간을 넘긴 관측도 기존 위험과 원래 관측시각을 유지하고 stale로 명시하며 신규 transition이나 push를 만들지 않는다.
- 알림 설정은 현재 위험 여부와 무관하게 소유한 모든 식물을 표시한다. revision 충돌은 최신 서버 설정을 다시 읽어 명시하고, UID별 draft를 authoritative plant ID 집합과 정확히 교집합/합집합 처리해 생존 ID의 사용자 값(false 포함)만 보존하고 삭제 ID를 제거하며 신규 ID에는 서버 기본값을 넣는다. 같은 revision의 계정 전환도 이전 UID draft를 재사용하지 않는다. 일반 저장 실패는 사용자의 복원 가능한 draft와 재시도를 유지한다.

### Weather notification lifecycle

- 서버만 OpenWeather 비밀키를 사용하고 기온·습도·강수·관측시각을 canonical snapshot으로 저장한다. 위치 기반 좌표는 소수 둘째 자리로 축소하고, 수동 지역이 있으면 위치 좌표를 조회 기준으로 사용하지 않는다.
- 위험은 적정 범위 밖에서만 활성화한다. 경계값은 안전하며 한 식물에 여러 위험이 동시에 존재할 수 있다. 위험 진입 transition마다 delivery 문서를 한 번만 만들고 지속 중인 위험, stale snapshot, 전체/식물별 비활성 상태에는 만들지 않는다.
- FCM 직전 단일 transaction은 claim lease와 identity, 식물 owner, active risk ID/type/transition/revision, snapshot freshness/revision, 전체·식물 설정 revision/enabled, 현재 PUBLIC 환경 기준 content ID/revision과 위험 재평가, endpoint owner/capability/token/generation을 다시 검증한다. endpoint owner에는 짧은 send lease를 원자적으로 설치해 revoke와 실제 send 경계를 직렬화한다. 대상 삭제·기준 변경·설정 변경·stale 전환 시 해당 delivery만 취소하고, endpoint 전이만 발생하면 PENDING으로 되돌려 안전한 endpoint로 재시도한다. Android도 현재 owner의 식물 목록으로 위험을 다시 걸러 삭제 직후 딥링크에서 즉시 unavailable/도감 이동을 제공한다.
- refresh 성공 응답과 outbox 전송을 분리한다. 직접 callable과 scheduled owner refresh는 모두 provider 완료/실패 시점에 clock을 다시 읽으며 invocation 시각을 provider latency 뒤로 전달하지 않는다. 따라서 timeout 중 2시간 59분에서 3시간 1분으로 넘어가도 retained snapshot의 stale 여부를 원자적으로 다시 계산하고, snapshot 값·기존 위험·transition·alert는 그대로 보존한다. outbox는 `PENDING -> CLAIMED -> SEND_MAY_HAVE_OCCURRED -> SENT|FAILED|SENT_AMBIGUOUS` lease를 사용하며, 만료 CLAIMED만 재대기시키고 send 경계를 지난 claim은 재전송 없이 ambiguous로 종결한다. FCM 직전 authorization은 invocation/claim 시각이 아닌 새 clock 값을 읽어 snapshot 관측/만료, risk 관측 identity, alert 만료를 다시 검증하고 경계에서 만료된 alert를 전송 없이 취소한다. Android는 immutable alert ID별 platform ID의 양방향 충돌 해결 registry를 영속화하고 notification tag와 request code를 alert ID로 분리해 32-bit hash 충돌과 프로세스 재시작에서도 서로 덮어쓰지 않으며, weather tap도 watering과 같은 allowlisted `ACTION_VIEW` typed URI로 MainActivity에 전달한다. PendingIntent는 weather alert ID 또는 watering delivery ID를 namespaced `Intent.identifier`로 전달하고 MainActivity는 최근 소비 identity와 현재 target을 saved state에 보존한다. NavController가 같은 target과 로그인 복귀 URI를 이미 복원했으면 기존 entry를 유지하고, 같은 identity의 warm 재전달은 무시하며, 새 identity만 canonical stack replacement를 수행한 뒤 실제 destination이 일치할 때 소비 처리한다.
- 시간별 configured refresh는 collection-group document path 순서와 server-only durable cursor를 사용해 invocation당 최대 5개의 100-user query page를 읽되, 각 사용자 호출 전 `inFlightPath`를 쓰고 성공·실패·10초 취소 후 즉시 settled cursor를 checkpoint한다. 7분 30초 strict deadline이 540초 Functions 제한 전에 안전하게 중단하고 다음 invocation은 마지막 ambiguous/settled 사용자 다음에서 재개한다. 10분 전역 lease가 동시 실행을 직렬화하고 끝에서만 wrap하므로 반복 provider timeout, 프로세스 중단, 500명 초과에서도 앞 사용자를 재실행하거나 후반 계정을 영구적으로 굶기지 않는다. OpenWeather fetch는 scheduler AbortSignal과 자체 10초 제한을 모두 따른다.
- 날씨 알림은 물 주기와 분리된 채널을 사용한다. payload와 Android 표시 제목은 식물 이름과 지역화된 고온·저온·건조·과습 유형을 명시하고 action 본문을 유지한다. 계정 전환 시 기존 알림을 모두 취소하는 공통 소유권 경계를 그대로 따르며 Android 13+ 알림 권한 거부는 앱 내 날씨 조회를 막지 않는다.

### 아이콘

- `PlanteriorIcons`의 24x24 viewport, 2dp round stroke 아이콘을 우선 사용한다.
- 색상은 벡터 내부에 고정하지 않고 호출부의 tint를 따른다.
- 장식이 아닌 아이콘에는 항상 현지화된 content description을 제공한다.

## 6. 화면 구성과 상태

- 최상위 화면은 `PlanteriorScreenScaffold`와 `PlanteriorBottomBar`를 조합한다.
- 전체 화면 흐름은 `PlanteriorNavHost`의 타입 안전 route를 사용한다.
- route에는 불투명 ID만 전달하고 사진 bytes, 사용자 ID, 메모 같은 개인정보를 넣지 않는다.
- loading, empty, error, denied, stale 상태를 빈 화면으로 두지 않는다.
- 실패 상태에는 재시도와 가능한 대체 경로를 함께 제공한다.
- 저장 시작부터 완료까지 편집 draft를 exact request snapshot으로 고정한다. 저장 실패 편집본도 exact retry를 위해 유지하며, revision conflict와 outbox mismatch는 같은 요청을 반복하지 않고 서버 확정본 reload로 reconciliation한다.
- stale 목록은 마지막 성공 시각과 사용자가 즉시 실행할 수 있는 retry를 함께 표시한다.
- 카메라·위치·알림 권한 거부 시 설정 이동 또는 직접 입력 등 핵심 목적을 달성할 다른 경로를 보여 준다.
- 사진 처리, 위치, 알림과 개인정보 관련 행동은 실행 전에 목적과 보관 범위를 명시한다.
- 스크롤 가능한 긴 화면은 system inset과 하단 행동 영역이 겹치지 않게 한다.

## 7. 접근성과 상호작용

- 모든 상호작용 요소는 최소 48x48dp 터치 영역을 확보한다.
- 아이콘만 있는 버튼은 content description으로 행동을 설명한다.
- 선택 가능한 항목은 selected state와 적절한 role을 노출한다.
- 화면 제목은 heading으로 표시한다.
- 색상만으로 선택, 오류, 경고를 구분하지 않고 텍스트나 아이콘을 함께 사용한다.
- 일반 본문은 WCAG 2.1 기준 4.5:1 이상의 대비를 유지한다.
- 경고 본문은 `onErrorContainer`를 사용한다. `error` 색은 경고 배경 위 본문색으로 사용하지 않는다.
- 비동기 상태 전환은 재구성이나 Activity 재생성 뒤에도 같은 사용자 선택을 복원해야 한다.
- 알림 권한 거부 안내는 `권한이 없어도 앱에서 예정일을 확인하고 완료할 수 있음`을 텍스트로 명시하고, 권한 CTA와 핵심 관리 행동을 독립적으로 유지한다.

## 8. 새 화면을 추가하는 순서

1. 기존 공용 컴포넌트로 화면 골격을 만든다.
2. `MaterialTheme`과 `PlanteriorTheme` 토큰만 사용한다.
3. loading, empty, error, permission-denied와 복원 상태를 함께 설계한다.
4. 행동 요소의 48dp 터치 영역과 semantics를 검사한다.
5. 반복되는 패턴이 두 화면 이상에서 사용될 때만 `core:designsystem`으로 승격한다.
6. 새 토큰이나 공용 패턴이 필요하면 구현보다 먼저 이 문서에 근거와 사용 범위를 추가한다.

## 9. 검증 기준

- 토큰 값: `PlanteriorDesignTokenTest`
- 텍스트 대비: `PlanteriorContrastTest`
- 하단 바 semantics와 순서: `PlanteriorBottomBarTest`
- 전체 프로젝트 형식: `./gradlew spotlessCheck`
- UI 변경은 API 29와 최신 지원 API에서 실제 렌더링, 시스템 inset, 큰 글꼴, 권한 거부와 Activity 재생성을 확인한다.
