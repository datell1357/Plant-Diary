package com.planterior.helper

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.planterior.helper.auth.AuthRuntime
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.feature.home.HomeViewModel
import com.planterior.helper.navigation.AuthRouteGuard
import com.planterior.helper.navigation.PlanteriorNavHost
import com.planterior.helper.navigation.PlanteriorRoute
import com.planterior.helper.navigation.PlanteriorRouteResolver
import java.net.URI
import java.time.Clock
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var authRuntime: AuthRuntime

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
        homeViewModel = HomeViewModel(authRuntime.homeRepository, Clock.systemDefaultZone())
        val requested = PlanteriorRouteResolver.resolve(intent.deepLinkUri())
        val target = AuthRouteGuard.destination(requested, authRuntime.hasSession)
        lifecycleScope.launch {
            authRuntime.coordinator.restore()
            intent
                .deepLinkUri()
                ?.let { runCatching { URI(it) }.getOrNull() }
                ?.takeIf { it.scheme == "planterior" && it.host == "auth" && it.path == "/apple" }
                ?.let { authRuntime.handleAppleCallback(it) }
        }
        setContent {
            PlanteriorApp(
                target = target,
                authRuntime = authRuntime,
                homeViewModel = homeViewModel,
                onNavigationReady = { navigationController = it },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val callback = intent.deepLinkUri()?.let { runCatching { URI(it) }.getOrNull() } ?: return
        lifecycleScope.launch { authRuntime.handleAppleCallback(callback) }
    }
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
