package com.planterior.helper.feature.home

import java.time.Instant

/**
 * 홈 화면이 그릴 수 있는 전체 상태이다.
 *
 * 실패는 두 종류로 나뉜다. 식물 관리 데이터를 읽지 못하면 홈은 [Error]가 되고, 날씨나 동기화만 실패하면 [Content] 안에서 부분 저하로만 표시한다.
 */
sealed interface HomeUiState {
    /** 이 상태의 비공개 데이터 소유자. 중립 상태는 `null`이다. */
    val ownerUid: String?

    /** 첫 로딩. 아직 세션조차 확인하지 못했다. */
    data object Loading : HomeUiState {
        override val ownerUid: String? = null
    }

    /**
     * 로그인 전 홈이다.
     *
     * 식물·아이템 데이터를 하나도 읽지 않으며 로그인 유도만 보여준다.
     */
    data object LoggedOut : HomeUiState {
        override val ownerUid: String? = null
    }

    /**
     * 로그인했지만 등록한 식물이 없다.
     *
     * 샘플 식물이나 아이템을 실제 데이터처럼 그리지 않는다. 이 상태는 등록 CTA만 담는다.
     */
    data class Empty(
        val greetingName: String?,
        val weather: HomeWeatherState,
        val sync: HomeSyncState,
        override val ownerUid: String = "legacy-unmanaged",
    ) : HomeUiState

    /**
     * 등록한 식물이 있는 홈이다.
     *
     * @param careItems 오늘 → 지연 → 예정 → 정보 없음 순으로 확정된 목록.
     * @param dueTodayCount 오늘 물을 줘야 하는 식물 수. 섹션 배지에 쓴다.
     */
    data class Content(
        val greetingName: String?,
        val careItems: List<HomeCareItem>,
        val dueTodayCount: Int,
        val miniHome: HomeMiniHomePreview?,
        val weather: HomeWeatherState,
        val sync: HomeSyncState,
        override val ownerUid: String = "legacy-unmanaged",
    ) : HomeUiState {
        /** 날씨나 동기화 중 하나라도 저하되었는지. 홈 자체는 계속 사용할 수 있다. */
        val isPartial: Boolean
            get() = weather == HomeWeatherState.Unavailable || sync is HomeSyncState.Stale
    }

    /** 식물 관리 데이터를 읽지 못해 홈을 구성할 수 없다. */
    data class Error(
        val sync: HomeSyncState,
        override val ownerUid: String = "legacy-unmanaged",
    ) : HomeUiState
}

/**
 * 홈에 그리는 식물 관리 항목 하나이다.
 *
 * @param plantId 상세 화면으로 이동할 때 쓰는 불투명 domain ID.
 */
data class HomeCareItem(
    val plantId: String,
    val displayName: String,
    val status: HomeCareStatus,
)

/**
 * 식물 하나의 물 주기 상태이다.
 *
 * [order]는 홈 목록의 그룹 순서를 정한다. 오늘 → 지연 → 예정 → 정보 없음이며, PRD가 요구한 "오늘 할 일 먼저"를 보장한다.
 */
sealed interface HomeCareStatus {
    val order: Int

    /** 오늘이 물 주는 날. */
    data object DueToday : HomeCareStatus {
        override val order: Int = 0
    }

    /**
     * 예정일이 지났다.
     *
     * @param days 며칠 지났는지. 항상 1 이상이다.
     */
    data class Overdue(val days: Int) : HomeCareStatus {
        init {
            require(days >= 1) { "지연 일수는 1일 이상이어야 한다." }
        }

        override val order: Int = 1
    }

    /**
     * 아직 예정일 전이다.
     *
     * @param days 며칠 뒤인지. 항상 1 이상이다.
     */
    data class Upcoming(val days: Int) : HomeCareStatus {
        init {
            require(days >= 1) { "예정 일수는 1일 이상이어야 한다." }
        }

        override val order: Int = 2
    }

    /** 물 주기 간격이나 마지막 물 주기일이 없어 일정을 계산할 수 없다. 임의 기본값을 만들지 않는다. */
    data object Unavailable : HomeCareStatus {
        override val order: Int = 3
    }
}

/** 홈에서 본 날씨 상태이다. */
sealed interface HomeWeatherState {
    /**
     * 날씨를 읽었다.
     *
     * @param topRisk 우선순위가 가장 높은 위험 하나. 위험이 없으면 `null`.
     */
    data class Available(
        val regionName: String,
        val temperatureCelsius: Double,
        val observedAt: Instant,
        val topRisk: HomeWeatherRisk?,
    ) : HomeWeatherState

    /** 날씨만 실패했다. 식물 관리 콘텐츠는 그대로 유지된다. */
    data object Unavailable : HomeWeatherState

    /**
     * 아직 지역을 정하지 않았다.
     *
     * 실패가 아니므로 오류 안내를 띄우지 않는다. 둘을 같은 상태로 묶으면 정상 신규 사용자에게 있지도 않은 장애를 알리게 된다.
     */
    data object NotConfigured : HomeWeatherState
}

/** 홈에서 본 동기화 상태이다. */
sealed interface HomeSyncState {
    /** 최신 상태이다. */
    data object Fresh : HomeSyncState

    /**
     * 동기화가 실패해 캐시된 내용을 보여주는 중이다.
     *
     * @param lastSuccessfulAt 마지막 성공 시각. 한 번도 성공하지 않았으면 `null`.
     */
    data class Stale(val lastSuccessfulAt: Instant?) : HomeSyncState
}
