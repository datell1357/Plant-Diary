# android-app-implementation - Work Plan

## TL;DR (For humans)
<!-- Fill this LAST, after the detailed plan below is written, so it summarizes the REAL plan. -->
<!-- Plain English for a non-engineer: NO file paths, NO todo numbers, NO wave/agent/tool names. -->

**What you'll get:** 사진으로 식물을 식별하고 개인 도감에 등록한 뒤 물 주기와 날씨 위험을 관리하며, 식물 미니어처로 방을 꾸미고 공유할 수 있는 Android 앱입니다. 로그인·기기 간 동기화·권한 관리·계정 삭제까지 Google Play 출시 가능한 상태로 제공합니다.

**Why this approach:** Android 네이티브 UI와 Firebase 서버리스 백엔드를 함께 구축해 복잡한 계정·동기화·푸시 흐름을 하나의 보안 경계로 관리합니다. 문서를 동작 기준, Figma를 시각 기준으로 사용해 기능 누락과 디자인 임의 해석을 줄입니다.

**What it will NOT do:** 웹 관리자 화면, 자체 식물 식별 모델, 결제·광고, 소셜 피드, iOS 앱은 만들지 않습니다. Figma 원본이나 요청과 무관한 파일도 변경하지 않습니다.

**Effort:** XL
**Risk:** High - 신규 저장소에서 외부 식별·날씨 API, 계정 동기화, 예약 푸시, 개인정보 삭제까지 동시에 구축해야 합니다.
**Decisions to sanity-check:** Android 10 이상, Firebase 백엔드, Plant.id·OpenWeather, 24시간 분석 사진 보관, 7일 삭제 유예, 30일 공유 링크 만료 정책입니다.

Your next move: MOMUS 고정밀 검토 승인 후 `/start-work android-app-implementation`으로 별도 실행합니다. Full execution detail follows below.

---

> TL;DR (machine): XL/high-risk 신규 Android+Firebase 제품 구축; 모바일 기능 1-6·8, 외부 API, 푸시, 동기화, 개인정보, 출시 검증 포함.

## Scope
### Must have
- Android 10(API 29) 이상, Google Play 한국 출시용 Kotlin·Jetpack Compose 앱.
- Figma의 402x874 화면, 색상·타이포·간격·모서리·아이콘·하단 탭을 토큰화하고 제공 화면을 시각적으로 재현.
- Google·Apple 로그인, Firebase 계정 통합, 기기 간 데이터 동기화와 계정별 로컬 캐시 격리.
- CameraX 촬영, Android Photo Picker, 사진 처리 고지, Plant.id 후보·신뢰도, 직접 입력·중복 등록.
- 개인 도감, 식물 상세, 초보자용 관리·증상 콘텐츠, 마지막 물 주기·위치·메모.
- 물 주기 일정 계산, 당일 1회와 다음 날 1회 재알림, FCM 딥링크, 완료 기록과 재계산.
- 위치 또는 수동 지역, OpenWeather, 고온·저온·건조·과습 판정, stale 상태와 날씨 푸시.
- 미니홈피 미니어처·아이템 배치, 창고·상점, 이미지 공유, 30일 스냅샷 링크.
- 권한·알림·지역 설정, 24시간 분석 원본 삭제, 7일 유예 계정 삭제·복구·완료 상태.
- Room 캐시, DataStore 설정, 멱등 쓰기, revision 충돌, 부분 동기화 상태.
- 단위·Firebase Emulator 통합·Compose UI·접근성·스크린샷·에뮬레이터 E2E 테스트와 출시 빌드.

### Must NOT have (guardrails, anti-slop, scope boundaries)
- 웹 관리자 UI, 식별 모델 학습·호스팅, 실내 센서, 소셜 피드, 공개 미니홈피 탐색.
- 결제, 광고, 유료 재화, iOS·웹 사용자 앱, Figma 원본 편집.
- Android 앱에 Plant.id·OpenWeather 비밀키 포함, 원본 사진·정확한 좌표·메모·인증값 분석 로그 수집.
- `Any`, 무타입 Map 기반 도메인 모델, 화면별 임의 오류 문자열, 전역 mutable singleton.
- 캐시·외부 공유·로컬 성공만으로 서버 저장·계정 삭제·링크 생성을 완료로 표시.
- 고정 sleep·폴링 기반 테스트, 테스트 skip·lint 억제·타입 오류 무시.
- 인접 코드 정리, 범위 외 추상화, 하위 호환 shim, 문서에 없는 기능 추가.

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: 도메인·상태·스케줄·보안 규칙은 TDD; UI·외부 연동은 fake/emulator를 먼저 만든 뒤 tests-after. JUnit 5, kotlinx-coroutines-test, Turbine, MockWebServer, Firebase Emulator Suite, Compose UI Test, Roborazzi, AndroidX Benchmark 사용.
- 고정 시계: `2026-08-12T09:00:00+09:00`, `ZoneId.of("Asia/Seoul")`; 비동기 테스트는 repository flow, Compose idle, WorkManager TestDriver, emulator 응답 이벤트를 구독하고 bounded timeout으로 대기.
- 공통 정적 검증: `./gradlew spotlessCheck lintDebug testDebugUnitTest`.
- 공통 통합 검증: `firebase emulators:exec --project demo-planterior "./gradlew connectedDebugAndroidTest"`.
- 출시 검증: `./gradlew clean bundleRelease :app:lintVitalRelease`.
- Evidence: `<attemptDir>/task-<N>-android-app-implementation.{log,png,json}` (attemptDir = currentAttemptDir from `omo-agent-toolkit ulw-loop status --json`; outside ulw-loop use `.omo/evidence/`).

## Execution strategy
### Parallel execution waves
> Target 5-8 todos per wave. Fewer than 3 (except the final) means you under-split.
- **Wave 1 - 기반과 계약:** 1, 2, 3을 병렬 수행하되 2·3은 1의 프로젝트 생성 직후 시작한다.
- **Wave 2 - 핵심 계정·입력:** 4, 5, 6, 7을 병렬 수행한다.
- **Wave 3 - 식물 등록·관리:** 8, 9, 10, 11을 병렬 수행한다.
- **Wave 4 - 환경·놀이 기능:** 12, 13, 14, 15를 병렬 수행한다.
- **Wave 5 - 개인정보·관측·출시:** 16, 17, 18을 병렬 시작하고, 18의 최종 빌드·E2E는 16·17 완료 후 실행한다.
- 동일 파일 충돌을 피하도록 기능 모듈별 소유권을 유지하며 `app` 조립 파일은 각 wave 종료 시 한 작업만 수정한다.

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| 1 | - | 2-18 | - |
| 2 | 1 | 4-16, 18 | 3 |
| 3 | 1 | 4, 7-17 | 2 |
| 4 | 2, 3 | 5, 8-18 | 6, 7 |
| 5 | 2, 4 | 18 | 6, 7 |
| 6 | 2 | 7, 8 | 4, 5 |
| 7 | 3, 6 | 8, 17 | 4, 5 |
| 8 | 3, 4, 6, 7 | 9-15, 18 | - |
| 9 | 3, 4, 8 | 10-15, 18 | - |
| 10 | 3, 9 | 11, 12, 18 | - |
| 11 | 3, 4, 10 | 12, 18 | - |
| 12 | 3, 4, 9, 11 | 18 | 13, 14, 15 |
| 13 | 3, 4, 8 | 14, 15, 18 | 12 |
| 14 | 3, 13 | 15, 18 | 12 |
| 15 | 3, 13, 14 | 18 | 12 |
| 16 | 3, 4, 9, 13, 14 | 18 | 17 |
| 17 | 3, 7, 11, 12, 15, 16 | 18 | - |
| 18 | 2-17 | F1-F4 | - |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->
- [ ] 1. Android 프로젝트와 품질 기반 구성
  Recommended task executor category: `unspecified-high`
  What to do / Must NOT do: Gradle version catalog을 사용하는 Kotlin·Compose 멀티모듈 프로젝트를 생성한다. `app`, `core:model`, `core:designsystem`, `core:data`, `core:database`, `core:network`, `core:testing`과 기능 모듈 틀, API 29 최소 버전, 한국어 기본 locale, debug/release build type, Spotless·Android Lint·Kover·dependency verification·GitHub Actions를 구성한다. 실제 Firebase·외부 API 비밀값은 로컬 환경/CI secret에서만 주입하고 샘플 값이나 작동하지 않는 fallback을 제품 코드에 넣지 않는다.
  Parallelization: Wave 1 | Blocked by: none | Blocks: 2-18
  References (executor has NO interview context - be exhaustive): `docs/초보 식집사_PRD.md:49`; `.omo/drafts/android-app-implementation.md` Decisions; Android app architecture `https://developer.android.com/topic/architecture`; Compose setup `https://developer.android.com/develop/ui/compose/setup`.
  Acceptance criteria (agent-executable): `./gradlew projects spotlessCheck lintDebug testDebugUnitTest assembleDebug`가 exit 0이고 APK의 `minSdkVersion`이 29이며 release artifact에 `plant.id`, `openweathermap`, Firebase private key 문자열이 없다.
  QA scenarios (name the exact tool + invocation): `./gradlew :app:installDebug` 후 `adb shell monkey -p com.planterior.helper 1`로 앱 셸 표시; 잘못된 secret 없이 release task를 실행해 명시적 구성 오류를 확인하고 값 노출이 없음을 검사. Evidence `<attemptDir>/task-1-android-app-implementation.log`.
  Commit: Y | `build(android): 안드로이드 프로젝트와 품질 검사 기반 구성`

- [ ] 2. Figma 디자인 시스템과 앱 내비게이션 구현
  Recommended task executor category: `visual-engineering`
  What to do / Must NOT do: Figma `Page 1`의 402x874 프레임, `#FCFBF7`, `#E5E7EB`, 녹색 primary, 48px 대형 모서리, 타이포·간격·아이콘을 Compose token/component로 구현한다. 홈·도감·카메라·창고·설정 5탭과 typed route, 인증 전후 그래프, cold-start 딥링크 백스택을 만든다. route에는 사용자 ID·사진 bytes·본문을 넣지 말고 불투명 domain ID만 전달한다. Figma에 없는 화면은 동일 token과 컴포넌트만 사용한다.
  Parallelization: Wave 1 | Blocked by: 1 | Blocks: 4-16, 18
  References (executor has NO interview context - be exhaustive): Figma `https://www.figma.com/design/NG1vpxyKmkD5fwgCtkknkM/초보-식집사?node-id=0-1&m=dev`; `docs/초보 식집사_유저플로우.md.md:4-118`; `docs/초보 식집사_PRD.md:34-38`; draft Findings의 Figma 치수·색상.
  Acceptance criteria (agent-executable): Roborazzi golden이 API 29·최신 API에서 light mode 핵심 shell과 일치하고, typed navigation test가 모든 탭·로그인 return route·삭제된 딥링크를 검증하며 `./gradlew :core:designsystem:verifyRoborazziDebug :app:testDebugUnitTest`가 통과한다.
  QA scenarios (name the exact tool + invocation): Compose UI test로 홈→도감→카메라→창고→설정 순회 및 back stack 확인; 유효하지 않은 외부 route를 `adb shell am start -W -a android.intent.action.VIEW ...`로 호출해 홈으로 안전 복귀 확인. Evidence `<attemptDir>/task-2-android-app-implementation.png`.
  Commit: Y | `feat(ui): 피그마 디자인 시스템과 앱 내비게이션 구현`

- [ ] 3. Firebase 데이터 계약과 보안 규칙 구축
  Recommended task executor category: `deep`
  What to do / Must NOT do: Firestore 컬렉션과 typed DTO/mapper를 사용자, 개인 식물, 공개 관리 콘텐츠, 물 주기 기록·일정·설정, 날씨 snapshot·위험, 미니홈피·배치, 아이템·보유, 공유 링크, 동의, 삭제 요청, 알림 delivery로 정의한다. Storage는 임시 식별 원본·사용자 선택 대표 사진·공유 이미지 prefix를 분리한다. Security Rules, indexes, Functions 공통 auth/validation/idempotency/revision/error 계약과 Emulator fixture를 만든다. 앱이 관리자 write를 수행하거나 다른 사용자의 존재 여부를 추측할 수 있게 하지 않는다.
  Parallelization: Wave 1 | Blocked by: 1 | Blocks: 4, 7-17
  References (executor has NO interview context - be exhaustive): `docs/초보 식집사_PRD.md:92-162,334-363,404-519,632-829,852-1115,1137-1554,1768-2054`; 기능명세서 `docs/초보 식집사_기능명세서.png`; Firebase rules `https://firebase.google.com/docs/rules`.
  Acceptance criteria (agent-executable): `firebase emulators:exec --project demo-planterior "npm --prefix functions test && ./gradlew :core:data:testDebugUnitTest"`가 통과하고, rules test가 본인 CRUD 허용·타인 CRUD 거부·공개 콘텐츠 read only·관리자 surface 거부·Storage 경로 격리를 검증한다.
  QA scenarios (name the exact tool + invocation): Emulator에서 사용자 A가 자기 식물 생성·조회 성공; 사용자 B ID로 같은 문서와 파일 접근 시 `PERMISSION_DENIED`이며 응답에 외부 데이터가 없는지 검사. Evidence `<attemptDir>/task-3-android-app-implementation.json`.
  Commit: Y | `feat(db): Firebase 데이터 계약과 보안 규칙 구축`

- [ ] 4. Google·Apple 로그인과 계정 동기화 구현
  Recommended task executor category: `deep`
  What to do / Must NOT do: Credential Manager 기반 Google 로그인과 Android 웹 인증 기반 Sign in with Apple을 Firebase Auth로 통합한다. 로그인 bottom sheet, 공급자 오류, 취소, 세션 복구, 로그아웃, 로그인 후 domain별 동기화, last sync·partial failure, 계정 전환 시 Room 계정 partition 제거를 구현한다. Apple client secret 생성은 Cloud Functions/보안 환경에 두고 앱에 private key를 포함하지 않는다. 사용자 가입 별도 이메일 폼은 만들지 않는다.
  Parallelization: Wave 2 | Blocked by: 2, 3 | Blocks: 5, 8-18
  References (executor has NO interview context - be exhaustive): Figma `google-login-screen`, `apple-login-screen`, `login-bottom-sheet`; `docs/초보 식집사_PRD.md:1782-1858`; `docs/초보 식집사_유저플로우.md.md:4-18`; Firebase Auth `https://firebase.google.com/docs/auth/android/start`.
  Acceptance criteria (agent-executable): Auth emulator 테스트가 Google·Apple 신규/기존 계정 통합, 취소, 공급자 실패, 세션 복원, A→B 전환 cache 격리, partial sync를 검증하고 `firebase emulators:exec --project demo-planterior "./gradlew :feature:auth:connectedDebugAndroidTest"`가 통과한다.
  QA scenarios (name the exact tool + invocation): debug fake provider로 로그인→Home→재실행 세션 복원; 실패 공급자와 네트워크 단절에서 로그인 화면 유지·재시도·타 계정 데이터 미표시 확인. Evidence `<attemptDir>/task-4-android-app-implementation.log`.
  Commit: Y | `feat(auth): 소셜 로그인과 계정 동기화 구현`

- [ ] 5. 홈 대시보드와 부분 실패 상태 구현
  Recommended task executor category: `visual-engineering`
  What to do / Must NOT do: Figma 로그인 전·후 홈을 재현하고 인사말, 미니홈피 preview, 오늘의 관리(오늘→지연→예정 정렬), 날씨 위험 priority, 식별 CTA, 알림 진입을 구현한다. 날씨 실패는 홈 전체 실패가 아닌 Partial, sync 실패는 cached content+last sync로 표시한다. 빈 홈에서 식물·아이템 샘플을 실제 데이터처럼 꾸미지 않는다.
  Parallelization: Wave 2 | Blocked by: 2, 4 | Blocks: 18
  References (executor has NO interview context - be exhaustive): Figma `home-screen-logged-out`, `home-screen-sign-in`, `home-screen`; `docs/초보 식집사_유저플로우.md.md:15-21,120-132`; `docs/초보 식집사_PRD.md:34-38,40-46`.
  Acceptance criteria (agent-executable): ViewModel test가 Empty/Content/Partial/Stale와 오늘→지연→예정 정렬을 고정 시계로 검증하고, Compose screenshot이 로그인 전·빈 상태·콘텐츠·날씨 부분 오류를 통과한다.
  QA scenarios (name the exact tool + invocation): `connectedDebugAndroidTest`로 로그인 홈에서 식별·도감·미니홈피·설정 진입; 날씨 repository만 실패시켜 식물 관리가 유지되는지 확인. Evidence `<attemptDir>/task-5-android-app-implementation.png`.
  Commit: Y | `feat(ui): 오늘의 식물 관리를 보여주는 홈 화면 구현`

- [ ] 6. 카메라·사진 선택과 처리 고지 구현
  Recommended task executor category: `unspecified-high`
  What to do / Must NOT do: CameraX 촬영, Android Photo Picker, 앱 전용 임시 URI, EXIF 회전, 검토·교체·재촬영, 요청별 사진 처리 목적 고지를 구현한다. 카메라 권한은 촬영 선택 시에만 요청하고 거부·영구 거부 시 설정·Photo Picker·직접 등록을 제공한다. 광범위 저장소 권한을 요청하거나 고지 확인 전에 upload하지 않는다.
  Parallelization: Wave 2 | Blocked by: 2 | Blocks: 7, 8
  References (executor has NO interview context - be exhaustive): Figma `plant-capture-flow-board`; `docs/초보 식집사_PRD.md:78-162,1861-1907`; `docs/초보 식집사_유저플로우.md.md:22-39,133-141`; Photo Picker `https://developer.android.com/training/data-storage/shared/photopicker`.
  Acceptance criteria (agent-executable): Camera/Picker contract test가 readable JPEG/PNG/WebP/HEIF, 최대 20MiB·256-8192px, EXIF 회전, 없는 URI, 권한 상태를 검증하며 고지 취소 시 식별 요청·network call이 0건이다.
  QA scenarios (name the exact tool + invocation): API 29·최신 API 에뮬레이터에서 촬영→검토→교체→고지 승인; 영구 거부와 손상 URI에서 대체 CTA와 draft 보존 확인. Evidence `<attemptDir>/task-6-android-app-implementation.png`.
  Commit: Y | `feat(camera): 식물 사진 촬영과 처리 동의 흐름 구현`

- [ ] 7. Plant.id 식별 프록시와 후보 화면 구현
  Recommended task executor category: `deep`
  What to do / Must NOT do: Cloud Function에서 Plant.id API key를 보관하고 임시 Storage 사진을 전송해 Pending/Candidates/NoCandidates/Failed typed 응답으로 변환한다. 상위 3개 후보를 신뢰도 내림차순으로 표시하고 후보 선택 전 확정을 막으며, 낮은 신뢰도는 참고 정보로만 표시한다. double submit은 idempotency key로 한 요청만 만들고 provider 내부 오류를 사용자 진단처럼 노출하지 않는다.
  Parallelization: Wave 2 | Blocked by: 3, 6 | Blocks: 8, 17
  References (executor has NO interview context - be exhaustive): `docs/초보 식집사_PRD.md:165-261`; Figma 식별 로딩·결과 frames; Plant.id `https://www.kindwise.com/plant-id`, API v3 `https://documenter.getpostman.com/view/24599534/2s93z5A4v2`.
  Acceptance criteria (agent-executable): MockWebServer/Functions test가 후보 정렬, top 3, no candidates, timeout, 429, 5xx, 중복 key를 검증하고 `npm --prefix functions test -- plant-identification && ./gradlew :feature:identify:testDebugUnitTest`가 통과한다.
  QA scenarios (name the exact tool + invocation): fixture 사진으로 후보 선택→확정; provider 429와 no candidate에서 재촬영·사진 변경·직접 수정·직접 등록이 모두 동작하고 personal plant가 생성되지 않음을 확인. Evidence `<attemptDir>/task-7-android-app-implementation.json`.
  Commit: Y | `feat(api): Plant.id 식별과 후보 확정 흐름 구현`

- [ ] 8. 직접 입력과 개인 식물 등록 구현
  Recommended task executor category: `deep`
  What to do / Must NOT do: 공개 관리 콘텐츠 검색, 결과 선택, 자유 입력, 식별 결과 수정, 선택적 대표 사진·마지막 물 주기일, 등록 방식, 중복 감지 화면을 구현한다. 동일 콘텐츠 식물은 기존 기록 열기·추가 등록·취소를 제공하고 추가 등록은 새 opaque ID를 만든다. 식물명은 trim 후 1-100 grapheme, 마지막 물 주기일은 계정 timezone 기준 미래 금지이며 저장 실패 시 draft를 보존한다.
  Parallelization: Wave 3 | Blocked by: 3, 4, 6, 7 | Blocks: 9-15, 18
  References (executor has NO interview context - be exhaustive): `docs/초보 식집사_PRD.md:264-363`; `docs/초보 식집사_유저플로우.md.md:40-50,147-152`; Figma 식별 결과·등록 완료 frames.
  Acceptance criteria (agent-executable): TDD가 검색 성공/없음/실패, 공백 이름, 미래 날짜, 식별·수정·직접 입력 방식, duplicate 세 선택지, 멱등 재시도를 검증하고 등록 직후 도감 query에 동일 ID가 한 번 나타난다.
  QA scenarios (name the exact tool + invocation): Firebase Emulator+Compose test로 식별 후보 등록과 검색 실패 자유 입력 등록; network 실패→입력 유지→재시도 후 한 문서만 생성 확인. Evidence `<attemptDir>/task-8-android-app-implementation.log`.
  Commit: Y | `feat(db): 식물 직접 입력과 개인 도감 등록 구현`

- [ ] 9. 개인 도감과 관리 상세 구현
  Recommended task executor category: `visual-engineering`
  What to do / Must NOT do: 도감 Loading/Content/Empty/Error, list position 보존, 식물 상세의 물·빛·온도·습도, 공개 증상별 원인·행동, Partial·Stale·표준 콘텐츠 없음, 마지막 물 주기·위치(50 grapheme)·비공개 메모(1000 grapheme) 편집을 구현한다. Empty에는 사진 식별과 직접 등록 CTA 둘 다 제공하며 비공개 콘텐츠를 fallback으로 노출하지 않는다.
  Parallelization: Wave 3 | Blocked by: 3, 4, 8 | Blocks: 10-15, 18
  References (executor has NO interview context - be exhaustive): `docs/초보 식집사_PRD.md:369-610`; `docs/초보 식집사_유저플로우.md.md:40-55,152-168`; Figma `plant-parent-do-gam-flow`; 기능명세서 도감 2.1-2.3.
  Acceptance criteria (agent-executable): repository/ViewModel/Compose test가 목록 네 상태, scroll 복원, partial humidity, 자유입력 식물, 증상 공개 필터, 편집 실패 draft 보존, 다른 계정 ID Forbidden/NotFound를 검증한다.
  QA scenarios (name the exact tool + invocation): 에뮬레이터에서 빈 도감→직접 등록→상세→메모 저장→뒤로 목록 위치 복원; 상세 진입 전 fixture 삭제 시 도감 CTA 확인. Evidence `<attemptDir>/task-9-android-app-implementation.png`.
  Commit: Y | `feat(ui): 개인 식물 도감과 관리 상세 구현`

- [ ] 10. 물 주기 일정과 완료 기록 구현
  Recommended task executor category: `ultrabrain`
  What to do / Must NOT do: 공개 콘텐츠의 `wateringIntervalDays`(1-365), 마지막 물 주기 `LocalDate`, 계정 `ZoneId`로 다음 날짜를 계산한다. Unavailable/Upcoming/Due/Overdue를 표시하고 완료는 기본 오늘 날짜로 멱등 record를 추가해 마지막 날짜·다음 날짜를 원자적으로 갱신한다. 미래 완료일이나 interval 없는 식물에 임의 기본 일수를 적용하지 않는다.
  Parallelization: Wave 3 | Blocked by: 3, 9 | Blocks: 11, 12, 18
  References (executor has NO interview context - be exhaustive): `docs/초보 식집사_PRD.md:616-698,784-829`; `docs/초보 식집사_유저플로우.md.md:47-55,166-168`; Figma 도감 상세·완료 확인 frames.
  Acceptance criteria (agent-executable): 고정 시계에서 2026-08-01+10일=2026-08-11 Overdue, equality Due, timezone 경계, interval 없음, 동일 idempotency key 두 번 완료가 record 1개임을 TDD로 검증한다.
  QA scenarios (name the exact tool + invocation): Plant detail에서 완료→확인 화면→last/next 날짜 변경; emulator transaction 실패에서 기존 일정 유지와 재시도 확인. Evidence `<attemptDir>/task-10-android-app-implementation.log`.
  Commit: Y | `feat(widget): 물 주기 일정 계산과 완료 기록 구현`

- [ ] 11. 물 주기 알림과 딥링크 구현
  Recommended task executor category: `deep`
  What to do / Must NOT do: 전역 기본 시간, 식물별 enable/time override, Android 13+ 알림 권한, FCM token, Cloud Scheduler/Functions delivery를 구현한다. 예정일 당일 1회와 미완료 시 다음 날 1회만 발송하며 식물+예정일+차수로 중복을 막는다. 탭하면 Home→도감→식물 일정 백스택을 만들고 로그인 필요 시 return route를 복원한다. 권한 거부는 일정·완료를 막지 않는다.
  Parallelization: Wave 3 | Blocked by: 3, 4, 10 | Blocks: 12, 18
  References (executor has NO interview context - be exhaustive): `docs/초보 식집사_PRD.md:701-829`; `docs/초보 식집사_유저플로우.md.md:56-63,169-175`; Figma `plant-care-settings-flow`; FCM `https://firebase.google.com/docs/cloud-messaging/android/client`.
  Acceptance criteria (agent-executable): Functions+WorkManager tests가 당일/다음날/완료 후 중단/disabled/권한 거부/중복 delivery/timezone을 검증하고 cold/warm start route tests가 통과한다.
  QA scenarios (name the exact tool + invocation): debug notification injector로 종료 앱 알림 탭→대상 일정; 삭제된 식물과 로그아웃 상태 알림에서 안전한 NotFound/login resume 확인. Evidence `<attemptDir>/task-11-android-app-implementation.json`.
  Commit: Y | `feat(widget): 물 주기 알림과 관리 화면 연결 구현`

- [ ] 12. OpenWeather 지역·위험·알림 구현
  Recommended task executor category: `deep`
  What to do / Must NOT do: 대략적 현재 위치의 지역 변환 또는 직접 지역 검색을 구현하고 수동 지역을 우선한다. Cloud Function이 OpenWeather의 기온·습도·강수·관측시각을 canonical snapshot으로 저장한다. 3시간 이후 stale, 범위 밖 strict 비교로 고온·저온·건조·과습 복수 위험을 생성하고 stale에서는 신규 push를 금지한다. 전역 weather off가 식물별 on을 우선하며 위험 진입 상태당 한 알림만 보낸다.
  Parallelization: Wave 4 | Blocked by: 3, 4, 9, 10, 11 | Blocks: 18
  References (executor has NO interview context - be exhaustive): `docs/초보 식집사_PRD.md:836-1115,1910-1961`; `docs/초보 식집사_유저플로우.md.md:56-63,89-96`; OpenWeather `https://openweathermap.org/api/one-call-3`.
  Acceptance criteria (agent-executable): tests가 위치 허용/거부/철회, 수동 우선, API timeout·stale, 경계 equality safe, 복수 위험, 관리 콘텐츠 없음, 전역/식물별 알림 precedence와 dedupe를 검증한다.
  QA scenarios (name the exact tool + invocation): fixture weather로 고온+건조 상세와 행동 안내, API 실패에서 timestamped stale 표시·도감 정상 이용, 알림 탭 상세 연결을 확인. Evidence `<attemptDir>/task-12-android-app-implementation.png`.
  Commit: Y | `feat(api): 날씨 기반 식물 위험 안내와 알림 구현`

- [ ] 13. 미니홈피 식물 배치와 저장 구현
  Recommended task executor category: `visual-engineering`
  What to do / Must NOT do: Figma isometric room을 Compose Canvas로 재현하고 등록 식물 picker, 식물당 미니어처, normalized x/y와 z, drag 배치, canvas clamp, 명시적 Save, revision 충돌, unsaved Save/Discard/Cancel을 구현한다. 마지막 서버 확정 구성만 일반 화면에 표시하고 저장 실패를 성공처럼 표시하지 않는다.
  Parallelization: Wave 4 | Blocked by: 3, 4, 8 | Blocks: 14, 15, 18
  References (executor has NO interview context - be exhaustive): Figma `myroom-*`, `home-screen-rename-*`; `docs/초보 식집사_PRD.md:1121-1226`; `docs/초보 식집사_유저플로우.md.md:65-77,176-188`.
  Acceptance criteria (agent-executable): Canvas unit/UI test가 식물 picker, 좌표 clamp, z-order, 재진입 동일 배치, revision conflict draft 보존, app back unsaved dialog를 검증한다.
  QA scenarios (name the exact tool + invocation): 등록 식물 추가→드래그→저장→activity recreate 동일 위치; network 차단 저장 실패에서 committed 구성 유지·draft 재시도 확인. Evidence `<attemptDir>/task-13-android-app-implementation.png`.
  Commit: Y | `feat(ui): 식물 미니어처 배치와 미니홈피 저장 구현`

- [ ] 14. 아이템 창고와 상점 구현
  Recommended task executor category: `deep`
  What to do / Must NOT do: 배경·가구·장식 카테고리, 보유/적용 분리, 공개 상점 item·획득 조건, Success/ConditionNotMet/AlreadyOwned/Failure, atomic ownership을 구현한다. 중복 획득을 차단하고 배경 1개·가구 10개·장식 10개를 적용 한도로 강제하며 해제는 소유권을 보존한다. 결제·재화 적립은 만들지 않고 Firebase fixture의 무료/행동 조건만 해석한다.
  Parallelization: Wave 4 | Blocked by: 3, 13 | Blocks: 15, 18
  References (executor has NO interview context - be exhaustive): Figma `plant-parent-board`; `docs/초보 식집사_PRD.md:1307-1554`; `docs/초보 식집사_유저플로우.md.md:78-88,192-203`.
  Acceptance criteria (agent-executable): emulator transaction tests가 공개 필터, 조건 충족/미충족, 중복 row 0, unowned apply 거부, 1/10/10 한도, remove ownership 보존, warehouse Partial을 검증한다.
  QA scenarios (name the exact tool + invocation): 상점 획득→창고 반영→적용→미니홈피 표시→해제; 상점 backend 실패 중에도 도감·물 주기가 정상임을 확인. Evidence `<attemptDir>/task-14-android-app-implementation.png`.
  Commit: Y | `feat(widget): 미니홈피 아이템 창고와 상점 구현`

- [ ] 15. 미니홈피 이미지와 링크 공유 구현
  Recommended task executor category: `deep`
  What to do / Must NOT do: 마지막 저장 revision을 bitmap으로 렌더링해 FileProvider read-only URI와 Android Sharesheet로 공유한다. 링크는 Cloud Function에서 현재 revision의 개인정보 제거 snapshot과 unguessable token을 만들고 30일 `expiresAt`, revoke 상태를 적용한다. 외부 공유 취소는 앱 실패가 아니며 미저장 draft·개인 메모·사용자 ID를 공유하지 않는다.
  Parallelization: Wave 4 | Blocked by: 3, 13, 14 | Blocks: 18
  References (executor has NO interview context - be exhaustive): `docs/초보 식집사_PRD.md:1229-1301`; `docs/초보 식집사_유저플로우.md.md:76-80,189-191`; Android Sharesheet `https://developer.android.com/training/sharing/send`.
  Acceptance criteria (agent-executable): tests가 saved-only revision, bitmap dimensions, scoped URI, offline link 거부, unique token, 30일 만료, revoked/expired read 거부, snapshot 불변, 개인정보 부재를 검증한다.
  QA scenarios (name the exact tool + invocation): 이미지 preview→Sharesheet 취소/성공; 링크 생성→복사→public fixture 조회→시계 31일 이동 후 404 확인. Evidence `<attemptDir>/task-15-android-app-implementation.json`.
  Commit: Y | `feat(ui): 미니홈피 이미지와 만료 링크 공유 구현`

- [ ] 16. 설정·동의와 계정 삭제 구현
  Recommended task executor category: `deep`
  What to do / Must NOT do: Figma 설정 화면에 OS 알림·위치 상태, 앱별 알림, 지역, last sync, 사진 고지 상태, 계정 삭제를 연결한다. 위치 철회 즉시 현재 위치 요청을 중단한다. 삭제 전 서버 계산 scope와 재인증, 최종 확인, idempotent Received/Processing/Completed/Failed/PartiallyFailed, 7일 유예·취소, 예약된 Functions 삭제, 완료 시 auth·Storage·Firestore·Room·navigation 제거를 구현한다. partial failure를 완료로 표시하지 않는다.
  Parallelization: Wave 5 | Blocked by: 3, 4, 9, 13, 14 | Blocks: 18
  References (executor has NO interview context - be exhaustive): Figma `settings-*`; `docs/초보 식집사_PRD.md:1768-2054`; `docs/초보 식집사_유저플로우.md.md:89-102,204-216`.
  Acceptance criteria (agent-executable): emulator tests가 취소 0건, double submit 1건, 7일 전 복구, 7일 후 전체 scope, partial failure, 완료 후 credential/cache/back stack 제거, 타인 request 거부를 검증한다.
  QA scenarios (name the exact tool + invocation): 설정에서 위치 철회 후 location call 0건; 삭제 요청→유예 취소→재요청→fake clock 7일→완료와 Back private 화면 차단 확인. Evidence `<attemptDir>/task-16-android-app-implementation.json`.
  Commit: Y | `feat(settings): 개인정보 동의와 계정 삭제 흐름 구현`

- [ ] 17. 분석·보안·데이터 수명주기 강화
  Recommended task executor category: `deep`
  What to do / Must NOT do: PRD 핵심 지표의 lifecycle event를 typed allowlist로 구현하고 식별 조회·확정·실패, 등록, 관리 조회, 알림 발송·열기·완료, 위험 조회, 미니홈피 저장·공유, 획득, sync·삭제 결과만 기록한다. 원본 사진·정확한 좌표·메모·auth·공유 token은 제외한다. 분석 임시 원본은 완료/실패 후 24시간 내 삭제하고 사용자가 대표 사진 저장을 선택한 경우 별도 경로만 보존한다. App Check·secret scan·dependency audit·log redaction을 적용한다.
  Parallelization: Wave 5 | Blocked by: 3, 7, 11, 12, 15, 16 | Blocks: 18
  References (executor has NO interview context - be exhaustive): `docs/초보 식집사_PRD.md:37-46,142-162,1861-1907`; draft Decisions 사진 정책; Firebase App Check `https://firebase.google.com/docs/app-check/android/play-integrity-provider`.
  Acceptance criteria (agent-executable): analytics schema test와 log snapshot에 금지 필드가 없고, fake clock 23:59에는 임시 원본 존재·24:00 이후 삭제·대표 사진 보존, `gitleaks detect --no-banner`, dependency audit, App Check emulator tests가 통과한다.
  QA scenarios (name the exact tool + invocation): 식별→등록→물 주기→공유 journey event export를 검사하고 원본 URI·좌표·note·token regex 0건; cleanup 실패 후 재시도와 사용자 기능 비차단 확인. Evidence `<attemptDir>/task-17-android-app-implementation.json`.
  Commit: Y | `feat(collector): 개인정보 보호 분석과 사진 수명주기 적용`

- [ ] 18. 전체 E2E와 Google Play 출시 준비 완료
  Recommended task executor category: `unspecified-high`
  What to do / Must NOT do: 모든 feature를 `app`에 조립하고 Splash/auth/home graph, Firebase config variant, ProGuard/R8, adaptive icon, 한국어 문자열, 접근성 contentDescription·touch target·font scale, 네트워크 보안, Play privacy/data safety 초안, release signing 환경 계약을 완성한다. API 29와 최신 stable API에서 핵심 happy/failure journey를 실행하고 성능 baseline profile을 생성한다. 테스트 계정·서명키·비밀값을 저장소에 커밋하지 않는다.
  Parallelization: Wave 5 final | Blocked by: 2-17 | Blocks: F1-F4
  References (executor has NO interview context - be exhaustive): 전체 `docs/초보 식집사_PRD.md`, `docs/초보 식집사_유저플로우.md.md`, `docs/초보 식집사_기능명세서.png`, Figma 전체 `Page 1`; Play launch checklist `https://developer.android.com/distribute/best-practices/launch/launch-checklist`.
  Acceptance criteria (agent-executable): `./gradlew clean spotlessCheck lintDebug testDebugUnitTest connectedDebugAndroidTest bundleRelease :app:lintVitalRelease`와 Firebase Functions/rules tests가 exit 0, API 29·최신 API screenshot diff 승인, release bundle secret scan 0건, baseline profile 생성 성공.
  QA scenarios (name the exact tool + invocation): Maestro/Compose E2E로 신규 로그인→사진 식별→등록→도감→물 주기→날씨 위험→미니홈피→아이템→공유→설정→삭제 요청 happy path; 권한 전체 거부·offline·API 429/500·sync conflict·삭제 partial failure path를 별도 실행. Evidence `<attemptDir>/task-18-android-app-implementation.log`.
  Commit: Y | `build(android): 안드로이드 앱 출시 검증과 패키징 완료`

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [ ] F1. Plan compliance audit
  Verify every Must have, documented acceptance criterion, approved policy, todo evidence, and commit rule against the final diff. Run `./gradlew projects` and map every requirement to a module/test/evidence file; reject missing or unverifiable claims.
- [ ] F2. Code quality and security review
  Run `./gradlew spotlessCheck lintDebug testDebugUnitTest :app:lintVitalRelease`, Firebase Rules/Functions tests, `gitleaks detect --no-banner`, dependency audit, and inspect auth ownership, secret boundaries, idempotency, revision conflict, photo deletion, link expiry, account deletion. Any error/warning or suppression fails approval.
- [ ] F3. Real manual QA
  Install release-like builds on API 29 and latest stable API emulators and execute happy path, camera/photo/notification/location denial, offline/stale, Plant.id/OpenWeather failure, deleted deep link, sync conflict, unsaved mini-home, expired share link, deletion partial failure. Capture screenshots/logs and require behavior—not source reading—to match PRD/Figma.
- [ ] F4. Scope fidelity
  Compare final changed files with Must NOT have and commit history. Reject web admin UI, payments, ads, social feed, model training, secret commits, Figma edits, unrelated cleanup, unapproved dependency or behavior expansion.

## Commit strategy
- 사용자에게 커밋 실행을 별도로 승인받은 경우에만 각 todo의 검증 완료 후 해당 todo에 적힌 커밋을 만든다.
- 형식은 `<type>(<scope>): <한국어 요약>`, 첫 줄 72자 이내이며 실제 staged diff만 설명한다.
- AI·Codex·ChatGPT·자동 생성, 브랜치명·작업명·이슈명 복사를 금지한다.
- 서로 무관한 변경은 분리하며, commit 전 `git diff --cached`로 제목·본문의 모든 문장을 대조한다.
- 각 commit은 해당 todo의 정적 검사와 관련 테스트가 green인 독립 증분이어야 한다. 최종 todo는 앞선 feature diff를 묶어 다시 커밋하지 않는다.

## Success criteria
- PRD 일반 사용자 모바일 기능 1-6·8과 Mermaid 모바일 경로가 누락 없이 동작한다.
- Figma 제공 화면은 API 29·최신 API screenshot 검증을 통과하고 누락 화면은 같은 token/component를 사용한다.
- Google·Apple 로그인, 계정 격리, sync partial/conflict, Firebase Rules ownership tests가 통과한다.
- Plant.id·OpenWeather 키가 앱/저장소에 없고 실패·rate limit·stale가 핵심 기능을 차단하지 않는다.
- 물 주기 계산·알림 중복·날씨 위험 경계가 고정 시계 deterministic test로 검증된다.
- 미니홈피 저장 실패·revision 충돌·아이템 1/10/10 한도·30일 링크 만료가 검증된다.
- 사진 원본 24시간 삭제, 대표 사진 분리, 7일 삭제 유예와 partial failure가 검증된다.
- API 29와 최신 API에서 happy/failure E2E, 접근성, release bundle, secret scan이 모두 clean이다.
- 모든 증거가 `<attemptDir>`에 남고 F1-F4가 독립 승인한다.
