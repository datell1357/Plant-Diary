package com.planterior.helper.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 홈 대시보드의 상태를 만든다.
 *
 * 날짜 판단은 주입한 [clock]으로만 하며 `LocalDate.now()` 같은 암묵적 시계를 쓰지 않는다. 덕분에 테스트는 고정 시계 하나로 오늘·지연·예정 경계를 모두
 * 결정할 수 있다.
 *
 * @param repository 세션·식물 관리·날씨·동기화를 각각 독립적으로 제공하는 원본.
 * @param clock 오늘 날짜의 기준. 계정 시간대는 세션에서 가져온다.
 * @param dispatcher 저장소 호출을 실행할 컨텍스트.
 */
class HomeViewModel(
    private val repository: HomeRepository,
    private val clock: Clock,
    private val dispatcher: CoroutineContext = Dispatchers.Main.immediate,
) : ViewModel() {
    private val mutableState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)

    /** 화면이 구독하는 홈 상태. */
    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()

    /** 홈을 다시 불러온다. 화면 진입과 사용자의 새로고침 모두 이 경로를 쓴다. */
    fun refresh() {
        viewModelScope.launch { mutableState.value = withContext(dispatcher) { load() } }
    }

    private suspend fun load(): HomeUiState {
        val session = repository.session()
        if (session !is HomeSession.SignedIn) return HomeUiState.LoggedOut

        val sync = repository.syncStatus().toUiState()
        val care =
            repository.plantCare().getOrElse {
                return HomeUiState.Error(sync)
            }
        val weather = repository.weather().toUiState()

        if (care.isEmpty()) {
            return HomeUiState.Empty(
                greetingName = session.displayName,
                weather = weather,
                sync = sync,
            )
        }

        val today = LocalDate.now(clock.withZone(session.zoneId))
        val items = care.map { it.toItem(today) }.sortedWith(CareOrder)
        return HomeUiState.Content(
            greetingName = session.displayName,
            careItems = items,
            dueTodayCount = items.count { it.status == HomeCareStatus.DueToday },
            miniHome = repository.miniHomePreview(),
            weather = weather,
            sync = sync,
        )
    }

    private fun HomeSyncStatus.toUiState(): HomeSyncState =
        when (this) {
            is HomeSyncStatus.Synced -> HomeSyncState.Fresh
            is HomeSyncStatus.Stale -> HomeSyncState.Stale(lastSuccessfulAt)
        }

    /** 날씨 실패는 홈 전체 실패가 아니라 부분 저하이다. 예외를 삼키지 않고 상태로 승격한다. */
    private fun Result<HomeWeather?>.toUiState(): HomeWeatherState =
        fold(
            onSuccess = { weather ->
                weather?.let {
                    HomeWeatherState.Available(
                        regionName = it.regionName,
                        temperatureCelsius = it.temperatureCelsius,
                        observedAt = it.observedAt,
                        topRisk = it.risks.minByOrNull(HomeWeatherRisk::priority),
                    )
                } ?: HomeWeatherState.NotConfigured
            },
            onFailure = { HomeWeatherState.Unavailable },
        )

    private fun HomePlantCare.toItem(today: LocalDate): HomeCareItem =
        HomeCareItem(
            plantId = plantId,
            displayName = displayName,
            status = careStatus(today),
        )

    private fun HomePlantCare.careStatus(today: LocalDate): HomeCareStatus {
        val due = nextWateringDate ?: return HomeCareStatus.Unavailable
        if (wateringIntervalDays == null) return HomeCareStatus.Unavailable
        val days = ChronoUnit.DAYS.between(today, due).toInt()
        return when {
            days == 0 -> HomeCareStatus.DueToday
            days < 0 -> HomeCareStatus.Overdue(-days)
            else -> HomeCareStatus.Upcoming(days)
        }
    }
}

/**
 * 홈 목록의 확정 순서이다.
 *
 * 1. 그룹: 오늘 → 지연 → 예정 → 정보 없음.
 * 2. 그룹 안: 지연은 오래된 것부터, 예정은 가까운 것부터.
 * 3. 같은 급함이면 `plantId` 오름차순으로 고정해 실행마다 순서가 흔들리지 않게 한다.
 */
private object CareOrder : Comparator<HomeCareItem> {
    override fun compare(left: HomeCareItem, right: HomeCareItem): Int {
        val group = left.status.order.compareTo(right.status.order)
        if (group != 0) return group
        val urgency = left.status.urgency().compareTo(right.status.urgency())
        if (urgency != 0) return urgency
        return left.plantId.compareTo(right.plantId)
    }

    /** 그룹 안에서 먼저 보여줄수록 작은 값을 돌려준다. */
    private fun HomeCareStatus.urgency(): Int =
        when (this) {
            HomeCareStatus.DueToday -> 0
            is HomeCareStatus.Overdue -> -days
            is HomeCareStatus.Upcoming -> days
            HomeCareStatus.Unavailable -> 0
        }
}
