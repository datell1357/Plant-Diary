package com.planterior.helper.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import com.planterior.helper.R
import com.planterior.helper.core.designsystem.component.PlanteriorBottomBar
import com.planterior.helper.core.designsystem.component.PlanteriorTab
import com.planterior.helper.core.designsystem.icon.PlanteriorIcons
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.feature.auth.AuthAccountScreen
import com.planterior.helper.feature.auth.AuthCoordinator
import com.planterior.helper.feature.auth.AuthScreen
import com.planterior.helper.feature.auth.AuthUiState
import com.planterior.helper.feature.camera.CameraRoute
import com.planterior.helper.feature.collection.CollectionRepository
import com.planterior.helper.feature.collection.CollectionRoute
import com.planterior.helper.feature.collection.PlaceholderPlantThumbnailLoader
import com.planterior.helper.feature.collection.PlantDetailRoute
import com.planterior.helper.feature.collection.PlantThumbnailLoader
import com.planterior.helper.feature.home.HomeScreen
import com.planterior.helper.feature.home.HomeUiState
import com.planterior.helper.feature.home.HomeViewModel
import com.planterior.helper.feature.identify.FirebaseIdentificationGateway
import com.planterior.helper.feature.identify.IdentificationRoute
import com.planterior.helper.feature.registration.RegistrationContent
import com.planterior.helper.feature.registration.RegistrationRepository
import com.planterior.helper.feature.registration.RegistrationRoute
import com.planterior.helper.feature.registration.RegistrationSeed
import com.planterior.helper.feature.watering.WateringConfirmationRoute
import com.planterior.helper.feature.watering.WateringRepository
import com.planterior.helper.identify.debugIdentificationGateway
import com.planterior.helper.identify.photoIdentificationHandoff
import com.planterior.helper.ui.PlaceholderScreen
import java.time.Clock
import kotlinx.coroutines.launch

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
 * @param homeViewModel 홈 대시보드 상태. 주입하지 않으면 로그인 전 홈으로 그린다.
 */
@Composable
fun PlanteriorNavHost(
    navController: NavHostController,
    startRoute: PlanteriorRoute,
    modifier: Modifier = Modifier,
    authCoordinator: AuthCoordinator? = null,
    homeViewModel: HomeViewModel? = null,
    registrationRepository: RegistrationRepository? = null,
    collectionRepository: CollectionRepository? = null,
    wateringRepository: WateringRepository? = null,
    collectionThumbnailLoader: PlantThumbnailLoader = PlaceholderPlantThumbnailLoader,
    clock: Clock = Clock.systemDefaultZone(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry.toPlanteriorRoute()
    val liveAuthState by
        authCoordinator?.state?.collectAsState() ?: remember { mutableStateOf<AuthUiState?>(null) }
    LaunchedEffect(currentRoute, liveAuthState, authCoordinator) {
        val requested = currentRoute ?: return@LaunchedEffect
        if (authCoordinator == null || liveAuthState !is AuthUiState.SignedOut) {
            return@LaunchedEffect
        }
        val guarded = AuthRouteGuard.destination(requested, authenticated = false)
        if (guarded == requested) return@LaunchedEffect
        navController.navigate(guarded) {
            backStackEntry?.destination?.id?.let { popUpTo(it) { inclusive = true } }
            launchSingleTop = true
        }
    }
    val selectedIndex = BottomTabRoutes.indexOfFirst { it == currentRoute }
    val registrationHandoff =
        rememberSaveable(saver = IdentificationRegistrationHandoff.Saver) {
            IdentificationRegistrationHandoff()
        }

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
            if (authCoordinator == null) {
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
            } else {
                val state by authCoordinator.state.collectAsState()
                val scope = rememberCoroutineScope()
                LaunchedEffect(state) {
                    val authenticated = state as? AuthUiState.Authenticated ?: return@LaunchedEffect
                    val target =
                        PlanteriorRouteResolver.resolveReturnRoute(
                            authenticated.returnRoute ?: route.returnRoute
                        )
                    navController.navigate(target) {
                        popUpTo<PlanteriorRoute.Login> { inclusive = true }
                    }
                }
                AuthScreen(
                    state = state,
                    onGoogle = {
                        scope.launch {
                            authCoordinator.signIn(
                                com.planterior.helper.feature.auth.AuthProvider.GOOGLE,
                                route.returnRoute,
                            )
                        }
                    },
                    onApple = {
                        scope.launch {
                            authCoordinator.signIn(
                                com.planterior.helper.feature.auth.AuthProvider.APPLE,
                                route.returnRoute,
                            )
                        }
                    },
                    onCancel = authCoordinator::cancelSignIn,
                    onRetry = { scope.launch { authCoordinator.restore() } },
                )
            }
        }
        composable<PlanteriorRoute.Home> { entry ->
            val homeState by
                homeViewModel?.state?.collectAsState()
                    ?: remember { mutableStateOf(HomeUiState.LoggedOut) }
            // 홈은 로그인 화면 아래에 살아 있다가 다시 앞으로 나온다. 그런 복귀에서는 재구성이 일어나지 않을 수 있으므로
            // 목적지가 다시 RESUMED 상태가 될 때마다 읽어 새 세션을 놓치지 않는다.
            LifecycleResumeEffect(entry, homeViewModel) {
                homeViewModel?.refresh()
                onPauseOrDispose {}
            }
            HomeScreen(
                state = homeState,
                onSignIn = { navController.navigate(PlanteriorRoute.Login()) },
                onNotifications = { navController.navigate(PlanteriorRoute.Notifications) },
                onIdentify = { navController.navigate(PlanteriorRoute.Camera) },
                onOpenMiniHome = { navController.navigate(PlanteriorRoute.MiniHome) },
                onOpenCollection = { navController.navigateToTab(PlanteriorRoute.Collection) },
                onOpenPlant = { plantId ->
                    navController.navigate(PlanteriorRoute.PlantDetail(plantId))
                },
                bottomBar = bottomBar,
            )
        }
        composable<PlanteriorRoute.Collection> {
            if (collectionRepository == null) {
                PlaceholderScreen(
                    title = stringResource(R.string.screen_collection),
                    description = stringResource(R.string.screen_collection_description),
                    bottomBar = bottomBar,
                )
            } else {
                CollectionRoute(
                    repository = collectionRepository,
                    onOpenPlant = { plantId ->
                        navController.navigate(PlanteriorRoute.PlantDetail(plantId.value))
                    },
                    onIdentify = { navController.navigate(PlanteriorRoute.Camera) },
                    onRegisterDirectly = {
                        registrationHandoff.clear()
                        navController.navigate(PlanteriorRoute.Registration)
                    },
                    bottomBar = bottomBar,
                    thumbnailLoader = collectionThumbnailLoader,
                )
            }
        }
        composable<PlanteriorRoute.Storage> {
            PlaceholderScreen(
                title = stringResource(R.string.screen_storage),
                description = stringResource(R.string.screen_storage_description),
                bottomBar = bottomBar,
            )
        }
        composable<PlanteriorRoute.Settings> {
            val scope = rememberCoroutineScope()
            if (authCoordinator == null) {
                PlaceholderScreen(
                    title = stringResource(R.string.screen_settings),
                    description = stringResource(R.string.screen_settings_description),
                    bottomBar = bottomBar,
                )
            } else {
                val state by authCoordinator.state.collectAsState()
                AuthAccountScreen(
                    state = state,
                    onLink = { provider, consent ->
                        scope.launch { authCoordinator.link(provider, consent) }
                    },
                    onLogout = {
                        scope.launch {
                            authCoordinator.logout()
                            navController.navigate(PlanteriorRoute.Login()) {
                                popUpTo(navController.graph.id) { inclusive = true }
                            }
                        }
                    },
                    logoutLabel = stringResource(R.string.action_logout),
                    bottomBar = bottomBar,
                )
            }
        }
        composable<PlanteriorRoute.Camera> {
            val context = LocalContext.current
            val photoHandoff =
                remember(context) {
                    lazy(LazyThreadSafetyMode.NONE) { photoIdentificationHandoff(context) }
                }
            CameraRoute(
                onExit = { navController.popBackStack() },
                onDirectRegistration = {
                    registrationHandoff.clear()
                    navController.navigate(PlanteriorRoute.Registration)
                },
                onIdentificationRequested = { submission ->
                    photoHandoff.value.prepare(submission)
                    registrationHandoff.clear()
                    navController.navigate(PlanteriorRoute.Identification(submission.requestId))
                },
            )
        }
        composable<PlanteriorRoute.Identification> { entry ->
            val route = entry.toRoute<PlanteriorRoute.Identification>()
            val gateway =
                remember(route.requestId) {
                    debugIdentificationGateway(route.requestId) ?: FirebaseIdentificationGateway()
                }
            IdentificationRoute(
                requestIdValue = route.requestId,
                gateway = gateway,
                onExit = { navController.popBackStack() },
                onRetakePhoto = {
                    navController.navigate(PlanteriorRoute.Camera) {
                        popUpTo<PlanteriorRoute.Identification> { inclusive = true }
                    }
                },
                onChangePhoto = {
                    navController.navigate(PlanteriorRoute.Camera) {
                        popUpTo<PlanteriorRoute.Identification> { inclusive = true }
                    }
                },
                onEditManually = {
                    registrationHandoff.clear()
                    navController.navigate(PlanteriorRoute.Registration)
                },
                onRegisterManually = {
                    registrationHandoff.clear()
                    navController.navigate(PlanteriorRoute.Registration)
                },
                onConfirmed = { confirmed ->
                    registrationHandoff.accept(confirmed)
                    navController.navigate(PlanteriorRoute.Registration)
                },
            )
        }
        composable<PlanteriorRoute.Registration> {
            val confirmed = registrationHandoff.confirmed
            val seed =
                confirmed?.let {
                    RegistrationSeed.Identified(
                        RegistrationContent(
                            it.candidate.publicContentId,
                            it.candidate.koreanName
                                ?: it.candidate.commonName
                                ?: it.candidate.scientificName,
                        ),
                        it.requestId.value,
                    )
                } ?: RegistrationSeed.Manual
            if (registrationRepository == null) {
                PlaceholderScreen(
                    title = stringResource(R.string.screen_registration),
                    description =
                        confirmed?.candidate?.let {
                            stringResource(
                                R.string.screen_registration_identified_description,
                                it.koreanName ?: it.commonName ?: it.scientificName,
                            )
                        } ?: stringResource(R.string.screen_registration_description),
                )
            } else {
                val registrationNavigation =
                    remember(navController) {
                        RegistrationNavigationCallbacks { destination ->
                            navController.navigate(destination) {
                                popUpTo<PlanteriorRoute.Registration> { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                RegistrationRoute(
                    seed = seed,
                    repository = registrationRepository,
                    onOpenExisting = {
                        registrationHandoff.clear()
                        registrationNavigation.openExisting(it)
                    },
                    onCompleted = {
                        registrationHandoff.clear()
                        registrationNavigation.registrationCompleted(it.id)
                    },
                    onCancel = {
                        registrationHandoff.clear()
                        navController.popBackStack()
                    },
                )
            }
        }
        composable<PlanteriorRoute.MiniHome> {
            PlaceholderScreen(
                title = stringResource(R.string.screen_mini_home),
                description = stringResource(R.string.screen_mini_home_description),
            )
        }
        composable<PlanteriorRoute.Notifications> {
            PlaceholderScreen(
                title = stringResource(R.string.screen_notifications),
                description = stringResource(R.string.screen_notifications_description),
            )
        }
        composable<PlanteriorRoute.PlantDetail> { entry ->
            val route = entry.toRoute<PlanteriorRoute.PlantDetail>()
            val wateringRefresh by
                entry.savedStateHandle
                    .getStateFlow<String?>(WATERING_REFRESH_KEY, null)
                    .collectAsState()
            if (collectionRepository == null) {
                PlaceholderScreen(
                    title = stringResource(R.string.screen_plant_detail),
                    description = stringResource(R.string.screen_plant_detail_description),
                )
            } else {
                PlantDetailRoute(
                    plantId = PersonalPlantId(route.plantId),
                    repository = collectionRepository,
                    onBack = { navController.popBackStack() },
                    clock = clock,
                    onRecordWatering =
                        wateringRepository?.let {
                            {
                                navController.navigate(
                                    PlanteriorRoute.WateringConfirmation(route.plantId)
                                )
                            }
                        },
                    refreshAfterWatering = wateringRefresh,
                    onWateringRefreshConsumed = {
                        entry.savedStateHandle[WATERING_REFRESH_KEY] = null
                    },
                )
            }
        }
        composable<PlanteriorRoute.WateringConfirmation> { entry ->
            val route = entry.toRoute<PlanteriorRoute.WateringConfirmation>()
            if (wateringRepository == null) {
                PlaceholderScreen(
                    title = "물 주기 완료",
                    description = "물 주기 기록을 준비하지 못했어요.",
                )
            } else {
                WateringConfirmationRoute(
                    plantId = PersonalPlantId(route.plantId),
                    repository = wateringRepository,
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack() },
                    clock = clock,
                    onCompleted = { receipt ->
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set(WATERING_REFRESH_KEY, receipt.operationId.value)
                    },
                )
            }
        }
    }
}

private const val WATERING_REFRESH_KEY = "watering.refresh-operation"
