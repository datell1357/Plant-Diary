package com.planterior.helper.navigation

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.toRoute

/**
 * 하단 탭으로 이동한다.
 *
 * 탭을 오갈 때 백스택이 무한히 쌓이지 않도록 시작 목적지까지 되감고, 각 탭이 가지고 있던 상태는 보존한다. 같은 탭을 다시 누르면 화면을 새로 만들지 않는다.
 *
 * @param route 이동할 최상위 탭 목적지.
 */
internal fun NavController.navigateToTab(route: PlanteriorRoute.TopLevel) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * 현재 back stack entry가 가리키는 route를 복원한다.
 *
 * @return 인식된 route. 아직 목적지가 없거나 알 수 없으면 `null`.
 */
internal fun NavBackStackEntry?.toPlanteriorRoute(): PlanteriorRoute? {
    val entry = this ?: return null
    val routeName = entry.destination.route?.substringBefore('/')?.substringBefore('?')
    return when (routeName) {
        PlanteriorRoute.Home::class.qualifiedName -> PlanteriorRoute.Home
        PlanteriorRoute.Collection::class.qualifiedName -> PlanteriorRoute.Collection
        PlanteriorRoute.Storage::class.qualifiedName -> PlanteriorRoute.Storage
        PlanteriorRoute.Settings::class.qualifiedName -> PlanteriorRoute.Settings
        PlanteriorRoute.InventoryItemDetail::class.qualifiedName ->
            entry.toRoute<PlanteriorRoute.InventoryItemDetail>()
        PlanteriorRoute.Camera::class.qualifiedName -> PlanteriorRoute.Camera
        PlanteriorRoute.Identification::class.qualifiedName ->
            entry.toRoute<PlanteriorRoute.Identification>()
        PlanteriorRoute.Registration::class.qualifiedName -> PlanteriorRoute.Registration
        PlanteriorRoute.MiniHome::class.qualifiedName -> PlanteriorRoute.MiniHome
        PlanteriorRoute.MiniHomeShare::class.qualifiedName -> PlanteriorRoute.MiniHomeShare
        PlanteriorRoute.Notifications::class.qualifiedName -> PlanteriorRoute.Notifications
        PlanteriorRoute.Weather::class.qualifiedName -> PlanteriorRoute.Weather
        PlanteriorRoute.WeatherRisk::class.qualifiedName ->
            entry.toRoute<PlanteriorRoute.WeatherRisk>()
        PlanteriorRoute.PlantDetail::class.qualifiedName ->
            entry.toRoute<PlanteriorRoute.PlantDetail>()
        PlanteriorRoute.WateringConfirmation::class.qualifiedName ->
            entry.toRoute<PlanteriorRoute.WateringConfirmation>()
        PlanteriorRoute.Login::class.qualifiedName -> entry.toRoute<PlanteriorRoute.Login>()
        else -> null
    }
}
