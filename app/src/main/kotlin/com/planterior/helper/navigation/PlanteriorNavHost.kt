package com.planterior.helper.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import com.planterior.helper.R
import com.planterior.helper.core.designsystem.component.PlanteriorBottomBar
import com.planterior.helper.core.designsystem.component.PlanteriorTab
import com.planterior.helper.core.designsystem.icon.PlanteriorIcons
import com.planterior.helper.ui.PlaceholderScreen

/**
 * 하단 탭 순서이다. Figma `tab-bar`의 좌측 2개와 우측 2개 순서를 그대로 따른다.
 *
 * 가운데 카메라 액션은 탭이 아니므로 이 목록에 넣지 않는다.
 */
internal val BottomTabRoutes: List<PlanteriorRoute.TopLevel> =
    listOf(
        PlanteriorRoute.Home,
        PlanteriorRoute.Collection,
        PlanteriorRoute.Storage,
        PlanteriorRoute.Settings,
    )

/**
 * 앱 셸이다. 인증 전 공개 그래프와 인증 후 그래프를 한 host에 담고 하단 탭을 붙인다.
 *
 * @param navController 화면 전환을 담당하는 컨트롤러.
 * @param startRoute 시작 목적지. 딥링크 cold start에서는 해석된 route가 들어온다.
 */
@Composable
fun PlanteriorNavHost(
    navController: NavHostController,
    startRoute: PlanteriorRoute,
    modifier: Modifier = Modifier,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry.toPlanteriorRoute()
    val selectedIndex = BottomTabRoutes.indexOfFirst { it == currentRoute }

    val tabs =
        listOf(
            PlanteriorTab(stringResource(R.string.tab_home), PlanteriorIcons.Home),
            PlanteriorTab(stringResource(R.string.tab_collection), PlanteriorIcons.Collection),
            PlanteriorTab(stringResource(R.string.tab_storage), PlanteriorIcons.Storage),
            PlanteriorTab(stringResource(R.string.tab_settings), PlanteriorIcons.Settings),
        )
    val cameraDescription = stringResource(R.string.tab_camera)

    val bottomBar: @Composable () -> Unit = {
        PlanteriorBottomBar(
            tabs = tabs,
            selectedIndex = selectedIndex,
            onTabSelected = { index -> navController.navigateToTab(BottomTabRoutes[index]) },
            cameraContentDescription = cameraDescription,
            onCameraClick = { navController.navigate(PlanteriorRoute.Camera) },
        )
    }

    NavHost(navController = navController, startDestination = startRoute, modifier = modifier) {
        composable<PlanteriorRoute.Login> { entry ->
            val route = entry.toRoute<PlanteriorRoute.Login>()
            PlaceholderScreen(
                title = stringResource(R.string.screen_login),
                description = stringResource(R.string.screen_login_description),
                onPrimaryAction = {
                    val target = PlanteriorRouteResolver.resolveReturnRoute(route.returnRoute)
                    navController.navigate(target) {
                        popUpTo<PlanteriorRoute.Login> { inclusive = true }
                    }
                },
                primaryActionLabel = stringResource(R.string.action_continue),
            )
        }
        composable<PlanteriorRoute.Home> {
            PlaceholderScreen(
                title = stringResource(R.string.screen_home),
                description = stringResource(R.string.screen_home_description),
                bottomBar = bottomBar,
            )
        }
        composable<PlanteriorRoute.Collection> {
            PlaceholderScreen(
                title = stringResource(R.string.screen_collection),
                description = stringResource(R.string.screen_collection_description),
                bottomBar = bottomBar,
            )
        }
        composable<PlanteriorRoute.Storage> {
            PlaceholderScreen(
                title = stringResource(R.string.screen_storage),
                description = stringResource(R.string.screen_storage_description),
                bottomBar = bottomBar,
            )
        }
        composable<PlanteriorRoute.Settings> {
            PlaceholderScreen(
                title = stringResource(R.string.screen_settings),
                description = stringResource(R.string.screen_settings_description),
                bottomBar = bottomBar,
            )
        }
        composable<PlanteriorRoute.Camera> {
            PlaceholderScreen(
                title = stringResource(R.string.screen_camera),
                description = stringResource(R.string.screen_camera_description),
            )
        }
        composable<PlanteriorRoute.PlantDetail> {
            PlaceholderScreen(
                title = stringResource(R.string.screen_plant_detail),
                description = stringResource(R.string.screen_plant_detail_description),
            )
        }
    }
}
