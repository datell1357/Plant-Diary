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
- `topAction`은 닫기처럼 제목과 같은 줄에 있어야 하는 선택적 보조 행동에만 사용하며, 최소 48dp target을 유지한다.
- `contentHorizontalPadding`은 승인된 외부 참조 계약이 다른 값을 명시한 화면만 사용한다. 기본값은 그대로 16dp이며 임의의 화면별 여백 조정 수단이 아니다.
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

### 창고·상점 chrome

- 창고 화면 제목과 heading semantics는 `나의 창고`, 상점은 `아이템 상점`을 사용한다.
- `창고`/`상점` 섹션 탭은 선택 시 `primary` 채움과 `onPrimary` 라벨을 사용한다. 미선택 탭은 투명 표면, `outline` 경계와 `onSurfaceVariant` 라벨을 사용하며 Material 기본 보라색 chip 색을 사용하지 않는다.
- `전체`/`배경`/`가구`/`장식` 카테고리 필터도 같은 Figma chrome을 사용한다. 선택 시 `primary`(`#3D6642`) 채움·경계와 `onPrimary` 흰색 라벨/아이콘, 미선택 시 투명 표면·`outline` 경계·`onSurfaceVariant` 라벨/아이콘을 사용한다. pressed/focus state layer는 현재 content color를 따르고 disabled 상태도 선택 여부에 맞는 같은 팔레트를 유지해 Material 기본 보라색이 어떤 상태에서도 나타나지 않는다. 각 필터는 단일 선택 semantics와 최소 48dp target을 가지며 200% 글꼴에서도 두 글자 라벨을 자르지 않는다.
- 아이템 상세는 앱 셸이 제공하는 하단 내비게이션을 한 번만 유지하고 `창고`를 선택 상태로 표시한다. 상세의 뒤로 동작이나 선택된 `창고` 탭은 기존 창고·상점 화면과 필터 상태로 돌아간다.

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

### `MiniHomeShareScreen`

- 이 화면은 승인된 iOS `MiniHomeShareView` 참조가 시각 계약이다. 순서는 미리보기 → `저장된 {revision}판` → 한 줄 상태 → 전폭 primary `이미지 공유`·`공유 링크 만들기`·활성 상태에서만 나타나는 `링크 해제`로 고정한다. 닫기 행동은 간결한 `닫기` 라벨과 별도 content description을 가진다.
- Android 확장(비공개 안내, 링크 주소·만료·복사·외부 공유, 렌더·링크 오류와 재시도, 공유 시트 피드백)은 이 참조 위율을 앞지르지 않고 primary 행동 아래에만 놓는다. 미리보기 앞에 큰 비공개·저장본 카드를 두지 않는다.
- 참조가 정한 스크롤 여백 20dp, 세로 리듬 16dp, 미리보기 최소 높이 220dp는 공용 spacing 스케일과 다른 값이므로 `MiniHomeShareLayout` 한 곳에만 정의한다. 미리보기 모서리는 기존 `PlanteriorRadius.Medium`(16dp)을 쓰고 색은 기존 팔레트만 사용한다.
- 렌더가 실패해도 미리보기 자리는 같은 치수의 대체 면으로 유지해 화면 위쪽이 오류 카드로 바뀌지 않는다. 상태는 별도 카드 표면 없이 한 줄로만 나타난다.
- 모든 행동은 최소 48dp 타겟을 유지하고, 200% 글꼴에서도 같은 순서로 끝까지 스크롤된다. 계약 검증은 `MiniHomeShareReferenceParityTest`이다.

### `MiniHomeScreen`

- 일반 화면은 Room에 원자적으로 반영된 마지막 서버 확정 revision만 그린다. 편집 draft와 실패한 exact request는 `SavedStateHandle`과 owner-scoped outbox에 따로 보존하며 홈 preview를 바꾸지 않는다.
- 방은 5x4 isometric grid다. 저장 좌표는 각 칸 중심의 normalized x/y이고 화면 크기와 회전에 독립적이다. floor tile, placement 바닥 anchor, drag inverse/hit mapping, miniature bounds와 z-index는 모두 `MiniHomeIsometricProjection` 하나를 사용한다. 투영은 logical column/row 두 축을 diamond 축으로 변환하고 같은 행렬의 역변환으로 pointer를 grid에 되돌린다. 따라서 density, 화면 폭, 회전, 큰 글꼴에서도 대상의 가로 중심과 아래쪽 기준점은 diamond cell 중심에 정확히 닿아야 한다. 한 칸에는 하나의 대상만 놓을 수 있고 동일 식물 또는 보유 장식은 한 번만 배치한다. z-index는 투영된 바닥 Y depth, 투영된 X, placement ID 순으로 0부터 연속 계산한다.
- 식물 미니어처는 `representativePhotoPath`가 있으면 기존 bounded decode/cache loader의 사진을 둥글게 clip해 쓰고, 사진이 없거나 실패하면 plant ID와 이름에서 안정적으로 선택한 넓은 잎·직립 잎·늘어진 잎 silhouette를 화분과 함께 그린다. picker도 같은 식물 사진/identity를 이름과 함께 표시한다. 보유 장식은 ID에 따른 조명·테이블·러그 silhouette와 장식 이름을 사용해 식물 및 서로 다른 장식과 구분한다. 사진 유무나 색만으로 대상을 식별하지 않고 placement/picker semantics에 종류와 이름을 유지한다.
- drag는 canonical projection inverse로 grid에 clamp하며 선택한 대상에는 왼쪽·오른쪽·위·아래 이동과 제거를 위한 48dp 이상 대체 컨트롤을 함께 제공한다. 선택, 좌표, 대상 종류와 표시 이름은 스크린 리더 문구로 노출한다. miniature의 시각 크기는 room geometry에서 계산하고 글꼴 크기에 의존하지 않으며 touch target은 별도의 최소 48dp 영역을 유지한다.
- 저장은 App Check가 적용된 전용 callable transaction만 사용한다. transaction은 현재 mini-home revision, 식물 소유권, 장식 소유권과 공개 catalog 상태, occupancy와 layering을 함께 검증하고 전체 placement set을 한 revision으로 교체한다. 직접 mini-home/placement write는 Rules에서 거부한다.
- Mini-home 상세 load는 App Check/auth가 적용된 `loadMiniHomeSnapshot` contract v1 callable만 사용한다. layout, acquisition, personal-plant mutation은 owner별 immutable `users/{uid}/miniHomeProjections/{generation}-{sha256}` 문서를 만들고 마지막 write로 `miniHomeProjectionPointers/current`를 교체한다. content-admin-only Rules를 통과한 `shopItems/{itemId}` create/update/delete는 retry가 켜진 `publishCatalogProjectionOnWrite` Firestore trigger를 실행한다. trigger는 event payload나 이전 partial 값을 사용하지 않고 현재 PUBLIC source를 transaction query로 매번 다시 읽어 100개 상한, full media identity와 action condition을 검증하고 canonical order로 재구축한다. 유효한 item만 immutable `catalogProjections/{generation}-{sha256}`에 넣고 malformed PUBLIC row 수를 exact `rejectedCount`로 저장하며 `partial == (rejectedCount > 0)`를 강제한다. repair/delete 뒤 현재 source가 모두 유효하면 partial과 rejected count는 즉시 0으로 복구된다. source catalog, partial, rejected count를 묶은 token이 같으면 event replay·동시·역순 실행은 generation을 늘리지 않고, source 또는 pointer가 transaction 중 바뀌면 Firestore retry 뒤 최신 canonical source만 pointer로 교체한다. owner projection은 exact catalog projection ID와 digest에 묶이고 snapshot load는 현재 catalog pointer와 owner pointer를 최대 6 documents로 읽어 stale binding을 같은 transaction에서 새 owner generation으로 교체한다. 두 pointer가 없는 최초 legacy bootstrap은 source와 owner domains를 함께 읽어 자동 publish하며 placements 20, catalog 100, owned 100, plants 200의 초과 감지 row를 포함해 최대 429 documents다. generation 문서는 수정하지 않으며 missing immutable document, owner·ID·generation 불일치, count/digest/rejected-count 불일치, 잘못된 catalog binding은 모두 `data-loss`로 fail closed한다. Android Mini-home은 layout→plants→inventory 순차 read를 하지 않으며 strict closed-envelope parser가 partial/malformed/cross-owner 응답을 fail closed한다.
- Canonical Firebase Rules gate는 정확히 41개 test를 machine-count하고 count가 달라져도 실패한다. 기존 ownership/server 계약 25개에 Todo 14 catalog/projection 경계 16개를 별도 suite로 더한다: 일반·비인증·잘못된 claim의 catalog CRUD 거부, exact `contentAdmin == true`의 valid CRUD, media identity·visibility·revision·closed-field 검증, catalog/owner projection pointer와 immutable generation의 모든 client-role read/write 거부, public digest-bound Storage 허용, path-only·wrong-digest·private·unsupported-action Storage 거부를 각각 독립 regression으로 고정한다. Functions unit/emulator와 Rules/harness는 repository `.nvmrc` 및 `npx node@22` 실행 경계를 사용하고 `NODE_RUNTIME required=22 actual=<version> status=ok`를 먼저 출력하며 major가 22가 아니면 test 전에 실패한다. CI도 `.nvmrc`로 Node를 설치하고 같은 canonical commands를 실행한다.
- Mini-home ordering generation은 layout 존재 여부나 layout revision과 분리된 owner 전역 server epoch다. published owner projection generation은 canonical content 또는 bound catalog projection이 바뀌는 pointer swap에서만 정확히 1 증가하고 exact operation replay와 exact load는 projection ID, token, generation을 그대로 유지한다. projection은 typed missing/present layout, inventory contract v3, plants, 각 domain generation/revision, canonical lowercase SHA-256 token을 함께 고정하고 endpoint만 transaction read time을 장식한다. callable과 Android는 generation 0을 authoritative 값으로 받지 않는다. Room v20은 layout과 inventory watermark에 같은 non-null snapshot token/generation을 한 transaction으로 저장하고 둘이 없거나 다르면 layout, placements, catalog, ownership과 두 watermark를 함께 purge한다. verified domain generation이 작은 응답은 `Ignored`, 같은 generation의 exact identity/content replay만 idempotent `Ignored`, mismatch는 `Conflict`, 큰 generation만 `Applied`다. bootstrap 전에 시작한 늦은 응답도 verified state를 삭제·회귀시킬 수 없고 failure/success publication은 post-remote atomic Room reread의 최신 coherent winner만 게시한다.
- `NETWORK`, local `DATABASE`, authoritative receipt read 실패인 `INCONSISTENT_RECEIPT`만 transient exact-retry 상태다. 확정본을 유지하고 같은 operation ID와 frozen snapshot만 다시 전송한다. Firebase `DATA_LOSS`나 malformed callable result는 `MALFORMED_RESPONSE` 영구 상태이며 network retry로 내리지 않는다. revision 충돌은 서버 확정본과 현재 inventory를 즉시 다시 읽어 캐시하되 사용자 draft를 보존하며, 사용자가 `최신 구성 불러오기`를 선택하기 전 stale draft를 재전송하지 않는다.
- owner-scoped Room outbox는 mini-home operation ID, expected revision, callable과 동일한 canonical JSON SHA-256 payload hash를 고정하고 `PENDING -> MAY_HAVE_COMMITTED -> RECONCILIATION_REQUIRED` phase를 영속화한다. callable 경계에 들어가기 전에 `MAY_HAVE_COMMITTED`로 전환하며 typed failure reason/details와, 알려진 경우 committed operation ID/expected revision/revision/payload hash receipt도 함께 저장한다. restart load는 어떤 재전송보다 먼저 authoritative home receipt를 이 identity와 비교한다. operation ID, expected revision, canonical payload hash, exact next revision과 layout content가 모두 일치할 때만 확정 layout을 채택하고 outbox를 제거한다. operation ID가 같아도 payload hash가 다르면 `PAYLOAD_MISMATCH`이며 높은 revision을 성공으로 오인하지 않는다.
- Android와 callable은 이름의 1~100 Unicode code point, NFC 정규화, 유효한 Unicode scalar, C0/C1 control 및 UAX #9 bidi control 금지를 같은 request contract로 검사한다. 앞뒤 whitespace 판정은 플랫폼 `trim`/`isWhitespace`를 쓰지 않고 Unicode White_Space의 고정 집합 `U+0009..000D, U+0020, U+0085, U+00A0, U+1680, U+2000..200A, U+2028, U+2029, U+202F, U+205F, U+3000`을 양쪽에 명시한다. path-safe owner/home/placement/target ID, 8~128자 operation ID, JavaScript safe revision, 5x4 snapped 좌표, 최대 20개, exclusive target, unique identity/target/cell, projected contiguous order도 같은 경계에서 검사한다. Android는 이 계약을 outbox 생성 전에 검사해 이름 오류를 editable validation으로 표시한다. callable의 모든 validation rejection은 `INVALID_REQUEST`와 `field` details를 반환한다.
- strict write 계약은 non-NFC 이름을 계속 거부하되 persisted legacy read 경계만 NFC recovery를 허용한다. Room 9→10은 정규화 결과가 위 canonical 계약을 만족하는 이름만 원자적으로 rewrite하고 control/bidi/overlength 등 복구 불가능한 home과 해당 placement cache를 격리한다. v10 runtime도 cache parse 실패를 remote refresh 밖으로 누출하지 않는다. authoritative remote를 먼저 적용해 invalid cache를 transaction으로 교체하고, offline recoverable cache는 정규화 이름을 표시한 뒤 compare-and-rewrite하며, offline irrecoverable cache는 typed load error로 닫힌다. legacy remote 문서는 같은 read-only recovery 뒤 canonical cache로 저장할 수 있지만 operation payload와 receipt hash는 원문 identity를 유지한다.
- durable outbox decode는 raw persisted envelope와 canonical domain construction을 분리한다. 크기 제한과 scalar safety를 통과한 JSON의 원문 bytes, raw 이름, operation/lineage identity와 저장된 payload hash를 먼저 보존하고, 화면용 draft만 별도로 NFC canonicalize한다. 예를 들어 raw `e + combining acute` 100쌍은 200 raw code point identity와 hash를 유지하면서 100자 NFC 이름으로 표시한다. receipt 비교용 hash는 저장값을 신뢰하지 않고 raw envelope의 exact expected revision/home/name/placement 순서와 필드에서 callable canonical JSON을 매번 재구성해 SHA-256으로 계산한다. 저장 hash와 recomputed hash 및 receipt hash 비교는 constant-time equality를 사용한다. 저장 hash가 forged/stale이면 receipt도 같은 forged hash여도 채택하지 않고 `PAYLOAD_MISMATCH`/`RECONCILIATION_REQUIRED`로 유지하며 stored/recomputed/authoritative hash를 모두 typed details에 노출한다. hash가 없는 pre-v9 legacy 행만 recomputed exact hash를 backfill하며 raw payload나 기존 receipt identity를 정규화 결과로 다시 쓰지 않는다. canonicalize가 필요한 operation은 같은 identity로 재전송하지 않으며 recomputed raw hash의 authoritative receipt와 canonical content가 모두 맞을 때만 채택한다. mismatch pending은 raw envelope/이름/operation ID와 authoritative operation/expected revision/revision을 명시적 owner action 전까지 제거하거나 `pending = null`로 숨기지 않는다. canonical-invalid, malformed surrogate/JSON 또는 identity mismatch envelope도 `MALFORMED_RESPONSE`/`RECONCILIATION_REQUIRED` quarantine으로 노출한다. Room v10→11은 각 outbox row에 opaque `rowHandleId` generation을 backfill하고 v11→12는 mutation마다 증가하는 `rowVersion`을 추가한다. quarantine의 화면용 synthetic ID와 별도로 persisted owner/aggregate type/row operation/row lineage/row generation/version으로 구성한 handle을 유지한다. remote save/load 전에 고정한 이 전체 handle만 payload-hash backfill, phase/reason/details, receipt adoption과 consume의 CAS 조건으로 사용한다. 어느 단계든 CAS가 실패하면 remote 결과를 버리고 replacement pending을 다시 읽으며 replacement row는 변경하지 않는다. discard는 `Consumed`, replacement를 포함한 `StaleHandle`, `Missing`, `OwnerMismatch`, `Rejected` typed 결과를 반환한다. durable outbox를 남기는 transient `Failed/NETWORK` 응답도 CAS 직후의 validated handle을 함께 반환한다. handle이 없는 pre-outbox `Failed/DATABASE`와 local draft discard는 현재 owner와 mini-home aggregate type의 pending row를 Room에서 authoritative query한다. query가 성공해 행이 없을 때만 `Missing`으로 local draft를 지우고, 행이 있으면 그 행의 전체 validated handle로 CAS consume하며, read 실패는 `Rejected`로 편집과 retry 안내를 유지한다. query와 consume 사이에 생긴 replacement도 CAS 실패 뒤 현재 pending으로 돌아가므로 no-row와 DB-read failure를 추론으로 합치지 않는다. controller는 load/save/reconcile/discard/conflict adoption/inventory reload 전에 controller epoch, generation, owner UID, operation ID, lineage ID와 full discard handle을 하나의 immutable operation token으로 고정한다. 모든 suspend/await 뒤와 UI 또는 `SavedStateHandle` mutation 직전에 같은 token인지 다시 확인하며, `setEditing`과 draft persistence는 mutable current owner를 추론하지 않고 이 explicit owner token만 받는다. 새 controller가 같은 `SavedStateHandle`을 인수하면 persisted controller epoch를 전진시켜 이전 process/controller callback을 무효화한다. lifecycle resume의 load sequence는 controller owner generation과 분리한다. 같은 UID refresh는 generation을 전진시키거나 owner job을 취소하거나 확정 화면을 `Loading`으로 덮지 않으며, in-flight save의 remote 결과와 UI publication 완료를 await한 뒤 load 시작 이후 state version이 바뀌었으면 stale 결과를 폐기한다. save 성공은 operation/lineage/layout을 `SavedStateHandle`에 확정 marker로 기록한 뒤 draft를 지우고 viewing revision을 게시한다. 같은 UID의 늦은 load나 controller recreation은 이 marker보다 낮거나 같은 revision으로 회귀할 수 없다. transport response loss는 remote committed operation ID, expected/committed revision, exact canonical payload hash와 layout content가 모두 saved draft와 일치하고 repository가 outbox 부재를 반환한 경우에만 확정 저장으로 채택한다. account 변경이나 logout만 generation을 전진시키고 이전 owner의 등록된 controller job을 cancel/join한 뒤 그 owner의 draft와 확정 marker를 지우므로 non-cancellable late boundary 결과도 새 owner state/Room request/SavedState를 바꾸지 못한다. controller는 save/reconcile 호출 전에 owner, operation ID와 save generation의 in-flight registration을 동기적으로 만들고, discard가 먼저 generation을 무효화한 뒤 그 registration의 remote 완료를 await한다. same-owner resume은 별도 settled signal로 save 결과 적용과 draft 정리까지 기다린다. repository의 owner별 operation mutex는 outbox insert·remote result·receipt reconciliation과 authoritative discard query/CAS를 직렬화하므로 pre-outbox no-row가 진행 중 save를 추월하지 못한다. durable remote boundary를 지난 save는 Activity coroutine 취소 중에도 receipt/CAS 정리를 완료하고, 같은 process의 재생성은 owner+operation ID로 제한된 최근 성공 결과를 correlate한다. remote commit이 먼저 선형화되면 `Committed(authoritative)`를 반환해 저장 확정 화면을 표시하고 discard 성공이나 자동 exit로 보고하지 않으며, response loss와 `MAY_HAVE_COMMITTED`는 authoritative receipt load가 확정될 때까지 consume하지 않는다. 늦은 save callback은 무효화된 generation에서 UI/draft를 변경할 수 없다. controller는 `Consumed` 또는 safe authoritative query/reload가 pending 부재를 확인한 `Missing`에서만 편집을 닫고, `Committed`/stale/rejected 결과에는 각각 확정본 또는 최신 draft와 접근성 오류 안내를 유지한다. unsaved/reconciliation dialog도 이 typed 결과를 끝까지 await하고 성공 결과에서만 한 번 navigate하며, 처리 중 버튼을 잠그고 Activity state 복원 뒤에는 취소된 요청을 성공으로 오인하지 않은 채 다시 시도할 수 있다. stale/wrong/cross-owner/type handle이나 delete-reinsert ABA replacement는 실패로 닫히며 synthetic ID, malformed payload ID 또는 다른 owner/domain row를 lookup/delete key로 사용하지 않는다.
- durable state/reason transition table은 `NETWORK`, local `DATABASE`, authoritative read 자체가 실패한 `INCONSISTENT_RECEIPT`만 frozen exact request 재전송을 허용한다. `UNAVAILABLE_ENTITY`, `OUTBOX_MISMATCH`, `PAYLOAD_MISMATCH`, `REVISION_CONFLICT`, `PERMISSION_DENIED`, `MALFORMED_RESPONSE`, `INVALID_REQUEST` 또는 phase 자체가 `RECONCILIATION_REQUIRED`인 모든 행은 callable 전송과 `MAY_HAVE_COMMITTED` 전이를 먼저 차단한다. `INVALID_REQUEST`는 unusable operation을 `RECONCILIATION_REQUIRED` tombstone으로 retire하고 draft field를 즉시 다시 편집 가능하게 한다. 값이 실제로 바뀔 때만 새 operation ID를 할당한다. root `lineageId`와 직전 `supersedesOperationId`는 outbox와 `SavedStateHandle` draft에 함께 보존되며 restart는 superseded tombstone이 아니라 lineage head를 복원한다. 명시적 discard/cancel은 owner, mini-home aggregate type, lineage ID를 한 Room delete로 제한해 invalid ancestor, corrected successor, response-unknown 행을 모두 제거하되 다른 계정·lineage·도메인은 보존한다. 그 외 영구 상태는 같은 fixed request를 반복하지 않고 `최신 구성과 배치 대상 확인`만 제공한다. explicit reconciliation만 authoritative layout/inventory/receipt를 다시 읽고, 삭제·비보유 대상만 draft에서 제거하면서 살아 있는 이름·위치·배치를 보존한 corrected draft를 만든 뒤 이전 outbox를 제거하고 새 operation을 할당할 수 있다. corrected draft는 `수정한 배치 저장`을 명시적으로 요구하며 자동 저장하지 않는다. 계정 전환과 restart는 reason/details/hash를 owner partition에 그대로 유지한다.
- Activity/process 복원은 private route state를 `mini-home.restoration-owner`와 controller epoch에 명시적으로 묶는다. `Restoring`/unknown 인증은 어떤 복원 결정이나 repository load도 하지 않는다. authoritative authenticated UID가 현재 또는 restored owner와 다르거나 owner marker가 없는 legacy state이면 B repository I/O 전에 generation/owner token을 바꾸고 A draft·confirmed marker·exit intent를 제거한 뒤 owner-typed `Loading(B)`만 게시한다. A의 등록된 save/load/reconcile/discard job은 이 neutral publication 뒤 cancel/join되며 B load는 join 완료 전 시작하지 않는다. 따라서 B load가 실패하거나 owner가 A→B→A로 돌아가도 A 이름·placement는 UI, SavedState, debug snapshot에 나타나지 않고 `Unavailable(B)`만 남는다. authoritative signed-out도 같은 방식으로 즉시 ownerless `Forbidden`을 게시하고 private marker를 지운다. 같은 UID refresh와 같은-owner process restoration만 기존 exact draft/save correlation을 보존한다. 이 정리는 owner-partitioned Room outbox에는 적용하지 않으므로 B의 offline cache와 durable reconciliation row는 그대로 복원된다. repository는 application runtime의 Room/Firebase 인스턴스를 재사용한다.
- Controller lifecycle effect보다 먼저 실행되는 composition도 동일한 경계를 지킨다. 모든 renderable `MiniHomeUiState`는 private payload owner를 직접 가지며 `MiniHomeRoute`는 현재 authoritative auth owner와 동기적으로 비교한다. UID mismatch는 effect나 repository 응답을 기다리지 않고 owner-typed neutral loading state로 치환하고, restoring/unknown은 ownerless loading, signed-out은 forbidden으로 치환한다. 이 displayed state만 semantics, accessibility, action wiring, debug observation에 전달된다. 홈의 확정 mini-home preview를 포함한 private `HomeUiState`도 owner UID를 가지며 NavHost에서 같은 방식으로 gate되어 계정 전환 첫 frame에 이전 owner preview가 남지 않는다.
- save-and-exit/discard-and-exit UI intent는 ownerless boolean이 아니라 owner UID, controller epoch/generation, action kind, operation/lineage/full discard handle, stable intent ID를 가진 typed `NavigationIntentToken`으로 `rememberSaveable`에 보존한다. controller가 owner와 함께 immutable session token을 노출하고 save/discard completion도 exact operation identity를 담는다. composition은 current session과 exact outcome이 모두 맞을 때만 token별로 한 번 navigate한다. account 변경, logout, A→B→A, replacement operation은 token을 제거하고 late result를 무시한다. 같은 owner의 Activity/process 복원은 persisted full identity가 현재 restored draft 또는 exact completion과 일치할 때만 새 controller epoch/generation으로 token을 rebind한다.
- API 37 visual evidence는 null photo path의 deterministic fallback seed, 저장 완료와 placement semantics가 관측된 settled viewing state, 1080x2400/420dpi crop, wipe-data/no-snapshot AVD와 software GPU backend를 고정한다. Activity 재생성 계측은 trigger 전에 현재 owner/name/revision과 placement ID/target/cell/z-index의 canonical typed state를 구독하고, 새 Activity identity가 같은 state를 실제 composition에 적용한 신호만 bounded await한다. retained ViewModel과 repository reload는 모두 유효한 복원 경로이며 단순 `waitForIdle`이나 load 호출 유무를 복원 완료로 추정하지 않는다. 시스템/Compose animation 중간 frame, cursor/selection, async photo load 또는 system-bar screenshot을 evidence로 쓰지 않는다. 동일 cleanup/seed로 새 AVD를 두 번 시작해 `captureToImage` PNG가 byte-identical일 때만 approved evidence를 교체한다.
- 배치 대상은 현재 owner의 개인 식물과 이미 보유한 PUBLIC `FURNITURE`/`DECORATION`만이다. 상점 획득, 배경 적용, 공유는 각각 후속 기능의 경계로 남긴다.

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
