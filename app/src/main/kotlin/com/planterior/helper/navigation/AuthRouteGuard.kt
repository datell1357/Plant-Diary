package com.planterior.helper.navigation

object AuthRouteGuard {
    /**
     * 진입 route를 실제로 열 목적지로 바꾼다.
     *
     * 홈은 Figma `home-screen-logged-out`이 있어 로그인 전에도 열린다. 홈이 스스로 로그인 유도를 보여주므로 강제 리다이렉션이 필요 없다. 나머지
     * 인증 목적지는 그대로 로그인 뒤에 둘다.
     */
    fun destination(requested: PlanteriorRoute, authenticated: Boolean): PlanteriorRoute =
        if (
            authenticated ||
                requested is PlanteriorRoute.Public ||
                requested is PlanteriorRoute.Home
        ) {
            requested
        } else {
            PlanteriorRoute.Login(externalRoute(requested))
        }

    fun returnDestination(returnRoute: String?): PlanteriorRoute.Authenticated =
        PlanteriorRouteResolver.resolveReturnRoute(returnRoute)

    fun externalRoute(route: PlanteriorRoute): String =
        when (route) {
            PlanteriorRoute.Home -> "planterior://home"
            PlanteriorRoute.Collection -> "planterior://collection"
            PlanteriorRoute.Storage -> "planterior://storage"
            is PlanteriorRoute.InventoryItemDetail -> "planterior://storage/item/${route.itemId}"
            PlanteriorRoute.Settings -> "planterior://settings"
            PlanteriorRoute.Camera -> "planterior://camera"
            is PlanteriorRoute.Identification -> "planterior://home"
            PlanteriorRoute.Registration -> "planterior://registration"
            PlanteriorRoute.MiniHome -> "planterior://minihome"
            PlanteriorRoute.Notifications -> "planterior://notifications"
            PlanteriorRoute.Weather -> "planterior://weather"
            is PlanteriorRoute.WeatherRisk -> "planterior://weather/plant/${route.plantId}"
            is PlanteriorRoute.PlantDetail -> "planterior://collection/plant/${route.plantId}"
            is PlanteriorRoute.WateringConfirmation ->
                "planterior://collection/plant/${route.plantId}/watering"
            is PlanteriorRoute.Login -> "planterior://home"
        }
}
