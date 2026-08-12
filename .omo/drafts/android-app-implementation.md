---
slug: android-app-implementation
status: drafting
intent: clear
review_required: true
plan_path: .omo/plans/android-app-implementation.md
plan_sha256: null
review_round_id: null
review_round_limit: 5
pending-action: write and review .omo/plans/android-app-implementation.md
review:
  momus:
    status: pending
    workspace_root: null
    runtime_home: null
    target: .omo/plans/android-app-implementation.md
    round_id: null
    plan_sha256: null
    launch_id: null
    session: null
    result: null
approach: Android 네이티브 앱 전체 범위를 기능별 수직 슬라이스로 구축하고, Figma가 있는 화면은 그대로 재현하며 누락 화면은 동일 디자인 토큰으로 보완한다. 외부 서비스와 서버 경계는 사용자 결정 후 고정한다.
---

# Draft: android-app-implementation

## Components (topology ledger)
<!-- Lock the SHAPE before depth. One row per top-level component that can succeed or fail independently. -->
<!-- id | outcome (one line) | status: active|deferred | evidence path -->
| id | outcome | status | evidence |
| --- | --- | --- | --- |
| foundation | Kotlin/Compose 앱, 디자인 시스템, 내비게이션, 공통 상태·오류 처리 기반 | active | Figma `Page 1`, PRD 전체 |
| auth-sync | 소셜 로그인, 세션, 계정 데이터 동기화, 계정 삭제 | active | PRD 8, Figma login frames |
| dashboard | 오늘의 관리, 날씨 주의, 미니홈피 진입을 제공하는 홈 | active | 유저플로우 s2, Figma home frames |
| identification | 촬영·선택·검토·분석·후보 확정·직접 등록 | active | PRD 1, Figma `plant-capture-flow-board` |
| catalog-care | 도감 목록·상세·증상 안내·관리 기록 | active | PRD 2, Figma `plant-parent-do-gam-flow` |
| watering | 일정 계산, 알림 설정·발송·딥링크·완료 기록 | active | PRD 3, Figma `plant-care-settings-flow` |
| weather | 지역·권한·날씨 조회·환경 위험·행동 안내·알림 | active | PRD 4 |
| mini-home | 식물 미니어처 배치, 꾸미기, 저장, 이미지·링크 공유 | active | PRD 5, Figma `myroom-*` |
| inventory-shop | 아이템 창고·상점·획득·적용·해제 | active | PRD 6, Figma `plant-parent-board` |
| privacy-settings | 권한, 알림, 지역, 개인정보 및 삭제 설정 | active | PRD 8, Figma `settings-*` |
| admin-web | 식물 콘텐츠 운영·게시·이력 웹 화면 | deferred | PRD 7, 유저플로우 s8 |

## Open assumptions (announced defaults)
<!-- Record any default you adopt instead of asking, so the user can veto it at the gate. -->
<!-- assumption | adopted default | rationale | reversible? -->
| assumption | adopted default | rationale | reversible? |
| --- | --- | --- | --- |
| 모바일 범위 | PRD의 일반 사용자용 모바일 기능 전체를 포함 | 요청이 “안드로이드 앱”이고 축소 범위를 요청하지 않음 | no |
| 관리자 범위 | 웹 관리자 구현은 제외하고 모바일이 소비할 콘텐츠 계약만 정의 | PRD가 별도 웹 디바이스로 명시 | yes |
| UI 기준 | Figma 존재 화면은 픽셀 기준으로 구현하고 누락 화면은 동일 토큰·패턴으로 설계 | Figma가 전체 PRD 화면을 포함하지 않음 | yes |
| Android UI | Kotlin, Jetpack Compose, Material 3, 단방향 상태 흐름 | 신규 네이티브 Android 앱의 검증된 기본값 | yes |
| 앱 구조 | 기능별 모듈 + core design/data/domain/testing 모듈 | 전체 기능 간 독립 배포·테스트 경계가 필요 | yes |
| 로컬 데이터 | Room 캐시와 DataStore 설정, 네트워크 단일 진실 원천 | 동기화 실패 시 마지막 데이터 유지 요구 | yes |
| 백그라운드 | WorkManager로 재시도 가능한 동기화·일정 재계산, FCM으로 원격 알림 | 계정 간 동기화와 푸시 요구에 적합 | partly |
| 사진 입력 | Android Photo Picker 우선, CameraX 촬영, 앱 전용 임시 파일 | 광범위 저장소 권한을 피하고 촬영 흐름 충족 | yes |
| 테스트 | 상태·도메인 로직 TDD, UI·통합·스크린샷 테스트 후속 | 일정·권한·딥링크 회귀 위험이 큼 | yes |
| 화면 상태 | Initial/Loading/Content/Empty/Partial/Submitting/Success/RecoverableError/AuthRequired/Forbidden/NotFound 공통 계약 | 문서 전반의 로딩·부분 실패·입력 보존 요구를 일관되게 구현 | yes |
| 오프라인 쓰기 | 관리 기록·물 주기만 멱등 큐에 보관하고 식별·상점·삭제·공유 링크는 온라인 확정 | 성공을 허위 표시하지 않으면서 핵심 관리 지속성 확보 | yes |
| 위치 권한 | 현재 지역 판별에는 대략적 위치만 요청 | 날씨 지역 설정에는 정밀 위치가 불필요 | yes |

## Findings (cited - path:lines)
- 원격 GitHub 저장소 API는 “repository is empty”를 반환하고 로컬에도 `.git` 및 Android 소스가 없어 신규 프로젝트로 계획해야 한다.
- 제품은 사진 식별에서 개인 도감, 물 주기, 날씨 주의, 미니홈피까지 이어지는 전체 모바일 경험을 요구한다 (`docs/초보 식집사_PRD.md:34`, `docs/초보 식집사_PRD.md:55`, `docs/초보 식집사_PRD.md:369`).
- 알림 일정과 환경 위험은 독립 기능이지만 홈·상세·설정·딥링크에서 결합된다 (`docs/초보 식집사_PRD.md:616`, `docs/초보 식집사_PRD.md:836`).
- 미니홈피와 아이템 기능은 핵심 관리 기능을 차단하지 않는 선택 기능이다 (`docs/초보 식집사_PRD.md:1121`, `docs/초보 식집사_PRD.md:1307`).
- 계정 동기화, 사진·위치 동의, 연결 데이터 삭제가 모바일 필수 범위다 (`docs/초보 식집사_PRD.md:1768`).
- 웹 관리자 기능은 별도 디바이스·역할이며 Android 구현 범위와 분리 가능하다 (`docs/초보 식집사_PRD.md:49`, `docs/초보 식집사_PRD.md:1560`, `docs/초보 식집사_유저플로우.md.md:103`).
- 유저플로우는 인증·홈·식별·도감·알림·미니홈피·설정의 내비게이션 뼈대를 제공한다 (`docs/초보 식집사_유저플로우.md.md:4`, `:15`, `:22`, `:40`, `:56`, `:65`, `:89`).
- Figma `Page 1`에는 402x874 모바일 프레임, 배경 `#FCFBF7`, 경계 `#E5E7EB`, 48px 프레임 반경과 홈·도감·카메라·창고·설정 5개 하단 탭이 확인된다.
- Figma는 로그아웃/로그인 홈, Google·Apple 로그인, 카메라 식별, 도감 목록·상세·증상·빈 상태, 물 주기 설정, 창고·상점·아이템 상세, 미니홈피, 설정 화면을 제공한다.
- Figma에는 PRD의 온보딩·회원가입·날씨 상세·공유 링크 정책·계정 삭제 전 과정을 완전히 덮는 화면이 없어 동일 디자인 시스템으로 보완 설계가 필요하다.
- 기능명세서 이미지는 PRD의 8개 대분류와 세부 기능 번호를 시각적으로 교차 확인하며, 미니홈피 공간 꾸미기 상세만 “상세 기능 추가”로 미완성 표시되어 있다 (`docs/초보 식집사_기능명세서.png`).
- 상세 설계 자문 결과, 내비게이션은 사용자 ID나 사진 데이터를 전달하지 않고 식별 요청·개인 식물·위험 판단·미니홈피 구성의 불투명 ID만 전달하며 서버에서 소유권을 재검증해야 한다.
- 알림 콜드 스타트는 홈→상위 목록→대상 상세 백스택을 만들고, 삭제된 대상은 개인정보를 노출하지 않는 NotFound와 상위 화면 CTA로 처리해야 한다.
- 물 주기 날짜는 `LocalDate`, 감사·서버 시각은 `Instant`, 알림 시간은 `LocalTime`, 해석 기준은 `ZoneId`로 분리해야 한다.
- 캐시가 있는 새로고침 실패는 콘텐츠를 유지한 Stale 상태, 변경 실패는 마지막 확정값과 사용자 초안을 함께 유지하며 멱등 키·revision으로 중복과 무음 덮어쓰기를 막아야 한다.
- 식별 공급자는 문서에 고정되지 않았다. Plant.id와 Pl@ntNet이 현재 공식 개발 API를 제공한다 (`https://www.kindwise.com/plant-id`, `https://my.plantnet.org/`).
- 날씨 공급자는 문서에 고정되지 않았다. OpenWeather는 현재 기온·습도·강수 및 One Call API를 제공한다 (`https://openweathermap.org/api`, `https://openweathermap.org/api/one-call-3`).

## Decisions (with rationale)
- 전체 Android 사용자 경험을 하나의 계획으로 작성하고 웹 관리자 구현은 제외한다.
- 문서가 정의한 상태·예외·권한·데이터를 PRD의 동작 근거로, Mermaid를 내비게이션 근거로, Figma를 시각 근거로 사용한다.
- 구현 순서는 기반 → 인증/동기화 → 식별/도감 → 일정/알림 → 날씨 → 미니홈피/아이템 → 개인정보로 잡고 각 단계가 독립 검증 가능하게 한다.
- 모든 커밋 항목은 실제 diff만을 근거로 Conventional Commits 형식의 72자 이내 한국어 제목을 사용한다.
- 사진·위치·푸시 payload·분석 이벤트에는 원본 이미지, 정확한 좌표, 개인 메모, 인증 정보를 기록하지 않는다.
- 날씨 데이터가 오래되면 기존 값을 시각적으로 표시할 수 있으나 신규 위험 푸시는 생성하지 않는다.
- 식별 성공은 개인 식물을 즉시 생성하지 않고 후보 확정 후 별도 등록 저장을 거친다.
- 백엔드는 Firebase Authentication, Firestore, Storage, Cloud Functions, FCM으로 구성한다.
- 로그인은 Figma에 표시된 Google·Apple 소셜 로그인을 구현하고 Firebase 계정으로 통합한다.
- 식물 식별은 Plant.id, 날씨는 OpenWeather를 사용하며 API 키와 공급자 호출은 Cloud Functions 뒤에 둔다.
- 최소 지원 버전은 Android 10(API 29), 배포 대상은 Google Play 한국으로 고정한다.
- 동일 식물의 중복 등록은 경고 후 허용하고, 기존 기록 열기·추가 등록·취소 선택지를 제공한다.
- 동일 아이템의 중복 획득은 차단하고 기존 보유 상태를 반환한다.
- 미니홈피는 배경 1개, 가구 10개, 장식 10개를 적용 한도로 사용한다.
- 공유 링크는 저장된 미니홈피 revision의 스냅샷이며 생성 후 30일에 만료한다.
- 계정 삭제는 7일 유예 후 실행하며 유예 기간에는 복구할 수 있다.
- 물 주기 알림은 예정일 당일 1회, 미완료 시 다음 날 1회만 재알림한다.
- 물 주기 간격은 공개 관리 콘텐츠에 정수 일수로 저장하고, 공개된 간격 변경은 각 식물의 다음 일정 재계산에 반영한다.
- 콘텐츠 비공개 전환 시 기존 개인 식물은 유지하되 해당 콘텐츠 기반 신규 일정·위험 판단을 중단하고 안내 불가 상태를 표시한다.
- 여러 기기의 동시 수정은 서버 revision을 기준으로 충돌을 중단하고 로컬 초안을 보존하여 재적용하게 한다.
- 식별용 사진은 분석 완료 또는 실패 후 24시간 내 임시 원본을 삭제하고, 사용자가 도감 대표 사진 저장을 선택한 경우에만 별도 영구 사본을 보관하며 모델 학습에 사용하지 않는다.

## Scope IN
- 신규 Android 프로젝트와 CI·품질 게이트
- 일반 사용자용 모바일 기능 1~6, 8 전체
- 홈 대시보드와 유저플로우의 모바일 내비게이션
- Figma 기반 디자인 시스템과 제공 화면 재현
- 외부 인증·식별·날씨·동기화·푸시·공유 연동
- 로컬 캐시, 오프라인 조회, 동기화 실패 복구
- 접근성, 권한, 개인정보 고지, 딥링크
- 단위·통합·Compose UI·스크린샷·기기 QA

## Scope OUT (Must NOT have)
- 웹 관리자 UI 및 운영 도구 구현
- 식물 식별 모델 학습·호스팅·운영
- 실내 센서 연동
- 소셜 피드·타 사용자 미니홈피 탐색
- 문서에 없는 결제·광고·유료 재화
- iOS·웹 사용자 앱
- Figma 원본 편집
- 관련 없는 저장소 정리

## Open questions
- 없음. 사용자가 모든 추천안을 승인했으며 계획 작성에 필요한 제품·외부 서비스·배포·데이터 정책이 고정되었다.

## Approval gate
status: awaiting-approval
approach: Firebase 기반 Android 네이티브 앱을 기능별 수직 슬라이스로 구축한다. 문서 요구사항을 동작 기준, Mermaid를 내비게이션 기준, Figma를 시각 기준으로 삼고 제공되지 않은 화면만 동일 디자인 시스템으로 보완한다.
test-strategy: 도메인·상태·일정 로직은 TDD, Firebase emulator 및 공급자 fake 기반 통합 테스트, Compose UI·접근성·스크린샷 테스트, API 29와 최신 API 에뮬레이터에서 실제 권한·딥링크·알림·공유 QA를 수행한다.
next-action: 사용자의 명시적 계획 작성 승인을 받은 뒤 scaffold-plan으로 .omo/plans/android-app-implementation.md를 생성하고 Todos 및 최종 검증 파동을 추가한 후 MOMUS 고정밀 검토를 최대 5회 수행한다.
<!-- When exploration is exhausted and unknowns are answered, set status: awaiting-approval. -->
<!-- That durable record is the loop guard: on a later turn read it and resume at the gate instead of re-running exploration. -->
