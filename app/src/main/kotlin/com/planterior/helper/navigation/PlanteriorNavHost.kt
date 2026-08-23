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
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemId
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
import com.planterior.helper.feature.minihome.MiniHomeAuthOwnership
import com.planterior.helper.feature.minihome.MiniHomePhotoLoader
import com.planterior.helper.feature.minihome.MiniHomePhotoRequest
import com.planterior.helper.feature.minihome.MiniHomeRepository
import com.planterior.helper.feature.minihome.MiniHomeRoute
import com.planterior.helper.feature.registration.RegistrationAuthOwnership
import com.planterior.helper.feature.registration.RegistrationContent
import com.planterior.helper.feature.registration.RegistrationRepository
import com.planterior.helper.feature.registration.RegistrationRoute
import com.planterior.helper.feature.registration.RegistrationSeed
import com.planterior.helper.feature.share.MiniHomeShareRepository
import com.planterior.helper.feature.share.MiniHomeShareRoute
import com.planterior.helper.feature.shop.CatalogMediaLoadResult
import com.planterior.helper.feature.shop.CatalogMediaLoader
import com.planterior.helper.feature.shop.InventoryAuthOwnership
import com.planterior.helper.feature.shop.InventoryItemDetailRoute
import com.planterior.helper.feature.shop.InventoryRepository
import com.planterior.helper.feature.shop.InventoryRoute
import com.planterior.helper.feature.shop.PlaceholderCatalogMediaLoader
import com.planterior.helper.feature.watering.WateringConfirmationRoute
import com.planterior.helper.feature.watering.WateringNotificationSettingsRepository
import com.planterior.helper.feature.watering.WateringNotificationSettingsRoute
import com.planterior.helper.feature.watering.WateringRepository
import com.planterior.helper.feature.weather.WeatherLocationGateway
import com.planterior.helper.feature.weather.WeatherPermissionCapabilityStore
import com.planterior.helper.feature.weather.WeatherRepository
import com.planterior.helper.feature.weather.WeatherRoute
import com.planterior.helper.identify.debugIdentificationGateway
import com.planterior.helper.identify.photoIdentificationHandoff
import com.planterior.helper.minihome.observeDebugMiniHomeState
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
    authRouteGuardEnabled: Boolean = true,
    signedOutReturnRoute: String? = null,
    homeViewModel: HomeViewModel? = null,
    registrationRepository: RegistrationRepository? = null,
    collectionRepository: CollectionRepository? = null,
    miniHomeRepository: MiniHomeRepository? = null,
    miniHomeShareRepository: MiniHomeShareRepository? = null,
    miniHomeAuthOwnershipOverride: MiniHomeAuthOwnership? = null,
    inventoryRepository: InventoryRepository? = null,
    wateringRepository: WateringRepository? = null,
    wateringNotificationSettingsRepository: WateringNotificationSettingsRepository? = null,
    weatherRepository: WeatherRepository? = null,
    weatherLocationGateway: WeatherLocationGateway? = null,
    weatherPermissionCapabilities: WeatherPermissionCapabilityStore? = null,
    notificationPermissionGranted: Boolean = true,
    canRequestNotificationPermission: Boolean = false,
    onRequestNotificationPermission: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onOpenLocationSettings: () -> Unit = {},
    collectionThumbnailLoader: PlantThumbnailLoader = PlaceholderPlantThumbnailLoader,
    catalogMediaLoader: CatalogMediaLoader = PlaceholderCatalogMediaLoader,
    clock: Clock = Clock.systemDefaultZone(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry.toPlanteriorRoute()
    val liveAuthState by
        authCoordinator?.state?.collectAsState() ?: remember { mutableStateOf<AuthUiState?>(null) }
    val registrationAuthOwnership =
        registrationAuthOwnership(
            authState = liveAuthState,
            coordinatorAvailable = authCoordinator != null,
            enforcementEnabled = authRouteGuardEnabled,
        )
    val miniHomeAuthOwnership =
        miniHomeAuthOwnershipOverride
            ?: miniHomeAuthOwnership(
                authState = liveAuthState,
                coordinatorAvailable = authCoordinator != null,
                enforcementEnabled = authRouteGuardEnabled,
            )
    val inventoryAuthOwnership = miniHomeAuthOwnership.toInventoryOwnership()
    LaunchedEffect(
        currentRoute,
        liveAuthState,
        authCoordinator,
        authRouteGuardEnabled,
        signedOutReturnRoute,
    ) {
        val requested = currentRoute ?: return@LaunchedEffect
        if (
            !authRouteGuardEnabled ||
                authCoordinator == null ||
                liveAuthState !is AuthUiState.SignedOut
        ) {
            return@LaunchedEffect
        }
        val guarded =
            if (requested is PlanteriorRoute.Authenticated && signedOutReturnRoute != null) {
                PlanteriorRoute.Login(signedOutReturnRoute)
            } else {
                AuthRouteGuard.destination(requested, authenticated = false)
            }
        if (guarded == requested) return@LaunchedEffect
        if (navController.currentBackStackEntry.toPlanteriorRoute() != requested) {
            return@LaunchedEffect
        }
        navController.navigate(guarded) {
            backStackEntry?.destination?.id?.let { popUpTo(it) { inclusive = true } }
            launchSingleTop = true
        }
    }
    val selectedBottomRoute =
        when (currentRoute) {
            is PlanteriorRoute.InventoryItemDetail -> PlanteriorRoute.Storage
            else -> currentRoute
        }
    val selectedIndex = BottomTabRoutes.indexOfFirst { it == selectedBottomRoute }
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
            onTabSelected = { index ->
                val destination = BottomTabRoutes[index]
                if (currentRoute is PlanteriorRoute.InventoryItemDetail) {
                    navController.popBackStack()
                    if (destination != PlanteriorRoute.Storage) {
                        navController.navigateToTab(destination)
                    }
                } else {
                    navController.navigateToTab(destination)
                }
            },
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
                        navController.replaceWithNotificationStack(
                            NotificationTapRouter.resumeAfterLogin(route.returnRoute)
                        )
                    },
                    primaryActionLabel = stringResource(R.string.action_continue),
                )
            } else {
                val state by authCoordinator.state.collectAsState()
                val scope = rememberCoroutineScope()
                LaunchedEffect(state) {
                    val authenticated = state as? AuthUiState.Authenticated ?: return@LaunchedEffect
                    navController.replaceWithNotificationStack(
                        NotificationTapRouter.resumeAfterLogin(
                            authenticated.returnRoute ?: route.returnRoute
                        )
                    )
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
                state = homeState.displayedFor(miniHomeAuthOwnership),
                onSignIn = { navController.navigate(PlanteriorRoute.Login()) },
                onNotifications = { navController.navigate(PlanteriorRoute.Notifications) },
                onIdentify = { navController.navigate(PlanteriorRoute.Camera) },
                onOpenMiniHome = { navController.navigate(PlanteriorRoute.MiniHome) },
                onOpenCollection = { navController.navigateToTab(PlanteriorRoute.Collection) },
                onOpenPlant = { plantId ->
                    navController.navigate(PlanteriorRoute.PlantDetail(plantId))
                },
                onOpenWeather = { navController.navigate(PlanteriorRoute.Weather) },
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
            if (inventoryRepository == null) {
                PlaceholderScreen(
                    title = stringResource(R.string.screen_storage),
                    description = stringResource(R.string.screen_storage_description),
                    bottomBar = bottomBar,
                )
            } else {
                InventoryRoute(
                    repository = inventoryRepository,
                    authOwnership = inventoryAuthOwnership,
                    onOpenMiniHome = { navController.navigate(PlanteriorRoute.MiniHome) },
                    onOpenItem = { itemId ->
                        navController.navigate(PlanteriorRoute.InventoryItemDetail(itemId.value))
                    },
                    bottomBar = bottomBar,
                    mediaLoader = catalogMediaLoader,
                )
            }
        }
        composable<PlanteriorRoute.InventoryItemDetail> { entry ->
            val route = entry.toRoute<PlanteriorRoute.InventoryItemDetail>()
            if (inventoryRepository == null) {
                PlaceholderScreen(
                    title = "아이템 상세",
                    description = "아이템 정보를 불러올 수 없어요.",
                    bottomBar = bottomBar,
                )
            } else {
                InventoryItemDetailRoute(
                    repository = inventoryRepository,
                    authOwnership = inventoryAuthOwnership,
                    itemId = ItemId(route.itemId),
                    onBack = { navController.popBackStack() },
                    onOpenMiniHome = { navController.navigate(PlanteriorRoute.MiniHome) },
                    bottomBar = bottomBar,
                    mediaLoader = catalogMediaLoader,
                )
            }
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
                            if (authCoordinator.logout()) {
                                navController.navigate(PlanteriorRoute.Login()) {
                                    popUpTo(navController.graph.id) { inclusive = true }
                                }
                            }
                        }
                    },
                    onNotificationSettings = {
                        navController.navigate(PlanteriorRoute.Notifications)
                    },
                    onWeatherSettings = { navController.navigate(PlanteriorRoute.Weather) },
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
                        registrationNavigation.registrationCompleted(it)
                    },
                    onCancel = {
                        registrationHandoff.clear()
                        navController.popBackStack()
                    },
                    authOwnership = registrationAuthOwnership,
                )
            }
        }
        composable<PlanteriorRoute.MiniHome> {
            if (miniHomeRepository == null) {
                PlaceholderScreen(
                    title = stringResource(R.string.screen_mini_home),
                    description = stringResource(R.string.screen_mini_home_description),
                )
            } else {
                val context = LocalContext.current
                MiniHomeRoute(
                    repository = miniHomeRepository,
                    onBack = { navController.popBackStack() },
                    onOpenCollection = { navController.navigateToTab(PlanteriorRoute.Collection) },
                    onOpenShare =
                        miniHomeShareRepository?.let {
                            { navController.navigate(PlanteriorRoute.MiniHomeShare) }
                        },
                    photoLoader =
                        miniHomePhotoLoader(collectionThumbnailLoader, catalogMediaLoader),
                    authOwnership = miniHomeAuthOwnership,
                    onStateObserved = { observeDebugMiniHomeState(context, it) },
                )
            }
        }
        composable<PlanteriorRoute.MiniHomeShare> {
            if (miniHomeShareRepository == null) {
                PlaceholderScreen(
                    title = stringResource(R.string.screen_mini_home_share),
                    description = stringResource(R.string.screen_mini_home_share_description),
                )
            } else {
                MiniHomeShareRoute(
                    repository = miniHomeShareRepository,
                    onBack = { navController.popBackStack() },
                    photoLoader =
                        miniHomePhotoLoader(collectionThumbnailLoader, catalogMediaLoader),
                    authOwnership = miniHomeAuthOwnership,
                )
            }
        }
        composable<PlanteriorRoute.Notifications> {
            if (wateringNotificationSettingsRepository == null) {
                PlaceholderScreen(
                    title = stringResource(R.string.screen_notifications),
                    description = stringResource(R.string.screen_notifications_description),
                )
            } else {
                WateringNotificationSettingsRoute(
                    repository = wateringNotificationSettingsRepository,
                    notificationPermissionGranted = notificationPermissionGranted,
                    canRequestNotificationPermission = canRequestNotificationPermission,
                    onRequestPermission = onRequestNotificationPermission,
                    onOpenSystemSettings = onOpenNotificationSettings,
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable<PlanteriorRoute.Weather> {
            if (weatherRepository == null || weatherLocationGateway == null) {
                PlaceholderScreen(
                    title = "날씨 관리",
                    description = "날씨 관리 기능을 준비하지 못했어요.",
                )
            } else {
                WeatherRoute(
                    repository = weatherRepository,
                    locationGateway = weatherLocationGateway,
                    permissionCapabilities = weatherPermissionCapabilities,
                    notificationPermissionGranted = notificationPermissionGranted,
                    canRequestNotificationPermission = canRequestNotificationPermission,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onBack = { navController.popBackStack() },
                    onOpenPlant = { plantId ->
                        navController.navigate(PlanteriorRoute.PlantDetail(plantId))
                    },
                    onOpenLocationSettings = onOpenLocationSettings,
                    onOpenCollection = { navController.navigateToTab(PlanteriorRoute.Collection) },
                    clock = clock,
                )
            }
        }
        composable<PlanteriorRoute.WeatherRisk> { entry ->
            val route = entry.toRoute<PlanteriorRoute.WeatherRisk>()
            if (weatherRepository == null || weatherLocationGateway == null) {
                PlaceholderScreen(
                    title = "날씨 위험 안내",
                    description = "날씨 위험 정보를 준비하지 못했어요.",
                )
            } else {
                WeatherRoute(
                    repository = weatherRepository,
                    locationGateway = weatherLocationGateway,
                    permissionCapabilities = weatherPermissionCapabilities,
                    notificationPermissionGranted = notificationPermissionGranted,
                    canRequestNotificationPermission = canRequestNotificationPermission,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onBack = { navController.popBackStack() },
                    onOpenPlant = { plantId ->
                        navController.navigate(PlanteriorRoute.PlantDetail(plantId))
                    },
                    onOpenLocationSettings = onOpenLocationSettings,
                    onOpenCollection = { navController.navigateToTab(PlanteriorRoute.Collection) },
                    focusedPlantId = route.plantId,
                    clock = clock,
                )
            }
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
                    onNotificationSettings = {
                        navController.navigate(PlanteriorRoute.Notifications)
                    },
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

/** 미니 식물원과 미니홈 공유가 같은 사진 소스를 쓰도록 한 곳에서 만든다. */
@Composable
private fun miniHomePhotoLoader(
    collectionThumbnailLoader: PlantThumbnailLoader,
    catalogMediaLoader: CatalogMediaLoader,
): MiniHomePhotoLoader =
    remember(collectionThumbnailLoader, catalogMediaLoader) {
        MiniHomePhotoLoader { request ->
            when (request) {
                is MiniHomePhotoRequest.Catalog ->
                    when (val result = catalogMediaLoader.load(request.identity)) {
                        is CatalogMediaLoadResult.Loaded -> result.bitmap
                        is CatalogMediaLoadResult.Fallback ->
                            error("Catalog media fallback: ${result.reason}")
                    }
                is MiniHomePhotoRequest.PersonalPlant ->
                    collectionThumbnailLoader.load(request.path)
            }
        }
    }

internal fun registrationAuthOwnership(
    authState: AuthUiState?,
    coordinatorAvailable: Boolean,
    enforcementEnabled: Boolean,
): RegistrationAuthOwnership {
    if (!enforcementEnabled) return RegistrationAuthOwnership.Unmanaged
    if (!coordinatorAvailable) return RegistrationAuthOwnership.Unknown
    return when (authState) {
        AuthUiState.Restoring -> RegistrationAuthOwnership.Restoring
        is AuthUiState.SignedOut -> RegistrationAuthOwnership.SignedOut
        is AuthUiState.Authenticated ->
            RegistrationAuthOwnership.Authenticated(AccountId(authState.account.uid))
        null,
        is AuthUiState.SigningIn,
        is AuthUiState.LinkConsentRequired,
        is AuthUiState.ReauthenticationRequired,
        is AuthUiState.LinkConflict,
        is AuthUiState.LinkFailure -> RegistrationAuthOwnership.Unknown
    }
}

internal fun HomeUiState.displayedFor(authOwnership: MiniHomeAuthOwnership): HomeUiState =
    when (authOwnership) {
        MiniHomeAuthOwnership.Unmanaged -> this
        MiniHomeAuthOwnership.Restoring,
        MiniHomeAuthOwnership.Unknown -> HomeUiState.Loading
        MiniHomeAuthOwnership.SignedOut -> HomeUiState.LoggedOut
        is MiniHomeAuthOwnership.Authenticated ->
            if (ownerUid == authOwnership.accountId.value) this else HomeUiState.Loading
    }

internal fun miniHomeAuthOwnership(
    authState: AuthUiState?,
    coordinatorAvailable: Boolean,
    enforcementEnabled: Boolean,
): MiniHomeAuthOwnership {
    if (!enforcementEnabled) return MiniHomeAuthOwnership.Unmanaged
    if (!coordinatorAvailable) return MiniHomeAuthOwnership.Unknown
    return when (authState) {
        AuthUiState.Restoring -> MiniHomeAuthOwnership.Restoring
        is AuthUiState.SignedOut -> MiniHomeAuthOwnership.SignedOut
        is AuthUiState.Authenticated ->
            MiniHomeAuthOwnership.Authenticated(AccountId(authState.account.uid))
        null,
        is AuthUiState.SigningIn,
        is AuthUiState.LinkConsentRequired,
        is AuthUiState.ReauthenticationRequired,
        is AuthUiState.LinkConflict,
        is AuthUiState.LinkFailure -> MiniHomeAuthOwnership.Unknown
    }
}

private fun MiniHomeAuthOwnership.toInventoryOwnership(): InventoryAuthOwnership =
    when (this) {
        MiniHomeAuthOwnership.Restoring -> InventoryAuthOwnership.Restoring
        MiniHomeAuthOwnership.Unknown -> InventoryAuthOwnership.Unknown
        MiniHomeAuthOwnership.SignedOut -> InventoryAuthOwnership.SignedOut
        MiniHomeAuthOwnership.Unmanaged -> InventoryAuthOwnership.Unmanaged
        is MiniHomeAuthOwnership.Authenticated -> InventoryAuthOwnership.Authenticated(accountId)
    }

private const val WATERING_REFRESH_KEY = "watering.refresh-operation"
