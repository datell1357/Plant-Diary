package com.planterior.helper.feature.home

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/**
 * 홈이 필요로 하는 세션 상태이다.
 *
 * 로그인 전·후 화면은 이 값에서만 갈라진다. 디버그 기본값이나 빌드 타입으로 결정하지 않는다.
 */
sealed interface HomeSession {
    /**
     * 저장된 세션을 복원하는 중이라 아직 로그인 여부를 모른다.
     *
     * 로그아웃과 반드시 구분해야 한다. 하나로 묶으면 복원이 끝나기 전에 로그아웃 홈을 그려 버리고 그대로 멈춴다.
     */
    data object Restoring : HomeSession

    /** 아직 로그인하지 않은 상태. */
    data object SignedOut : HomeSession

    /**
     * 로그인한 상태.
     *
     * @param accountUid 계정을 가리키는 불투명 식별자. 화면에 그대로 노출하지 않는다.
     * @param displayName 인사말에 쓰는 표시 이름. 공급자가 주지 않으면 `null`.
     * @param zoneId 물 주기 날짜 비교의 기준이 되는 계정 시간대.
     */
    data class SignedIn(
        val accountUid: String,
        val displayName: String?,
        val zoneId: ZoneId,
    ) : HomeSession
}

/**
 * 홈이 정렬·표시에 쓰는 최소 식물 관리 정보이다.
 *
 * @param plantId 개인 식물의 불투명 domain ID.
 * @param displayName 사용자가 정한 식물 이름.
 * @param nextWateringDate 다음 물 주기 예정일. 계산할 수 없으면 `null`.
 * @param wateringIntervalDays 공개 콘텐츠의 물 주기 간격. 없으면 `null`이며 임의 기본값을 만들지 않는다.
 * @param zoneId 이 일정이 생성된 시간대. “오늘”은 이 시간대의 자정 경계로 판단한다. 여행·이사로 식물마다 기준이 다를 수 있어 하나로 묶지 않는다.
 */
data class HomePlantCare(
    val plantId: String,
    val displayName: String,
    val nextWateringDate: LocalDate?,
    val wateringIntervalDays: Int?,
    val zoneId: ZoneId,
)

/** 홈이 표시하는 날씨 관측 값과 그로부터 판정된 위험 목록이다. */
data class HomeWeather(
    val regionName: String,
    val temperatureCelsius: Double,
    val observedAt: Instant,
    val risks: List<HomeWeatherRisk>,
)

/**
 * 날씨 기반 위험이다.
 *
 * 홈은 한 번에 하나만 보여주므로 우선순위가 필요하다. [priority]가 낮을수록 먼저 보여준다. PRD의 고온·저온·건조·과습 순서를 그대로 따르며 동점은 존재하지
 * 않는다.
 */
sealed interface HomeWeatherRisk {
    val message: String
    val priority: Int

    /** 고온. 즉시 잎이 상할 수 있어 가장 먼저 안내한다. */
    data class HighTemperature(override val message: String) : HomeWeatherRisk {
        override val priority: Int = 0
    }

    /** 저온. */
    data class LowTemperature(override val message: String) : HomeWeatherRisk {
        override val priority: Int = 1
    }

    /** 건조. */
    data class Dry(override val message: String) : HomeWeatherRisk {
        override val priority: Int = 2
    }

    /** 과습. */
    data class Overwatered(override val message: String) : HomeWeatherRisk {
        override val priority: Int = 3
    }
}

/**
 * 미니홈피 미리보기이다.
 *
 * 마지막으로 서버에 확정된 구성만 담는다. 저장하지 않은 draft는 홈에 올라오지 않는다.
 */
data class HomeMiniHomePreview(val title: String, val placedPlantCount: Int)

/** 마지막 동기화 결과이다. */
sealed interface HomeSyncStatus {
    /** 최신 동기화에 성공했다. */
    data class Synced(val at: Instant) : HomeSyncStatus

    /**
     * 동기화가 실패해 캐시가 오래되었다.
     *
     * @param lastSuccessfulAt 마지막으로 성공한 시각. 한 번도 성공하지 않았으면 `null`.
     */
    data class Stale(val lastSuccessfulAt: Instant?) : HomeSyncStatus
}

/**
 * 홈이 읽는 데이터 원본이다.
 *
 * 날씨와 식물 관리는 서로 다른 저장소에서 오며 한쪽 실패가 다른 쪽을 막지 않는다. 그래서 각각 독립적인 [Result]로 돌려준다.
 */
interface HomeRepository {
    /**
     * 세션 변화 흐름이다.
     *
     * 홈은 한 번 읽고 말지 않고 이 흐름을 구독한다. 복원 중·로그인·로그아웃·계정 전환이 모두 여기서 밀려오므로 타이밍에 따라 상태가 잘못 고정되지 않는다.
     */
    fun sessions(): Flow<HomeSession>

    /** 오늘의 관리 대상 식물. 실패하면 홈은 오류 상태가 된다. */
    suspend fun plantCare(): Result<List<HomePlantCare>>

    /** 현재 지역 날씨. 실패해도 홈 전체가 실패하지 않고 부분 저하로만 처리된다. */
    suspend fun weather(): Result<HomeWeather?>

    /** 미니홈피 미리보기. 아직 만들지 않았으면 `null`. */
    suspend fun miniHomePreview(): HomeMiniHomePreview?

    /** 마지막 동기화 상태. */
    suspend fun syncStatus(): HomeSyncStatus
}
