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
