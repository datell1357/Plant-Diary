package com.planterior.helper.navigation

import kotlinx.serialization.Serializable

/**
 * 앱의 모든 목적지를 나타내는 타입 안전 route이다.
 *
 * route 인자에는 불투명한 domain ID만 담는다. 사용자 ID, 사진 bytes, 메모 본문처럼 개인정보나 큰 payload는 절대 넣지 않는다. 화면은 ID로 필요한
 * 데이터를 다시 조회한다.
 */
@Serializable
sealed interface PlanteriorRoute {
    /** 로그인 전에 보여주는 공개 그래프의 목적지이다. */
    @Serializable sealed interface Public : PlanteriorRoute

    /** 로그인 후에만 접근할 수 있는 그래프의 목적지이다. */
    @Serializable sealed interface Authenticated : PlanteriorRoute

    /** 하단 탭이 직접 가리키는 최상위 목적지이다. */
    @Serializable sealed interface TopLevel : Authenticated

    /** 로그인 화면. */
    @Serializable
    data class Login(
        /**
         * 로그인 성공 후 되돌아갈 목적지이다.
         *
         * 외부에서 주입될 수 있으므로 [PlanteriorRouteResolver]가 허용 목록으로 검사한 값만 쓴다.
         */
        val returnRoute: String? = null
    ) : Public

    /** 홈 대시보드. 하단 탭 `홈`. */
    @Serializable data object Home : TopLevel

    /** 개인 도감 목록. 하단 탭 `도감`. */
    @Serializable data object Collection : TopLevel

    /** 아이템 창고. 하단 탭 `창고`. */
    @Serializable data object Storage : TopLevel

    /** 앱 설정. 하단 탭 `설정`. */
    @Serializable data object Settings : TopLevel

    /** 계정 삭제 범위와 서버 lifecycle을 확인하는 전용 화면. */
    @Serializable data object AccountDeletion : Authenticated

    /** 창고나 상점 카드에서 여는 아이템 상세. 계정 정보 없이 불투명 아이템 ID만 전달한다. */
    @Serializable data class InventoryItemDetail(val itemId: String) : Authenticated

    /** 식물 촬영 화면. 하단 탭 가운데 카메라 액션과 홈의 식별 CTA가 연다. */
    @Serializable data object Camera : Authenticated

    /** 사진 처리 고지 승인 뒤 식별 기능이 이어받는 요청. 사진이나 사용자 정보는 route에 넣지 않는다. */
    @Serializable data class Identification(val requestId: String) : Authenticated

    /** 사진 권한 없이도 열 수 있는 직접 등록 진입점. */
    @Serializable data object Registration : Authenticated

    /** 미니홈피. 홈의 미리보기 카드가 연다. 하단 탭이 아니므로 탭 목록에 넣지 않는다. */
    @Serializable data object MiniHome : Authenticated

    /**
     * 미니홈 공유. 미니 식물원의 보기 상태 하단 액션이 연다.
     *
     * 인자가 없다. 공유 링크는 bearer 데이터라 route 인자로 절대 옮기지 않고, 화면이 확정 구성을 직접 다시 읽는다.
     */
    @Serializable data object MiniHomeShare : Authenticated

    /** 알림 목록. 홈 상단 종 버튼이 연다. */
    @Serializable data object Notifications : Authenticated

    /** 현재 날씨와 모든 등록 식물의 날씨 위험 안내. */
    @Serializable data object Weather : Authenticated

    /** 날씨 알림이 가리키는 식물별 위험 상세. */
    @Serializable data class WeatherRisk(val plantId: String) : Authenticated

    /**
     * 식물 상세.
     *
     * @param plantId 개인 식물의 불투명 domain ID. 사용자 ID나 사진 데이터가 아니다.
     */
    @Serializable data class PlantDetail(val plantId: String) : Authenticated

    /** 식물 상세에서 시작하는 물 주기 완료 확인과 결과 화면. */
    @Serializable data class WateringConfirmation(val plantId: String) : Authenticated
}
