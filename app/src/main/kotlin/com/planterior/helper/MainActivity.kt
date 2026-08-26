package com.planterior.helper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.planterior.helper.auth.AuthRuntime
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ProductEventRecorder
import com.planterior.helper.feature.auth.AuthUiState
import com.planterior.helper.feature.home.HomeViewModel
import com.planterior.helper.feature.minihome.MiniHomeAuthOwnership
import com.planterior.helper.feature.weather.LocationPermission
import com.planterior.helper.feature.weather.WeatherLocationGateway
import com.planterior.helper.navigation.NotificationStackNavigator
import com.planterior.helper.navigation.NotificationTapRouter
import com.planterior.helper.navigation.PlanteriorNavHost
import com.planterior.helper.navigation.PlanteriorRoute
import com.planterior.helper.navigation.PlanteriorRouteResolver
import com.planterior.helper.navigation.replaceWithNotificationStack
import com.planterior.helper.navigation.toPlanteriorRoute
import com.planterior.helper.notification.DebugNotificationInjector
import com.planterior.helper.notification.FirebaseNotificationOpenCallable
import com.planterior.helper.notification.NotificationCapabilityPublisher
import com.planterior.helper.notification.NotificationOpenConfirmationStore
import com.planterior.helper.notification.NotificationPermissionAction
import com.planterior.helper.notification.NotificationPermissionPolicy
import com.planterior.helper.notification.NotificationPermissionPreferences
import com.planterior.helper.notification.NotificationTokenStore
import com.planterior.helper.notification.NotificationWorkScheduler
import com.planterior.helper.weather.AndroidWeatherLocationGateway
import java.net.URI
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var authRuntime: AuthRuntime
    private var activeDeepLinkDelivery: DeepLinkDelivery? = null
    private var pendingDeepLinkDelivery: DeepLinkDelivery? = null
    private var restoreActiveDeepLinkTarget = false
    private val consumedDeepLinkIdentities = linkedSetOf<String>()
    private lateinit var notificationOpenConfirmations: NotificationOpenConfirmationStore
    private lateinit var notificationOpenCallable: FirebaseNotificationOpenCallable
    private lateinit var weatherLocationGateway: WeatherLocationGateway
    private var pendingLocationPermission: CompletableDeferred<LocationPermission>? = null
    private val navigationReady = CompletableDeferred<NavHostController>()
    internal var notificationPermissionAction by
        mutableStateOf(NotificationPermissionAction.NOT_REQUIRED)
        private set

    internal val pendingNotificationIntentCount: Int
        get() = if (pendingDeepLinkDelivery == null) 0 else 1

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshNotificationCapability()
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            pendingLocationPermission?.complete(locationPermissionState())
            pendingLocationPermission = null
        }

    /** 현재 제품 NavHost. 계측은 이 실제 컨트롤러의 목적지 이벤트를 관찰한다. */
    internal lateinit var navigationController: NavHostController
        private set

    /** 현재 제품 홈 상태. Activity 재생성 검증은 이 실제 상태 흐름을 관찰한다. */
    internal lateinit var homeViewModel: HomeViewModel
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        authRuntime = AuthRuntime.create(this)
        consumedDeepLinkIdentities +=
            savedInstanceState?.getStringArrayList(CONSUMED_DEEP_LINK_IDENTITIES).orEmpty()
        activeDeepLinkDelivery = savedInstanceState?.deepLinkDelivery() ?: intent.deepLinkDelivery()
        restoreActiveDeepLinkTarget =
            savedInstanceState?.getBoolean(ACTIVE_DEEP_LINK_WAS_CURRENT, false) == true
        notificationOpenConfirmations = NotificationOpenConfirmationStore(this)
        notificationOpenCallable =
            FirebaseNotificationOpenCallable(
                com.google.firebase.functions.FirebaseFunctions.getInstance()
            )
        notificationOpenConfirmations.recordTap(intent.deepLinkUri())
        refreshNotificationCapability()
        weatherLocationGateway =
            AndroidWeatherLocationGateway(
                this,
                ::locationPermissionState,
                ::requestLocationPermission,
            )
        authRuntime.accountDeletionRuntime?.attachLocationGateway(weatherLocationGateway)
        DebugNotificationInjector.injectIfRequested(this, intent)
        homeViewModel = HomeViewModel(authRuntime.homeRepository, Clock.systemDefaultZone())
        val target =
            NotificationTapRouter.coldStartStack(
                    activeDeepLinkDelivery?.uri,
                    authRuntime.hasSession || authRuntime.forcedHomeSession,
                )
                .last()
        lifecycleScope.launch {
            authRuntime.accountDeletionRuntime?.exits?.collect {
                navigationReady
                    .await()
                    .replaceWithNotificationStack(listOf(PlanteriorRoute.Login()))
            }
        }
        lifecycleScope.launch {
            authRuntime.accountDeletionRuntime?.retryIncompleteAfterSignedOutStartup()
            authRuntime.coordinator.restore()
            intent
                .deepLinkUri()
                ?.let { runCatching { URI(it) }.getOrNull() }
                ?.takeIf { it.scheme == "planterior" && it.host == "auth" && it.path == "/apple" }
                ?.let { authRuntime.handleAppleCallback(it) }
        }
        lifecycleScope.launch {
            authRuntime.coordinator.state.collectLatest { state ->
                if (state is AuthUiState.Authenticated) {
                    launch { authRuntime.analyticsRuntime?.consent?.load(state.account.uid) }
                    confirmPendingNotificationOpens(state.account.uid)
                    NotificationWorkScheduler(
                            androidx.work.WorkManager.getInstance(this@MainActivity)
                        )
                        .enqueueTokenRegistration()
                } else if (state is AuthUiState.SignedOut) {
                    launch { authRuntime.analyticsRuntime?.clearLocalOwner(null) }
                }
            }
        }
        setContent {
            PlanteriorApp(
                target = target,
                deferInitialDeepLink = activeDeepLinkDelivery != null,
                useAuthRouteGuard = !authRuntime.forcedHomeSession,
                authRuntime = authRuntime,
                homeViewModel = homeViewModel,
                notificationPermissionAction = notificationPermissionAction,
                onRequestNotificationPermission = ::requestNotificationPermission,
                onOpenNotificationSettings = ::openNotificationSettings,
                weatherLocationGateway = weatherLocationGateway,
                onOpenLocationSettings = ::openLocationSettings,
                onNavigationReady = { controller ->
                    navigationController = controller
                    if (!navigationReady.isCompleted) navigationReady.complete(controller)
                    restoreOrApplyActiveDeepLink()
                    pendingDeepLinkDelivery?.let { delivery ->
                        pendingDeepLinkDelivery = null
                        openNotificationDelivery(delivery)
                    }
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DebugNotificationInjector.injectIfRequested(this, intent)
        notificationOpenConfirmations.recordTap(intent.deepLinkUri())
        val delivery = intent.deepLinkDelivery() ?: return
        val callback = runCatching { URI(delivery.uri) }.getOrNull() ?: return
        if (callback.scheme == "planterior" && callback.host == "auth") {
            lifecycleScope.launch { authRuntime.handleAppleCallback(callback) }
            return
        }
        if (delivery.identity in consumedDeepLinkIdentities) return
        activeDeepLinkDelivery = delivery
        if (::navigationController.isInitialized) {
            openNotificationDelivery(delivery)
        } else if (pendingDeepLinkDelivery?.identity != delivery.identity) {
            pendingDeepLinkDelivery = delivery
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        activeDeepLinkDelivery?.let { delivery ->
            outState.putString(ACTIVE_DEEP_LINK_URI, delivery.uri)
            outState.putString(ACTIVE_DEEP_LINK_IDENTITY, delivery.identity)
            outState.putBoolean(
                ACTIVE_DEEP_LINK_WAS_CURRENT,
                isDeepLinkReflected(delivery),
            )
        }
        outState.putStringArrayList(
            CONSUMED_DEEP_LINK_IDENTITIES,
            ArrayList(consumedDeepLinkIdentities),
        )
    }

    override fun onDestroy() {
        if (::weatherLocationGateway.isInitialized) weatherLocationGateway.cancel()
        pendingLocationPermission?.cancel(CancellationException("Activity destroyed"))
        pendingLocationPermission = null
        super.onDestroy()
        if (::homeViewModel.isInitialized) homeViewModel.close()
        if (::authRuntime.isInitialized) authRuntime.close()
    }

    override fun onStart() {
        super.onStart()
        if (::authRuntime.isInitialized)
            authRuntime.analyticsRuntime?.sessionTracker?.onForeground()
    }

    override fun onStop() {
        if (::authRuntime.isInitialized)
            authRuntime.analyticsRuntime?.sessionTracker?.onBackground()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::authRuntime.isInitialized) {
            refreshNotificationCapability()
            val state = authRuntime.coordinator.state.value
            if (state is AuthUiState.Authenticated) {
                lifecycleScope.launch { confirmPendingNotificationOpens(state.account.uid) }
            }
        }
    }

    private suspend fun confirmPendingNotificationOpens(ownerUid: String) {
        try {
            notificationOpenConfirmations.confirmPending(ownerUid, notificationOpenCallable)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The ID stays persisted and is retried on the next authenticated resume.
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            NotificationPermissionPreferences(this).markRequested()
            refreshNotificationCapability()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private suspend fun requestLocationPermission(): LocationPermission {
        val current = locationPermissionState()
        if (current is LocationPermission.GrantedApproximate) return current
        val pending = pendingLocationPermission
        if (pending != null) return pending.await()
        getSharedPreferences(LOCATION_PERMISSION_PREFERENCES, MODE_PRIVATE).edit {
            putBoolean(LOCATION_PERMISSION_REQUESTED, true)
        }
        val request = CompletableDeferred<LocationPermission>()
        pendingLocationPermission = request
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        return try {
            request.await()
        } finally {
            if (pendingLocationPermission === request && request.isCancelled) {
                pendingLocationPermission = null
            }
        }
    }

    private fun locationPermissionState(): LocationPermission {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            return LocationPermission.GrantedApproximate
        }
        val requested =
            getSharedPreferences(LOCATION_PERMISSION_PREFERENCES, MODE_PRIVATE)
                .getBoolean(LOCATION_PERMISSION_REQUESTED, false)
        return LocationPermission.Denied(
            canAskAgain =
                !requested ||
                    shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    private fun openLocationSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$packageName".toUri()
            }
        )
    }

    private fun restoreOrApplyActiveDeepLink() {
        val delivery = activeDeepLinkDelivery ?: return
        if (isDeepLinkReflected(delivery)) {
            markDeepLinkConsumed(delivery.identity)
            restoreActiveDeepLinkTarget = false
            return
        }
        if (delivery.identity !in consumedDeepLinkIdentities || restoreActiveDeepLinkTarget) {
            openNotificationDelivery(delivery, allowConsumed = true)
            if (isDeepLinkReflected(delivery)) restoreActiveDeepLinkTarget = false
        }
    }

    private fun openNotificationDelivery(
        delivery: DeepLinkDelivery,
        allowConsumed: Boolean = false,
    ) {
        if (!allowConsumed && delivery.identity in consumedDeepLinkIdentities) return
        val authenticated = authRuntime.coordinator.state.value is AuthUiState.Authenticated
        val expected = NotificationTapRouter.coldStartStack(delivery.uri, authenticated).last()
        NotificationTapRouter.openWarm(
            delivery.uri,
            authenticated,
            NotificationStackNavigator(navigationController::replaceWithNotificationStack),
        )
        if (navigationController.currentBackStackEntry.toPlanteriorRoute() == expected) {
            markDeepLinkConsumed(delivery.identity)
        }
    }

    private fun isDeepLinkReflected(delivery: DeepLinkDelivery): Boolean {
        if (!::navigationController.isInitialized) return false
        val authenticated = authRuntime.coordinator.state.value is AuthUiState.Authenticated
        val expected = NotificationTapRouter.coldStartStack(delivery.uri, authenticated).last()
        return navigationController.currentBackStackEntry.toPlanteriorRoute() == expected
    }

    private fun markDeepLinkConsumed(identity: String) {
        if (identity in consumedDeepLinkIdentities) return
        if (consumedDeepLinkIdentities.size == MAX_CONSUMED_DEEP_LINK_IDENTITIES) {
            consumedDeepLinkIdentities.remove(consumedDeepLinkIdentities.first())
        }
        consumedDeepLinkIdentities += identity
    }

    private fun refreshNotificationCapability() {
        val granted = notificationPermissionGranted()
        val notificationsEnabled = NotificationCapabilityPublisher.notificationsEnabled(this)
        notificationPermissionAction =
            NotificationPermissionPolicy.action(
                Build.VERSION.SDK_INT,
                granted,
                NotificationPermissionPreferences(this).requestedBefore(),
                notificationsEnabled,
            )
        val tokenStore = NotificationTokenStore(this)
        val capabilityChanged = tokenStore.updateCapability(notificationsEnabled)
        if (
            capabilityChanged &&
                ::authRuntime.isInitialized &&
                authRuntime.coordinator.state.value is AuthUiState.Authenticated
        ) {
            NotificationWorkScheduler(androidx.work.WorkManager.getInstance(this))
                .enqueueTokenRegistration()
        }
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                data = "package:$packageName".toUri()
            }
        )
    }

    private fun notificationPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
}

/**
 * 앱 셸을 구성한다.
 *
 * cold start 딥링크로 하위 화면에 바로 들어와도 뒤로 가기가 상위 화면으로 이어지도록 부모 백스택을 먼저 쌓는다.
 *
 * @param target 딥링크에서 해석한 최종 목적지.
 */
@Composable
internal fun PlanteriorApp(
    target: PlanteriorRoute,
    deferInitialDeepLink: Boolean = false,
    useAuthRouteGuard: Boolean = true,
    authRuntime: AuthRuntime? = null,
    homeViewModel: HomeViewModel? = null,
    notificationPermissionAction: NotificationPermissionAction =
        NotificationPermissionAction.GRANTED,
    onRequestNotificationPermission: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    weatherLocationGateway: WeatherLocationGateway? = null,
    onOpenLocationSettings: () -> Unit = {},
    onNavigationReady: (NavHostController) -> Unit = {},
) {
    PlanteriorTheme {
        val navController = rememberNavController()
        SideEffect { onNavigationReady(navController) }
        val backStack = PlanteriorRouteResolver.backStackFor(target)
        navController.RestoreDeepLinkBackStack(
            backStack = backStack,
            deferred = deferInitialDeepLink,
        )
        val resolvedHomeViewModel =
            homeViewModel
                ?: authRuntime?.let { runtime ->
                    androidx.compose.runtime.remember(runtime) {
                        HomeViewModel(runtime.homeRepository, Clock.systemDefaultZone())
                    }
                }
        PlanteriorNavHost(
            navController = navController,
            startRoute = backStack.first(),
            authCoordinator = authRuntime?.coordinator,
            analyticsConsentCoordinator = authRuntime?.analyticsRuntime?.consent,
            authRouteGuardEnabled = useAuthRouteGuard,
            signedOutReturnRoute = (target as? PlanteriorRoute.Login)?.returnRoute,
            homeViewModel = resolvedHomeViewModel,
            registrationRepository = authRuntime?.registrationRepository,
            collectionRepository = authRuntime?.collectionRepository,
            miniHomeRepository = authRuntime?.miniHomeRepository,
            miniHomeShareRepository = authRuntime?.miniHomeShareRepository,
            miniHomeAuthOwnershipOverride =
                authRuntime?.forcedHomeAccountUid?.let {
                    MiniHomeAuthOwnership.Authenticated(AccountId(it))
                },
            inventoryRepository = authRuntime?.inventoryRepository,
            wateringRepository = authRuntime?.wateringRepository,
            wateringNotificationSettingsRepository =
                authRuntime?.wateringNotificationSettingsRepository,
            weatherRepository = authRuntime?.weatherRepository,
            weatherLocationGateway = weatherLocationGateway,
            weatherPermissionCapabilities = authRuntime?.weatherPermissionCapabilities,
            accountDeletionDependencies = authRuntime?.accountDeletionDependencies,
            accountDeletionDependencyFactory =
                authRuntime?.accountDeletionRuntime?.let { runtime -> runtime::dependencies },
            onOpenLocationSettings = onOpenLocationSettings,
            notificationPermissionGranted =
                notificationPermissionAction == NotificationPermissionAction.GRANTED ||
                    notificationPermissionAction == NotificationPermissionAction.NOT_REQUIRED,
            canRequestNotificationPermission =
                notificationPermissionAction == NotificationPermissionAction.REQUEST,
            onRequestNotificationPermission = onRequestNotificationPermission,
            onOpenNotificationSettings = onOpenNotificationSettings,
            collectionThumbnailLoader =
                authRuntime?.collectionThumbnailLoader
                    ?: com.planterior.helper.feature.collection.PlaceholderPlantThumbnailLoader,
            catalogMediaLoader =
                authRuntime?.catalogMediaLoader
                    ?: com.planterior.helper.feature.shop.PlaceholderCatalogMediaLoader,
            productEventRecorder =
                authRuntime?.analyticsRuntime?.recorder ?: ProductEventRecorder {},
        )
    }
}

/** Activity가 intent identity를 조정하는 경우를 제외하고 canonical 초기 stack을 구성한다. */
@Composable
private fun NavHostController.RestoreDeepLinkBackStack(
    backStack: List<PlanteriorRoute>,
    deferred: Boolean,
) {
    androidx.compose.runtime.LaunchedEffect(backStack, deferred) {
        val current = currentBackStackEntry.toPlanteriorRoute()
        if (!deferred && current == backStack.first() && current != backStack.last()) {
            replaceWithNotificationStack(backStack)
        }
    }
}

private data class DeepLinkDelivery(
    val uri: String,
    val identity: String,
)

/** VIEW intent가 들고 온 딥링크 문자열을 꺼낸다. 다른 intent는 딥링크가 아니다. */
private fun Intent.deepLinkUri(): String? = if (action == Intent.ACTION_VIEW) dataString else null

private fun Intent.deepLinkDelivery(): DeepLinkDelivery? {
    val uri = deepLinkUri() ?: return null
    return DeepLinkDelivery(uri, identifier?.takeIf(String::isNotBlank) ?: "uri:$uri")
}

private fun Bundle.deepLinkDelivery(): DeepLinkDelivery? {
    val uri = getString(ACTIVE_DEEP_LINK_URI) ?: return null
    val identity = getString(ACTIVE_DEEP_LINK_IDENTITY) ?: return null
    return DeepLinkDelivery(uri, identity)
}

private const val LOCATION_PERMISSION_PREFERENCES = "weather-location-permission"
private const val LOCATION_PERMISSION_REQUESTED = "requested"
private const val ACTIVE_DEEP_LINK_URI = "deep-link.active.uri"
private const val ACTIVE_DEEP_LINK_IDENTITY = "deep-link.active.identity"
private const val ACTIVE_DEEP_LINK_WAS_CURRENT = "deep-link.active.was-current"
private const val CONSUMED_DEEP_LINK_IDENTITIES = "deep-link.consumed-identities"
private const val MAX_CONSUMED_DEEP_LINK_IDENTITIES = 64
