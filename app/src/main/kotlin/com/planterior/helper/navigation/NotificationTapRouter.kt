package com.planterior.helper.navigation

import androidx.navigation.NavHostController

fun interface NotificationStackNavigator {
    fun replaceWith(routes: List<PlanteriorRoute>)
}

internal fun NavHostController.replaceWithNotificationStack(routes: List<PlanteriorRoute>) {
    if (routes.isEmpty()) return
    navigate(routes.first()) {
        popUpTo(graph.startDestinationId) { inclusive = true }
        launchSingleTop = true
    }
    routes.drop(1).forEach(::navigate)
}

object NotificationTapRouter {
    fun coldStartStack(uri: String?, authenticated: Boolean): List<PlanteriorRoute> {
        val requested = PlanteriorRouteResolver.resolve(uri)
        val target =
            if (!authenticated && requested is PlanteriorRoute.Authenticated) {
                PlanteriorRoute.Login(uri)
            } else {
                AuthRouteGuard.destination(requested, authenticated)
            }
        return PlanteriorRouteResolver.backStackFor(target)
    }

    fun openWarm(
        uri: String?,
        authenticated: Boolean,
        navigator: NotificationStackNavigator,
    ) {
        navigator.replaceWith(coldStartStack(uri, authenticated))
    }

    fun resumeAfterLogin(returnRoute: String?): List<PlanteriorRoute> =
        PlanteriorRouteResolver.backStackFor(
            PlanteriorRouteResolver.resolveReturnRoute(returnRoute)
        )
}
