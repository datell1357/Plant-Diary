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

    /** 식물 촬영 화면. 하단 탭 가운데 카메라 액션과 홈의 식별 CTA가 연다. */
    @Serializable data object Camera : Authenticated

    /** 미니홈피. 홈의 미리보기 카드가 연다. 하단 탭이 아니므로 탭 목록에 넣지 않는다. */
    @Serializable data object MiniHome : Authenticated

    /** 알림 목록. 홈 상단 종 버튼이 연다. */
    @Serializable data object Notifications : Authenticated

    /**
     * 식물 상세.
     *
     * @param plantId 개인 식물의 불투명 domain ID. 사용자 ID나 사진 데이터가 아니다.
     */
    @Serializable data class PlantDetail(val plantId: String) : Authenticated
}
