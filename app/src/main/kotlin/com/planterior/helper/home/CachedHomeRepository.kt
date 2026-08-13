package com.planterior.helper.home

import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.feature.auth.AuthUiState
import com.planterior.helper.feature.auth.SyncDomain
import com.planterior.helper.feature.auth.SyncStatus
import com.planterior.helper.feature.home.HomeMiniHomePreview
import com.planterior.helper.feature.home.HomePlantCare
import com.planterior.helper.feature.home.HomeRepository
import com.planterior.helper.feature.home.HomeSession
import com.planterior.helper.feature.home.HomeSyncStatus
import com.planterior.helper.feature.home.HomeWeather
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * 로컬 캐시와 인증 세션에서 홈 데이터를 만든다.
 *
 * 홈은 캐시를 먼저 읽으므로 동기화가 실패해도 저장된 식물 관리와 미니홈피 구성을 계속 보여줄 수 있다. 날씨는 별도 공급자에서 오며 실패해도 이 저장소의 다른 결과를
 * 오염시키지 않는다.
 *
 * @param database 계정별로 격리된 로컬 캐시.
 * @param authState 현재 인증 상태. 로그인 전·후는 오직 이 값으로 갈린다.
 * @param weatherSource 지역 날씨 공급자. 실패는 [Result]로만 전달하고 예외를 밖으로 던지지 않는다.
 * @param fallbackZoneId 일정에 시간대가 없거나 해석할 수 없을 때 쓰는 기준. 기기 시간대를 기본으로 한다.
 */
class CachedHomeRepository(
    private val database: PlanteriorDatabase,
    private val authState: StateFlow<AuthUiState>,
    private val weatherSource: HomeWeatherSource,
    private val fallbackZoneId: ZoneId = ZoneId.systemDefault(),
    /**
     * 지금 조회해야 할 계정을 알려준다.
     *
     * 기본값은 인증 상태에서 직접 읽는다. 디버그 QA가 세션을 고정하는 경우에만 다른 계정을 주입해 실제 캐시를 조회하게 한다.
     */
    private val activeAccountUid: () -> String? = {
        (authState.value as? AuthUiState.Authenticated)?.account?.uid
    },
) : HomeRepository {
    override fun sessions(): Flow<HomeSession> = authState.map(::toSession)

    override suspend fun plantCare(): Result<List<HomePlantCare>> = runCatching {
        val accountUid = signedInUid() ?: return@runCatching emptyList()
        val plants =
            database.cacheDao().plants(accountUid).associate { it.plantId to it.displayName }
        val schedules = earliestSchedulePerPlant(accountUid, plants.keys)
        plants.map { (plantId, displayName) ->
            val schedule = schedules[plantId]
            HomePlantCare(
                plantId = plantId,
                displayName = displayName,
                nextWateringDate = schedule?.dueDate,
                // 일정이 있다는 것은 공개 콘텐츠의 물 주기 간격을 이미 적용했다는 뜻이다.
                // 일정이 없으면 간격을 임의로 만들지 않고 정보 없음으로 남긴다.
                wateringIntervalDays = schedule?.let { KNOWN_INTERVAL },
                zoneId = schedule?.zoneId ?: fallbackZoneId,
            )
        }
    }

    override suspend fun weather(): Result<HomeWeather?> = weatherSource.current()

    override suspend fun miniHomePreview(): HomeMiniHomePreview? {
        val accountUid = signedInUid() ?: return null
        val cached = database.cacheDao().miniHome(accountUid) ?: return null
        return HomeMiniHomePreview(title = cached.name, placedPlantCount = cached.placedPlantCount)
    }

    override suspend fun syncStatus(): HomeSyncStatus {
        val accountUid = signedInUid() ?: return HomeSyncStatus.Stale(null)
        val records =
            SyncDomain.entries.mapNotNull { domain ->
                database.syncDao().lastSync(accountUid, domain.name)
            }
        if (records.isEmpty()) return HomeSyncStatus.Stale(null)
        val lastSuccess =
            records
                .filter { it.status == SyncStatus.SUCCESS.name }
                .maxOfOrNull { it.syncedAtEpochMillis }
                ?.let(Instant::ofEpochMilli)
        val anyFailure = records.any { it.status == SyncStatus.FAILED.name }
        return if (anyFailure || lastSuccess == null) HomeSyncStatus.Stale(lastSuccess)
        else HomeSyncStatus.Synced(lastSuccess)
    }

    private fun toSession(state: AuthUiState): HomeSession =
        when (state) {
            is AuthUiState.Authenticated ->
                HomeSession.SignedIn(
                    accountUid = state.account.uid,
                    displayName = state.account.displayName,
                    zoneId = fallbackZoneId,
                )
            // 복원과 로그인 진행 중에는 아직 결과를 모른다. 로그아웃으로 단정하면 잠깐 게스트 홈이 스쳐 지나간다.
            AuthUiState.Restoring,
            is AuthUiState.SigningIn -> HomeSession.Restoring
            else -> HomeSession.SignedOut
        }

    private fun signedInUid(): String? = activeAccountUid()

    private suspend fun earliestSchedulePerPlant(
        accountUid: String,
        plantIds: Set<String>,
    ): Map<String, ScheduleView> =
        database
            .cacheDao()
            .scheduleIds(accountUid)
            .mapNotNull { scheduleId -> database.cacheDao().schedule(accountUid, scheduleId) }
            .filter { it.plantId in plantIds }
            .mapNotNull { entity ->
                val due = runCatching { LocalDate.parse(entity.dueDate) }.getOrNull()
                due?.let { entity.plantId to ScheduleView(it, parseZone(entity.zoneId)) }
            }
            .groupBy({ it.first }, { it.second })
            // 같은 식물에 일정이 여럿이면 가장 이른 예정일을 홈 기준으로 삼는다.
            .mapValues { (_, views) -> views.minBy { it.dueDate } }

    /**
     * 저장된 시간대 문자열을 해석한다.
     *
     * 서버나 과거 버전이 넣은 값이 깨져 있을 수 있으므로 실패하면 홈 전체를 멈추는 대신 기준 시간대로 물러난다.
     */
    private fun parseZone(rawZoneId: String): ZoneId =
        try {
            ZoneId.of(rawZoneId)
        } catch (_: DateTimeException) {
            fallbackZoneId
        }

    private data class ScheduleView(val dueDate: LocalDate, val zoneId: ZoneId)

    private companion object {
        /**
         * 일정이 계산되어 있다는 사실만 표현하는 표시값이다.
         *
         * 홈은 간격의 실제 크기를 쓰지 않고 "간격을 알 수 있는가"만 판단하므로, 상세 화면이 쓰는 실제 간격을 여기서 다시 조회하지 않는다.
         */
        const val KNOWN_INTERVAL = 1
    }
}

/**
 * 홈이 쓰는 지역 날씨 공급자이다.
 *
 * 날씨 기능 자체는 이후 todo에서 구현한다. 홈은 실패를 부분 저하로만 다루면 되므로 이 좁은 계약만 있으면 된다.
 */
fun interface HomeWeatherSource {
    /** 현재 지역 날씨. 아직 지역을 정하지 않았으면 성공하면서 `null`을 돌려준다. */
    suspend fun current(): Result<HomeWeather?>
}
