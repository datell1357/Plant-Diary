package com.planterior.helper.feature.home

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    ): HomePlantCare =
        HomePlantCare(
            plantId = id,
            displayName = name,
            nextWateringDate = due,
            wateringIntervalDays = interval,
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

private class FakeHomeRepository(
    private val session: HomeSession,
    private val care: Result<List<HomePlantCare>>,
    private val weather: Result<HomeWeather?>,
    private val miniHome: HomeMiniHomePreview?,
    private val sync: HomeSyncStatus,
) : HomeRepository {
    override suspend fun session(): HomeSession = session

    override suspend fun plantCare(): Result<List<HomePlantCare>> = care

    override suspend fun weather(): Result<HomeWeather?> = weather

    override suspend fun miniHomePreview(): HomeMiniHomePreview? = miniHome

    override suspend fun syncStatus(): HomeSyncStatus = sync
}
