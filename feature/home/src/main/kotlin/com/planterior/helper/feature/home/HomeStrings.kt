package com.planterior.helper.feature.home

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 홈 화면 문구이다.
 *
 * 문구를 화면 코드에 흩어 두면 상태마다 표현이 달라지므로 한 곳에 모은다. 값은 Figma `home-screen`의 텍스트 레이어를 그대로 따른다.
 *
 * @param zoneId 마지막 동기화 시각을 사람이 읽는 형태로 바꿀 때 쓰는 시간대.
 */
class HomeStrings(private val zoneId: ZoneId) {
    /** Figma `안녕하세요, 민지님!`. 이름이 없으면 게스트로 인사한다. */
    fun greeting(name: String?): String =
        if (name.isNullOrBlank()) "안녕하세요, 게스트님!" else "안녕하세요, ${name}님!"

    /** Figma `서울 성동구 · 28°C`. */
    fun weatherSubtitle(regionName: String, temperatureCelsius: Double): String =
        "$regionName · ${formatTemperature(temperatureCelsius)}"

    /** Figma `로그인하고 시작하기`. */
    val signIn: String = "로그인하고 시작하기"

    /** 상단 종 버튼의 접근성 설명. 아이콘만 있어 이름이 없으면 스크린 리더가 읽을 수 없다. */
    val notifications: String = "알림"

    /** 미니홈피 미리보기 카드의 접근성 설명. */
    val miniHomeAction: String = "미니 식물원 열기"

    /** 로그인 전 홈이 무엇을 담는지 알려 주는 안내이다. */
    val loggedOutDescription: String = "로그인하면 등록한 식물의 오늘 관리와 날씨 안내를 볼 수 있어요."

    /** Figma `민지의 미니 식물원`의 기본형. */
    val miniHomeDefaultTitle: String = "나의 미니 식물원"

    /** 로그인 전에는 미니홈피 구성을 읽을 수 없다. */
    val miniHomeLoggedOut: String = "로그인하면 미니 식물원을 꾸밀 수 있어요."

    /** 등록한 식물이 없으면 배치할 미니어처도 없다. */
    val miniHomeEmpty: String = "아직 배치한 식물이 없어요."

    /** 미니홈피에 배치된 식물 수를 알려 준다. */
    fun miniHomePlacedCount(count: Int): String = "배치한 식물 ${count}개"

    /** Figma `오늘의 식물 관리`. */
    val careSectionTitle: String = "오늘의 식물 관리"

    /** Figma `오늘 1개` 배지. */
    fun dueTodayBadge(count: Int): String = "오늘 ${count}개"

    /** Figma `일정 더보기`. */
    val moreSchedule: String = "일정 더보기"

    /** 카드 부제. Figma `오늘 물 주는 날` / `3일 후 물주기`. */
    fun careStatus(status: HomeCareStatus): String =
        when (status) {
            HomeCareStatus.DueToday -> "오늘 물 주는 날"
            is HomeCareStatus.Overdue -> "${status.days}일 지났어요"
            is HomeCareStatus.Upcoming -> "${status.days}일 후 물주기"
            HomeCareStatus.Unavailable -> "물 주기 정보가 아직 없어요"
        }

    /** 카드 우측 배지. Figma `물주기 완료` / `D-3`. */
    fun careBadge(status: HomeCareStatus): String =
        when (status) {
            HomeCareStatus.DueToday -> "물주기 완료"
            is HomeCareStatus.Overdue -> "D+${status.days}"
            is HomeCareStatus.Upcoming -> "D-${status.days}"
            HomeCareStatus.Unavailable -> "정보 없음"
        }

    /** 날씨만 실패했을 때. 식물 관리는 그대로 쓸 수 있음을 분명히 말한다. */
    val weatherUnavailable: String = "날씨 정보를 불러오지 못했어요. 식물 관리 일정은 그대로 확인할 수 있어요."

    /** 동기화가 지연돼 캐시를 보여줄 때. 마지막 성공 시각을 함께 알린다. */
    fun syncStale(lastSuccessfulAt: Instant?): String =
        if (lastSuccessfulAt == null) {
            "아직 동기화하지 못했어요. 저장된 내용을 보여드릴게요."
        } else {
            "${formatInstant(lastSuccessfulAt)}에 동기화한 내용을 보여드릴게요."
        }

    /** 빈 도감 안내 제목. */
    val emptyTitle: String = "아직 등록한 식물이 없어요"

    /** 빈 도감 안내 본문. 샘플 식물을 그리는 대신 다음 행동을 알려 준다. */
    val emptyDescription: String = "사진으로 식물을 식별하거나 이름을 직접 입력해 첫 식물을 등록해 보세요."

    /** 식물 관리 데이터를 읽지 못했을 때. */
    val errorTitle: String = "식물 정보를 불러오지 못했어요"

    /** 오류 안내 본문. */
    val errorDescription: String = "잠시 후 다시 시도해 주세요."

    /** Figma 식별 진입 CTA. */
    val identify: String = "사진으로 식물 식별하기"

    /** 직접 등록 진입. */
    val registerManually: String = "이름으로 직접 등록하기"

    private fun formatTemperature(celsius: Double): String = "${Math.round(celsius)}°C"

    private fun formatInstant(instant: Instant): String =
        LAST_SYNC_FORMAT.withZone(zoneId).format(instant)

    companion object {
        private val LAST_SYNC_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN)

        /** 계정 시간대를 아직 모를 때 쓰는 기본 한국어 문구. */
        val Korean: HomeStrings = HomeStrings(ZoneId.of("Asia/Seoul"))
    }
}
