package com.planterior.helper.navigation

object AuthRouteGuard {
    fun destination(requested: PlanteriorRoute, authenticated: Boolean): PlanteriorRoute =
        if (authenticated || requested is PlanteriorRoute.Public) requested
        else PlanteriorRoute.Login(externalRoute(requested))

    fun returnDestination(returnRoute: String?): PlanteriorRoute.Authenticated =
        PlanteriorRouteResolver.resolveReturnRoute(returnRoute)

    fun externalRoute(route: PlanteriorRoute): String =
        when (route) {
            PlanteriorRoute.Home -> "planterior://home"
            PlanteriorRoute.Collection -> "planterior://collection"
            PlanteriorRoute.Storage -> "planterior://storage"
            PlanteriorRoute.Settings -> "planterior://settings"
            PlanteriorRoute.Camera -> "planterior://camera"
            is PlanteriorRoute.PlantDetail -> "planterior://collection/plant/${route.plantId}"
            is PlanteriorRoute.Login -> "planterior://home"
        }
}
