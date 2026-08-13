package com.planterior.helper.feature.home

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 홈 대시보드 상태 기계를 고정 시계로 검증한다.
 *
 * 시계는 계획서가 정한 `2026-08-12T09:00:00+09:00`(`Asia/Seoul`)로 고정한다. 시간 흐름이나 sleep에 기대지 않고 모든 단언은 주입한
 * repository 결과와 이 시계만으로 결정된다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 8, 12)
    private val clock = Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), zone)

    /**
     * `viewModelScope`가 쓰는 Main 디스패처를 테스트 디스패처로 바꾼다.
     *
     * `UnconfinedTestDispatcher`는 `refresh()`가 반환되는 시점에 상태를 이미 확정해 둔다. 덕분에 sleep이나 폴링 없이 바로 단언할 수
     * 있다.
     */
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun removeMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun plant(
        id: String,
        name: String,
        due: LocalDate?,
        interval: Int? = 7,
        zone: ZoneId = this.zone,
    ): HomePlantCare =
        HomePlantCare(
            plantId = id,
            displayName = name,
            nextWateringDate = due,
            wateringIntervalDays = interval,
            zoneId = zone,
        )

    private fun viewModel(
        session: HomeSession = HomeSession.SignedIn("uid-1", "민지", zone),
        care: Result<List<HomePlantCare>> = Result.success(emptyList()),
        weather: Result<HomeWeather?> = Result.success(null),
        miniHome: HomeMiniHomePreview? = null,
        sync: HomeSyncStatus = HomeSyncStatus.Synced(Instant.parse("2026-08-12T00:00:00Z")),
    ) =
        HomeViewModel(
            repository =
                FakeHomeRepository(
                    session = session,
                    care = care,
                    weather = weather,
                    miniHome = miniHome,
                    sync = sync,
                ),
            clock = clock,
            dispatcher = mainDispatcher,
        )

    /** 세션 흐름만 주입해 상태 전이를 관찰하는 ViewModel을 만든다. */
    private fun sessionModel(sessions: Flow<HomeSession>) =
        HomeViewModel(
            repository = SessionFlowRepository(sessions, listOf(plant("p-today", "오늘이", today))),
            clock = clock,
            dispatcher = mainDispatcher,
        )

    @Test
    fun `a restoring session shows loading and never latches logged out`() = runTest {
        val sessions = MutableStateFlow<HomeSession>(HomeSession.Restoring)
        val model = sessionModel(sessions)

        testScheduler.advanceUntilIdle()

        assertEquals(
            "세션을 복원하는 동안은 로그아웃으로 단정하면 안 된다",
            HomeUiState.Loading,
            model.state.value,
        )
    }

    @Test
    fun `restoring then authenticated reaches content without any manual refresh`() = runTest {
        val sessions = MutableStateFlow<HomeSession>(HomeSession.Restoring)
        val model = sessionModel(sessions)
        testScheduler.advanceUntilIdle()
        assertEquals(HomeUiState.Loading, model.state.value)

        sessions.value = HomeSession.SignedIn("uid-1", "민지", zone)
        testScheduler.advanceUntilIdle()

        assertTrue(
            "세션이 서면 수동 갱신 없이도 홈이 채워져야 한다: ${model.state.value}",
            model.state.value is HomeUiState.Content,
        )
    }

    @Test
    fun `restoring then signed out reaches the logged out home`() = runTest {
        val sessions = MutableStateFlow<HomeSession>(HomeSession.Restoring)
        val model = sessionModel(sessions)
        testScheduler.advanceUntilIdle()

        sessions.value = HomeSession.SignedOut
        testScheduler.advanceUntilIdle()

        assertEquals(HomeUiState.LoggedOut, model.state.value)
    }

    @Test
    fun `switching account A to B to A reloads each session in order`() = runTest {
        val sessions = MutableStateFlow<HomeSession>(HomeSession.SignedIn("uid-a", "A", zone))
        val perAccount =
            mapOf(
                "uid-a" to listOf(plant("p-a", "A 식물", today)),
                "uid-b" to listOf(plant("p-b", "B 식물", today)),
            )
        val model =
            HomeViewModel(
                repository = PerAccountRepository(sessions, perAccount),
                clock = clock,
                dispatcher = mainDispatcher,
            )
        testScheduler.advanceUntilIdle()
        assertEquals(
            listOf("p-a"),
            (model.state.value as HomeUiState.Content).careItems.map { it.plantId },
        )

        sessions.value = HomeSession.SignedIn("uid-b", "B", zone)
        testScheduler.advanceUntilIdle()
        assertEquals(
            listOf("p-b"),
            (model.state.value as HomeUiState.Content).careItems.map { it.plantId },
        )

        sessions.value = HomeSession.SignedIn("uid-a", "A", zone)
        testScheduler.advanceUntilIdle()
        assertEquals(
            listOf("p-a"),
            (model.state.value as HomeUiState.Content).careItems.map { it.plantId },
        )
    }

    @Test
    fun `a slow load for a stale session never overwrites the newer session`() = runTest {
        val sessions = MutableStateFlow<HomeSession>(HomeSession.SignedIn("uid-a", "A", zone))
        val gate = CompletableDeferred<Unit>()
        val repository = GatedRepository(sessions, gate)
        val model =
            HomeViewModel(repository = repository, clock = clock, dispatcher = mainDispatcher)
        testScheduler.advanceUntilIdle()

        // A 계정 조회가 멈춰 있는 사이 B로 전환한다.
        sessions.value = HomeSession.SignedIn("uid-b", "B", zone)
        testScheduler.advanceUntilIdle()
        // 늦게 끝난 A 조회 결과가 도착해도 B 화면을 덮어쓰면 안 된다.
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        val content = model.state.value as HomeUiState.Content
        assertEquals("B", content.greetingName)
        assertEquals(listOf("p-uid-b"), content.careItems.map { it.plantId })
        // A 조회는 끝까지 가지 못하고 취소되어야 한다.
        assertEquals("취소된 조회가 완료되면 안 된다", 0, repository.completedStaleLoads)
    }

    @Test
    fun `logged out session renders the guest home without any plant data`() = runTest {
        val model = viewModel(session = HomeSession.SignedOut)

        model.refresh()

        val state = model.state.value
        assertTrue("로그아웃 상태여야 한다: $state", state is HomeUiState.LoggedOut)
    }

    @Test
    fun `signed in account with no plants renders Empty and never fabricates sample plants`() =
        runTest {
            val model = viewModel(care = Result.success(emptyList()))

            model.refresh()

            val state = model.state.value
            assertTrue("빈 상태여야 한다: $state", state is HomeUiState.Empty)
            assertEquals("민지", (state as HomeUiState.Empty).greetingName)
        }

    @Test
    fun `care items are ordered today then overdue then upcoming`() = runTest {
        val model =
            viewModel(
                care =
                    Result.success(
                        listOf(
                            plant("p-upcoming", "예정이", today.plusDays(3)),
                            plant("p-overdue", "지연이", today.minusDays(2)),
                            plant("p-today", "오늘이", today),
                        )
                    )
            )

        model.refresh()

        val content = model.state.value as HomeUiState.Content
        assertEquals(
            listOf("p-today", "p-overdue", "p-upcoming"),
            content.careItems.map { it.plantId },
        )
        assertEquals(
            listOf(
                HomeCareStatus.DueToday,
                HomeCareStatus.Overdue(2),
                HomeCareStatus.Upcoming(3),
            ),
            content.careItems.map { it.status },
        )
    }

    @Test
    fun `equal due dates keep a deterministic order by plant id`() = runTest {
        val model =
            viewModel(
                care =
                    Result.success(
                        listOf(
                            plant("p-b", "비", today.minusDays(1)),
                            plant("p-a", "에이", today.minusDays(1)),
                            plant("p-c", "씨", today.minusDays(1)),
                        )
                    )
            )

        model.refresh()

        val content = model.state.value as HomeUiState.Content
        assertEquals(listOf("p-a", "p-b", "p-c"), content.careItems.map { it.plantId })
    }

    @Test
    fun `plants without a watering interval are listed last as unavailable`() = runTest {
        val model =
            viewModel(
                care =
                    Result.success(
                        listOf(
                            plant("p-unknown", "정보없음", due = null, interval = null),
                            plant("p-today", "오늘이", today),
                        )
                    )
            )

        model.refresh()

        val content = model.state.value as HomeUiState.Content
        assertEquals(listOf("p-today", "p-unknown"), content.careItems.map { it.plantId })
        assertEquals(HomeCareStatus.Unavailable, content.careItems.last().status)
    }

    @Test
    fun `weather failure degrades to Partial and keeps every plant care item`() = runTest {
        val model =
            viewModel(
                care = Result.success(listOf(plant("p-today", "오늘이", today))),
                weather = Result.failure(IllegalStateException("provider down")),
            )

        model.refresh()

        val content = model.state.value as HomeUiState.Content
        assertEquals(HomeWeatherState.Unavailable, content.weather)
        assertTrue("날씨 실패는 부분 저하여야 한다", content.isPartial)
        assertEquals(listOf("p-today"), content.careItems.map { it.plantId })
    }

    @Test
    fun `stale synchronization keeps cached content and reports the last sync instant`() = runTest {
        val lastSync = Instant.parse("2026-08-10T00:00:00Z")
        val model =
            viewModel(
                care = Result.success(listOf(plant("p-today", "오늘이", today))),
                sync = HomeSyncStatus.Stale(lastSync),
            )

        model.refresh()

        val content = model.state.value as HomeUiState.Content
        assertEquals(HomeSyncState.Stale(lastSync), content.sync)
        assertEquals(listOf("p-today"), content.careItems.map { it.plantId })
    }

    @Test
    fun `an empty cache with a stale sync still renders Empty rather than fake content`() =
        runTest {
            val model =
                viewModel(
                    care = Result.success(emptyList()),
                    sync = HomeSyncStatus.Stale(Instant.parse("2026-08-10T00:00:00Z")),
                )

            model.refresh()

            val state = model.state.value
            assertTrue("빈 캐시는 Empty여야 한다: $state", state is HomeUiState.Empty)
            assertEquals(
                HomeSyncState.Stale(Instant.parse("2026-08-10T00:00:00Z")),
                (state as HomeUiState.Empty).sync,
            )
        }

    @Test
    fun `the highest priority weather risk is the one shown on home`() = runTest {
        val model =
            viewModel(
                care = Result.success(listOf(plant("p-today", "오늘이", today))),
                weather =
                    Result.success(
                        HomeWeather(
                            regionName = "서울 성동구",
                            temperatureCelsius = 35.0,
                            observedAt = Instant.parse("2026-08-12T00:00:00Z"),
                            risks =
                                listOf(
                                    HomeWeatherRisk.Dry("건조 안내"),
                                    HomeWeatherRisk.HighTemperature("고온 안내"),
                                ),
                        )
                    ),
            )

        model.refresh()

        val content = model.state.value as HomeUiState.Content
        val weather = content.weather as HomeWeatherState.Available
        assertEquals(HomeWeatherRisk.HighTemperature("고온 안내"), weather.topRisk)
    }

    @Test
    fun `no configured region is not a weather failure`() = runTest {
        val model =
            viewModel(
                care = Result.success(listOf(plant("p-today", "오늘이", today))),
                weather = Result.success(null),
            )

        model.refresh()

        val content = model.state.value as HomeUiState.Content
        assertEquals(HomeWeatherState.NotConfigured, content.weather)
        assertTrue("지역 미설정은 부분 저하가 아니다", !content.isPartial)
    }

    @Test
    fun `weather without any risk shows no risk banner`() = runTest {
        val model =
            viewModel(
                care = Result.success(listOf(plant("p-today", "오늘이", today))),
                weather =
                    Result.success(
                        HomeWeather(
                            regionName = "서울 성동구",
                            temperatureCelsius = 21.0,
                            observedAt = Instant.parse("2026-08-12T00:00:00Z"),
                            risks = emptyList(),
                        )
                    ),
            )

        model.refresh()

        val content = model.state.value as HomeUiState.Content
        assertNull((content.weather as HomeWeatherState.Available).topRisk)
    }

    @Test
    fun `care repository failure with weather intact still surfaces the weather`() = runTest {
        val model =
            viewModel(
                care = Result.failure(IllegalStateException("cache unavailable")),
                weather =
                    Result.success(
                        HomeWeather(
                            regionName = "서울 성동구",
                            temperatureCelsius = 21.0,
                            observedAt = Instant.parse("2026-08-12T00:00:00Z"),
                            risks = emptyList(),
                        )
                    ),
            )

        model.refresh()

        val state = model.state.value
        assertTrue("식물 관리 실패는 Empty가 아니라 오류여야 한다: $state", state is HomeUiState.Error)
    }

    @Test
    fun `today wins over an overdue item even when the overdue item is more urgent`() = runTest {
        val model =
            viewModel(
                care =
                    Result.success(
                        listOf(
                            plant("p-very-overdue", "많이지연", today.minusDays(30)),
                            plant("p-today", "오늘이", today),
                        )
                    )
            )

        model.refresh()

        val content = model.state.value as HomeUiState.Content
        assertEquals(listOf("p-today", "p-very-overdue"), content.careItems.map { it.plantId })
    }

    @Test
    fun `overdue items are ordered by how long they have been overdue`() = runTest {
        val model =
            viewModel(
                care =
                    Result.success(
                        listOf(
                            plant("p-1", "하루", today.minusDays(1)),
                            plant("p-9", "아홉", today.minusDays(9)),
                            plant("p-4", "넷", today.minusDays(4)),
                        )
                    )
            )

        model.refresh()

        val content = model.state.value as HomeUiState.Content
        assertEquals(listOf("p-9", "p-4", "p-1"), content.careItems.map { it.plantId })
    }

    @Test
    fun `upcoming items are ordered by the nearest due date first`() = runTest {
        val model =
            viewModel(
                care =
                    Result.success(
                        listOf(
                            plant("p-5", "닷새", today.plusDays(5)),
                            plant("p-1", "하루", today.plusDays(1)),
                            plant("p-3", "사흘", today.plusDays(3)),
                        )
                    )
            )

        model.refresh()

        val content = model.state.value as HomeUiState.Content
        assertEquals(listOf("p-1", "p-3", "p-5"), content.careItems.map { it.plantId })
    }

    @Test
    fun `the today count only counts items due today`() = runTest {
        val model =
            viewModel(
                care =
                    Result.success(
                        listOf(
                            plant("p-a", "오늘1", today),
                            plant("p-b", "오늘2", today),
                            plant("p-c", "지연", today.minusDays(1)),
                            plant("p-d", "예정", today.plusDays(1)),
                        )
                    )
            )

        model.refresh()

        val content = model.state.value as HomeUiState.Content
        assertEquals(2, content.dueTodayCount)
    }

    @Test
    fun `both weather and synchronization failing keeps plant care usable`() = runTest {
        val staleAt = Instant.parse("2026-08-09T00:00:00Z")
        val model =
            viewModel(
                care = Result.success(listOf(plant("p-today", "오늘이", today))),
                weather = Result.failure(IllegalStateException("provider down")),
                sync = HomeSyncStatus.Stale(staleAt),
            )

        model.refresh()

        val content = model.state.value as HomeUiState.Content
        assertEquals(HomeWeatherState.Unavailable, content.weather)
        assertEquals(HomeSyncState.Stale(staleAt), content.sync)
        assertEquals(listOf("p-today"), content.careItems.map { it.plantId })
    }

    @Test
    fun `the mini home preview only reflects a committed configuration`() = runTest {
        val preview = HomeMiniHomePreview(title = "민지의 미니 식물원", placedPlantCount = 3)
        val model =
            viewModel(
                care = Result.success(listOf(plant("p-today", "오늘이", today))),
                miniHome = preview,
            )

        model.refresh()

        val content = model.state.value as HomeUiState.Content
        assertEquals(preview, content.miniHome)
    }

    @Test
    fun `each schedule is classified at its own local date boundary`() = runTest {
        // 고정 시각 2026-08-12T15:30Z = 서울 8월13일 00:30, LA 8월12일 08:30, UTC 8월12일 15:30.
        val instant = Instant.parse("2026-08-12T15:30:00Z")
        val model =
            HomeViewModel(
                repository =
                    FakeHomeRepository(
                        session = HomeSession.SignedIn("uid-1", "민지", ZoneId.of("UTC")),
                        care =
                            Result.success(
                                listOf(
                                    plant(
                                        "p-seoul",
                                        "서울",
                                        LocalDate.of(2026, 8, 13),
                                        zone = ZoneId.of("Asia/Seoul"),
                                    ),
                                    plant(
                                        "p-utc",
                                        "UTC",
                                        LocalDate.of(2026, 8, 12),
                                        zone = ZoneId.of("UTC"),
                                    ),
                                    plant(
                                        "p-la",
                                        "LA",
                                        LocalDate.of(2026, 8, 12),
                                        zone = ZoneId.of("America/Los_Angeles"),
                                    ),
                                )
                            ),
                        weather = Result.success(null),
                        miniHome = null,
                        sync = HomeSyncStatus.Synced(instant),
                    ),
                clock = Clock.fixed(instant, ZoneId.of("UTC")),
                dispatcher = mainDispatcher,
            )

        model.refresh()

        val content = model.state.value as HomeUiState.Content
        val byId = content.careItems.associateBy { it.plantId }
        // 세 식물 모두 자기 지역 기준으로는 “오늘”이다. 계정 지역 하나로 묶으면 이 중 둘은 어깃난다.
        assertEquals(HomeCareStatus.DueToday, byId.getValue("p-seoul").status)
        assertEquals(HomeCareStatus.DueToday, byId.getValue("p-utc").status)
        assertEquals(HomeCareStatus.DueToday, byId.getValue("p-la").status)
        assertEquals(3, content.dueTodayCount)
    }

    @Test
    fun `a schedule just past midnight in its own zone counts as overdue`() = runTest {
        // 서울은 이미 8월13일 00:30이므로 8월12일 예정은 하루 지났고, LA는 아직 8월12일이라 오늘이다.
        val instant = Instant.parse("2026-08-12T15:30:00Z")
        val model =
            HomeViewModel(
                repository =
                    FakeHomeRepository(
                        session = HomeSession.SignedIn("uid-1", "민지", ZoneId.of("UTC")),
                        care =
                            Result.success(
                                listOf(
                                    plant(
                                        "p-seoul",
                                        "서울",
                                        LocalDate.of(2026, 8, 12),
                                        zone = ZoneId.of("Asia/Seoul"),
                                    ),
                                    plant(
                                        "p-la",
                                        "LA",
                                        LocalDate.of(2026, 8, 12),
                                        zone = ZoneId.of("America/Los_Angeles"),
                                    ),
                                )
                            ),
                        weather = Result.success(null),
                        miniHome = null,
                        sync = HomeSyncStatus.Synced(instant),
                    ),
                clock = Clock.fixed(instant, ZoneId.of("UTC")),
                dispatcher = mainDispatcher,
            )

        model.refresh()

        val content = model.state.value as HomeUiState.Content
        val byId = content.careItems.associateBy { it.plantId }
        assertEquals(HomeCareStatus.Overdue(1), byId.getValue("p-seoul").status)
        assertEquals(HomeCareStatus.DueToday, byId.getValue("p-la").status)
        // 오늘이 먼저, 그 다음 지연이다.
        assertEquals(listOf("p-la", "p-seoul"), content.careItems.map { it.plantId })
    }

    @Test
    fun `a daylight saving transition day is still one local day`() = runTest {
        // 2026-11-01 LA 서머타임 해제일(25시간). 그날 09:30Z = LA 02:30 로 여전히 11월1일이다.
        val instant = Instant.parse("2026-11-01T09:30:00Z")
        val model =
            HomeViewModel(
                repository =
                    FakeHomeRepository(
                        session = HomeSession.SignedIn("uid-1", "민지", ZoneId.of("UTC")),
                        care =
                            Result.success(
                                listOf(
                                    plant(
                                        "p-la",
                                        "LA",
                                        LocalDate.of(2026, 11, 1),
                                        zone = ZoneId.of("America/Los_Angeles"),
                                    ),
                                    plant(
                                        "p-la-next",
                                        "LA 다음날",
                                        LocalDate.of(2026, 11, 2),
                                        zone = ZoneId.of("America/Los_Angeles"),
                                    ),
                                )
                            ),
                        weather = Result.success(null),
                        miniHome = null,
                        sync = HomeSyncStatus.Synced(instant),
                    ),
                clock = Clock.fixed(instant, ZoneId.of("UTC")),
                dispatcher = mainDispatcher,
            )

        model.refresh()

        val content = model.state.value as HomeUiState.Content
        val byId = content.careItems.associateBy { it.plantId }
        assertEquals(HomeCareStatus.DueToday, byId.getValue("p-la").status)
        assertEquals(HomeCareStatus.Upcoming(1), byId.getValue("p-la-next").status)
    }

    @Test
    fun `identical due dates in the same zone keep the deterministic id order`() = runTest {
        val instant = Instant.parse("2026-08-12T15:30:00Z")
        val model =
            HomeViewModel(
                repository =
                    FakeHomeRepository(
                        session = HomeSession.SignedIn("uid-1", "민지", ZoneId.of("UTC")),
                        care =
                            Result.success(
                                listOf(
                                    plant("p-c", "C", LocalDate.of(2026, 8, 13), zone = zone),
                                    plant("p-a", "A", LocalDate.of(2026, 8, 13), zone = zone),
                                    plant("p-b", "B", LocalDate.of(2026, 8, 13), zone = zone),
                                )
                            ),
                        weather = Result.success(null),
                        miniHome = null,
                        sync = HomeSyncStatus.Synced(instant),
                    ),
                clock = Clock.fixed(instant, ZoneId.of("UTC")),
                dispatcher = mainDispatcher,
            )

        model.refresh()

        val content = model.state.value as HomeUiState.Content
        assertEquals(listOf("p-a", "p-b", "p-c"), content.careItems.map { it.plantId })
    }

    @Test
    fun `state is produced without depending on wall clock progress`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val model =
            HomeViewModel(
                repository =
                    FakeHomeRepository(
                        session = HomeSession.SignedIn("uid-1", "민지", zone),
                        care = Result.success(listOf(plant("p-today", "오늘이", today))),
                        weather = Result.success(null),
                        miniHome = null,
                        sync = HomeSyncStatus.Synced(Instant.parse("2026-08-12T00:00:00Z")),
                    ),
                clock = clock,
                dispatcher = dispatcher,
            )

        model.refresh()
        testScheduler.advanceUntilIdle()

        assertTrue(model.state.value is HomeUiState.Content)
    }
}

/** 세션 흐름만 바꾸면서 나머지는 고정해 두는 저장소이다. */
private class SessionFlowRepository(
    private val sessions: Flow<HomeSession>,
    private val care: List<HomePlantCare>,
) : HomeRepository {
    override fun sessions(): Flow<HomeSession> = sessions

    override suspend fun plantCare(): Result<List<HomePlantCare>> = Result.success(care)

    override suspend fun weather(): Result<HomeWeather?> = Result.success(null)

    override suspend fun miniHomePreview(): HomeMiniHomePreview? = null

    override suspend fun syncStatus(): HomeSyncStatus =
        HomeSyncStatus.Synced(Instant.parse("2026-08-12T00:00:00Z"))
}

/** 계정마다 다른 식물을 돌려줘 계정 전환이 실제로 다시 읽는지 확인한다. */
private class PerAccountRepository(
    private val sessions: MutableStateFlow<HomeSession>,
    private val perAccount: Map<String, List<HomePlantCare>>,
) : HomeRepository {
    override fun sessions(): Flow<HomeSession> = sessions

    override suspend fun plantCare(): Result<List<HomePlantCare>> {
        val uid = (sessions.value as? HomeSession.SignedIn)?.accountUid
        return Result.success(perAccount[uid].orEmpty())
    }

    override suspend fun weather(): Result<HomeWeather?> = Result.success(null)

    override suspend fun miniHomePreview(): HomeMiniHomePreview? = null

    override suspend fun syncStatus(): HomeSyncStatus =
        HomeSyncStatus.Synced(Instant.parse("2026-08-12T00:00:00Z"))
}

/**
 * 첫 계정 조회를 gate로 멈춰 늦게 끝난 결과가 최신 세션을 덮는지 확인한다.
 *
 * 조회를 시작한 시점의 계정을 그대로 들고 있다가 돌려준다. 실제 저장소도 요청 시점 계정으로 질의하므로, 취소하지 않으면 늦게 끝난 A 결과가 B 화면을 덮어쓴다.
 */
private class GatedRepository(
    private val sessions: MutableStateFlow<HomeSession>,
    private val gate: CompletableDeferred<Unit>,
) : HomeRepository {
    /** gate 뒤까지 살아남은 예전 계정 조회 수. 취소가 제대로 동작하면 0이어야 한다. */
    var completedStaleLoads: Int = 0
        private set

    override fun sessions(): Flow<HomeSession> = sessions

    override suspend fun plantCare(): Result<List<HomePlantCare>> {
        // 조회를 시작한 시점의 계정을 고정한다. 멈춰 있는 동안 계정이 바뀌어도 결과는 예전 계정 것이 된다.
        val uid = (sessions.value as? HomeSession.SignedIn)?.accountUid.orEmpty()
        if (uid == "uid-a") {
            gate.await()
            completedStaleLoads += 1
        }
        return Result.success(
            listOf(
                HomePlantCare(
                    "p-$uid",
                    uid,
                    LocalDate.of(2026, 8, 12),
                    7,
                    ZoneId.of("Asia/Seoul"),
                )
            )
        )
    }

    override suspend fun weather(): Result<HomeWeather?> = Result.success(null)

    override suspend fun miniHomePreview(): HomeMiniHomePreview? = null

    override suspend fun syncStatus(): HomeSyncStatus =
        HomeSyncStatus.Synced(Instant.parse("2026-08-12T00:00:00Z"))
}

private class FakeHomeRepository(
    private val session: HomeSession,
    private val care: Result<List<HomePlantCare>>,
    private val weather: Result<HomeWeather?>,
    private val miniHome: HomeMiniHomePreview?,
    private val sync: HomeSyncStatus,
) : HomeRepository {
    override fun sessions(): Flow<HomeSession> = MutableStateFlow(session)

    override suspend fun plantCare(): Result<List<HomePlantCare>> = care

    override suspend fun weather(): Result<HomeWeather?> = weather

    override suspend fun miniHomePreview(): HomeMiniHomePreview? = miniHome

    override suspend fun syncStatus(): HomeSyncStatus = sync
}
