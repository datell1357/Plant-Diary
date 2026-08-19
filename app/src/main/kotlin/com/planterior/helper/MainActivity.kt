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
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.planterior.helper.auth.AuthRuntime
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.feature.auth.AuthUiState
import com.planterior.helper.feature.home.HomeViewModel
import com.planterior.helper.navigation.NotificationStackNavigator
import com.planterior.helper.navigation.NotificationTapRouter
import com.planterior.helper.navigation.PlanteriorNavHost
import com.planterior.helper.navigation.PlanteriorRoute
import com.planterior.helper.navigation.PlanteriorRouteResolver
import com.planterior.helper.navigation.replaceWithNotificationStack
import com.planterior.helper.notification.DebugNotificationInjector
import com.planterior.helper.notification.FirebaseNotificationOpenCallable
import com.planterior.helper.notification.NotificationCapabilityPublisher
import com.planterior.helper.notification.NotificationOpenConfirmationStore
import com.planterior.helper.notification.NotificationPermissionAction
import com.planterior.helper.notification.NotificationPermissionPolicy
import com.planterior.helper.notification.NotificationPermissionPreferences
import com.planterior.helper.notification.NotificationTokenStore
import com.planterior.helper.notification.NotificationWorkScheduler
import java.net.URI
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var authRuntime: AuthRuntime
    private var pendingNotificationUri: String? = null
    private lateinit var notificationOpenConfirmations: NotificationOpenConfirmationStore
    private lateinit var notificationOpenCallable: FirebaseNotificationOpenCallable
    internal var notificationPermissionAction by
        mutableStateOf(NotificationPermissionAction.NOT_REQUIRED)
        private set

    internal val pendingNotificationIntentCount: Int
        get() = if (pendingNotificationUri == null) 0 else 1

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshNotificationCapability()
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
        notificationOpenConfirmations = NotificationOpenConfirmationStore(this)
        notificationOpenCallable =
            FirebaseNotificationOpenCallable(
                com.google.firebase.functions.FirebaseFunctions.getInstance()
            )
        notificationOpenConfirmations.recordTap(intent.deepLinkUri())
        refreshNotificationCapability()
        DebugNotificationInjector.injectIfRequested(this, intent)
        homeViewModel = HomeViewModel(authRuntime.homeRepository, Clock.systemDefaultZone())
        val target =
            NotificationTapRouter.coldStartStack(intent.deepLinkUri(), authRuntime.hasSession)
                .last()
        lifecycleScope.launch {
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
                    confirmPendingNotificationOpens(state.account.uid)
                    NotificationWorkScheduler(
                            androidx.work.WorkManager.getInstance(this@MainActivity)
                        )
                        .enqueueTokenRegistration()
                }
            }
        }
        setContent {
            PlanteriorApp(
                target = target,
                authRuntime = authRuntime,
                homeViewModel = homeViewModel,
                notificationPermissionAction = notificationPermissionAction,
                onRequestNotificationPermission = ::requestNotificationPermission,
                onOpenNotificationSettings = ::openNotificationSettings,
                onNavigationReady = { controller ->
                    navigationController = controller
                    pendingNotificationUri?.let { uri ->
                        pendingNotificationUri = null
                        openNotificationUri(uri)
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
        val callback = intent.deepLinkUri()?.let { runCatching { URI(it) }.getOrNull() } ?: return
        if (callback.scheme == "planterior" && callback.host == "auth") {
            lifecycleScope.launch { authRuntime.handleAppleCallback(callback) }
            return
        }
        if (::navigationController.isInitialized) {
            openNotificationUri(callback.toString())
        } else {
            pendingNotificationUri = callback.toString()
        }
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

    private fun openNotificationUri(uri: String) {
        val authenticated = authRuntime.coordinator.state.value is AuthUiState.Authenticated
        NotificationTapRouter.openWarm(
            uri,
            authenticated,
            NotificationStackNavigator(navigationController::replaceWithNotificationStack),
        )
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
    authRuntime: AuthRuntime? = null,
    homeViewModel: HomeViewModel? = null,
    notificationPermissionAction: NotificationPermissionAction =
        NotificationPermissionAction.GRANTED,
    onRequestNotificationPermission: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onNavigationReady: (NavHostController) -> Unit = {},
) {
    PlanteriorTheme {
        val navController = rememberNavController()
        SideEffect { onNavigationReady(navController) }
        val backStack = PlanteriorRouteResolver.backStackFor(target)
        navController.RestoreDeepLinkBackStack(backStack)
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
            homeViewModel = resolvedHomeViewModel,
            registrationRepository = authRuntime?.registrationRepository,
            collectionRepository = authRuntime?.collectionRepository,
            wateringRepository = authRuntime?.wateringRepository,
            wateringNotificationSettingsRepository =
                authRuntime?.wateringNotificationSettingsRepository,
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
        )
    }
}

/** 딥링크 백스택의 두 번째 목적지부터 순서대로 쌓는다. 첫 목적지는 [PlanteriorNavHost]의 시작 목적지가 이미 담당한다. */
@Composable
private fun NavHostController.RestoreDeepLinkBackStack(backStack: List<PlanteriorRoute>) {
    androidx.compose.runtime.LaunchedEffect(backStack) {
        backStack.drop(1).forEach { route -> navigate(route) }
    }
}

/** VIEW intent가 들고 온 딥링크 문자열을 꺼낸다. 다른 intent는 딥링크가 아니다. */
private fun Intent.deepLinkUri(): String? = if (action == Intent.ACTION_VIEW) dataString else null
