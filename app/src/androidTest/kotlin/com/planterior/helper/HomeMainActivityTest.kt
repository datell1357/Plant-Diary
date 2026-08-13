package com.planterior.helper

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.planterior.helper.core.database.CachedMiniHomeEntity
import com.planterior.helper.core.database.CachedPlantEntity
import com.planterior.helper.core.database.CachedWateringScheduleEntity
import com.planterior.helper.core.database.LastSyncEntity
import com.planterior.helper.core.database.MIGRATION_1_2
import com.planterior.helper.core.database.MIGRATION_2_3
import com.planterior.helper.core.database.MIGRATION_3_4
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.feature.home.HomeTestTags
import com.planterior.helper.feature.home.HomeUiState
import com.planterior.helper.home.SESSION_LOGGED_OUT
import com.planterior.helper.home.SESSION_RESTORING
import com.planterior.helper.home.SESSION_SIGNED_IN
import com.planterior.helper.home.WEATHER_FAILURE
import com.planterior.helper.home.WEATHER_OK
import com.planterior.helper.home.WEATHER_RISK
import com.planterior.helper.home.setDebugHomeScenario
import com.planterior.helper.home.setDebugHomeSession
import com.planterior.helper.navigation.PlanteriorRoute
import com.planterior.helper.navigation.toPlanteriorRoute
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 제품 `MainActivity`를 그대로 띄워 홈의 각 상태와 진입점을 검증한다.
 *
 * 화면 데이터는 앱이 실제로 읽는 Room 파일에 직접 넣는다. 그래서 여기서 통과한다는 것은 저장된 데이터가 제품 경로로 렌더링된다는 뜻이지, 테스트용 가짜 저장소가
 * 동작한다는 뜻이 아니다. 세션만 디버그 제어로 고정해 Firebase 계정 없이도 결정적으로 재현한다.
 */
@RunWith(AndroidJUnit4::class)
class HomeMainActivityTest {
    @get:Rule(order = 0) val composeRule = createAndroidComposeRule<MainActivity>()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val account = "qa-home-account"

    @Before
    fun setUp() {
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .grantRuntimePermission(
                    context.packageName,
                    "android.permission.ACCESS_LOCAL_NETWORK",
                )
        }
        // 앞서 돌은 테스트가 남긴 계정 데이터와 시나리오를 지워 항상 같은 지점에서 시작하게 한다.
        setDebugHomeSession(context, "")
        setDebugHomeScenario(context, "")
        withDatabase { it.cacheDao().clearVisibleAccount(account) }
    }

    @After
    fun tearDown() {
        setDebugHomeSession(context, "")
        setDebugHomeScenario(context, "")
        withDatabase { it.cacheDao().clearVisibleAccount(account) }
    }

    /** 앱이 실제로 여는 데이터베이스 파일을 그대로 연다. */
    private fun withDatabase(block: suspend (PlanteriorDatabase) -> Unit) {
        val database =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, "planterior.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
        try {
            runBlocking { block(database) }
        } finally {
            database.close()
        }
    }

    private fun seedCare() = withDatabase { database ->
        val dao = database.cacheDao()
        val today = LocalDate.now(ZoneId.systemDefault())
        listOf(
                Triple("plant-today", "몬몬이 (몬스테라)", today),
                Triple("plant-overdue", "지연이 (스킨답서스)", today.minusDays(2)),
                Triple("plant-upcoming", "뾰족이 (스투키)", today.plusDays(3)),
            )
            .forEach { (id, name, due) ->
                dao.upsertPlant(CachedPlantEntity(account, id, name, null, 1, 1))
                dao.upsertSchedule(
                    CachedWateringScheduleEntity(
                        account,
                        "schedule-$id",
                        id,
                        due.toString(),
                        "09:00",
                        ZoneId.systemDefault().id,
                        1,
                        1,
                    )
                )
            }
        database
            .syncDao()
            .upsertLastSync(LastSyncEntity(account, "PLANTS", 1_786_500_000_000, "SUCCESS", null))
    }

    private fun seedMiniHome(name: String, placed: Int) = withDatabase { database ->
        database
            .cacheDao()
            .upsertMiniHome(CachedMiniHomeEntity(account, "mini-home", name, placed, 1, 1))
    }

    private fun markSyncFailed() = withDatabase { database ->
        database
            .syncDao()
            .upsertLastSync(
                LastSyncEntity(account, "MINI_HOME", 1_786_500_000_000, "FAILED", "unavailable")
            )
    }

    private fun signedIn() = setDebugHomeSession(context, SESSION_SIGNED_IN, account, "민지")

    /** Activity 재생성과 홈 상태 구독을 트리거 전에 걸고 새 인스턴스의 실제 복원 상태를 기다린다. */
    private fun relaunch(expectedState: (HomeUiState) -> Boolean) {
        val application = context as Application
        val previousActivity = composeRule.activity
        val resumedActivity = AtomicReference<MainActivity?>()
        val restoredState = AtomicReference<HomeUiState?>()
        val resumedSignal = CountDownLatch(1)
        val stateSignal = CountDownLatch(1)
        val callbacks =
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
                    Unit

                override fun onActivityPostCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) {
                    if (activity !is MainActivity || activity === previousActivity) return
                    activity.lifecycleScope.launch {
                        restoredState.set(activity.homeViewModel.state.first(expectedState))
                        stateSignal.countDown()
                    }
                }

                override fun onActivityResumed(activity: Activity) {
                    if (activity is MainActivity && activity !== previousActivity) {
                        resumedActivity.set(activity)
                        resumedSignal.countDown()
                    }
                }

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) =
                    Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            }

        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            composeRule.activityRule.scenario.recreate()
            assertTrue(
                "새 MainActivity가 RESUMED 상태가 되어야 한다",
                resumedSignal.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertTrue(
                "새 MainActivity의 홈 상태가 복원되어야 한다",
                stateSignal.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }

        val restoredActivity = checkNotNull(resumedActivity.get())
        assertNotSame(previousActivity, restoredActivity)
        assertSame(restoredActivity, composeRule.activity)
        assertTrue(expectedState(checkNotNull(restoredState.get())))
        composeRule.waitForIdle()
    }

    @Test
    fun loggedOutHomeOffersSignInAndHidesAllPlantData() {
        setDebugHomeSession(context, SESSION_LOGGED_OUT)
        relaunch { it is HomeUiState.LoggedOut }

        composeRule.onNodeWithText("안녕하세요, 게스트님!").assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTestTags.SIGN_IN).assertIsDisplayed()
        assertEquals(
            "로그인 전에는 관리 섹션이 없어야 한다",
            0,
            composeRule.onAllNodesWithTag(HomeTestTags.CARE_SECTION).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun restoringSessionDoesNotLatchTheLoggedOutHome() {
        setDebugHomeSession(context, SESSION_RESTORING)
        relaunch { it is HomeUiState.Loading }

        // 복원 중에는 로그인 유도를 확정적으로 그리면 안 된다.
        assertEquals(
            "복원 중에 로그아웃 홈을 그리면 안 된다",
            0,
            composeRule.onAllNodesWithTag(HomeTestTags.SIGN_IN).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun emptyHomeNeverInventsSamplePlants() {
        signedIn()
        setDebugHomeScenario(context, WEATHER_OK)
        relaunch { it is HomeUiState.Empty }

        composeRule.onNodeWithTag(HomeTestTags.EMPTY).performScrollTo().assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithTag(HomeTestTags.CARE_SECTION).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun contentHomeRendersPersistedCareInTodayThenOverdueThenUpcomingOrder() {
        seedCare()
        signedIn()
        setDebugHomeScenario(context, WEATHER_OK)
        relaunch { it is HomeUiState.Content }

        composeRule.onNodeWithTag(HomeTestTags.CARE_SECTION).performScrollTo().assertIsDisplayed()
        val order =
            listOf("plant-today", "plant-overdue", "plant-upcoming").map { id ->
                composeRule
                    .onNodeWithTag("${HomeTestTags.CARE_ITEM}:$id")
                    .performScrollTo()
                    .fetchSemanticsNode()
                    .positionInRoot
                    .y
            }
        assertTrue("오늘이 지연보다 위에 있어야 한다", order[0] < order[1])
        assertTrue("지연이 예정보다 위에 있어야 한다", order[1] < order[2])
    }

    @Test
    fun persistedMiniHomePreviewIsRenderedByTheProductScreen() {
        seedCare()
        seedMiniHome("민지의 미니 식물원", 3)
        signedIn()
        setDebugHomeScenario(context, WEATHER_OK)
        relaunch { it is HomeUiState.Content }

        // 저장된 구성이 제품 화면에 그대로 나타나야 한다.
        composeRule.onNodeWithText("민지의 미니 식물원").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("배치한 식물 3개").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun miniHomePreviewSurvivesActivityRecreation() {
        // 미니홈피 미리보기는 등록된 식물이 있는 콘텐츠 홈에서 그려진다.
        seedCare()
        seedMiniHome("복원된 미니 식물원", 2)
        signedIn()
        relaunch { it is HomeUiState.Content }
        composeRule.onNodeWithText("복원된 미니 식물원").performScrollTo().assertIsDisplayed()

        relaunch { it is HomeUiState.Content }

        composeRule.onNodeWithText("복원된 미니 식물원").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("배치한 식물 2개").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun weatherFailureKeepsEveryPersistedCareItem() {
        seedCare()
        signedIn()
        setDebugHomeScenario(context, WEATHER_FAILURE)
        relaunch { it is HomeUiState.Content }

        composeRule
            .onNodeWithTag(HomeTestTags.WEATHER_UNAVAILABLE)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag("${HomeTestTags.CARE_ITEM}:plant-today")
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(
            "날씨가 없으면 위험 배너도 없어야 한다",
            0,
            composeRule.onAllNodesWithTag(HomeTestTags.WEATHER_RISK).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun onlyTheHighestPriorityWeatherRiskIsShown() {
        seedCare()
        signedIn()
        setDebugHomeScenario(context, WEATHER_RISK)
        relaunch { it is HomeUiState.Content }

        composeRule.onNodeWithTag(HomeTestTags.WEATHER_RISK).performScrollTo().assertIsDisplayed()
        assertEquals(
            1,
            composeRule.onAllNodesWithTag(HomeTestTags.WEATHER_RISK).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun staleSyncShowsCachedCareWithTheLastSyncNotice() {
        seedCare()
        markSyncFailed()
        signedIn()
        setDebugHomeScenario(context, WEATHER_OK)
        relaunch { it is HomeUiState.Content }

        composeRule.onNodeWithTag(HomeTestTags.SYNC_STALE).performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithTag("${HomeTestTags.CARE_ITEM}:plant-today")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun everyHomeEntryPointReachesItsDestinationAndComesBack() {
        seedCare()
        seedMiniHome("민지의 미니 식물원", 1)
        signedIn()
        setDebugHomeScenario(context, WEATHER_OK)
        relaunch { it is HomeUiState.Content }

        navigateTo(PlanteriorRoute.MiniHome) {
            composeRule.onNodeWithTag(HomeTestTags.MINI_HOME).performScrollTo().performClick()
        }
        composeRule.onNodeWithText("미니 식물원").assertIsDisplayed()
        pressBack()

        navigateTo(PlanteriorRoute.Notifications) {
            composeRule.onNodeWithTag(HomeTestTags.NOTIFICATION).performClick()
        }
        composeRule.onNodeWithText("알림").assertIsDisplayed()
        pressBack()

        navigateTo(PlanteriorRoute.Camera) {
            composeRule.onNodeWithTag(HomeTestTags.IDENTIFY_CTA).performScrollTo().performClick()
        }
        composeRule.onNodeWithText("식물 촬영").assertIsDisplayed()
        pressBack()

        navigateTo(PlanteriorRoute.Collection) {
            composeRule.onNodeWithContentDescription("도감").performClick()
        }
        composeRule.onNodeWithText("도감").assertIsDisplayed()

        navigateTo(PlanteriorRoute.Settings) {
            composeRule.onNodeWithContentDescription("설정").performClick()
        }
        composeRule.onNodeWithText("설정").assertIsDisplayed()

        navigateTo(PlanteriorRoute.Home) {
            composeRule.onNodeWithContentDescription("홈").performClick()
        }
        assertHome()
    }

    @Test
    fun aHostileExternalRouteResolvesToHome() {
        // 외부 딥링크 해석은 제품 resolver가 담당한다. 새 Activity를 띄우면 시나리오 정리가 깨지므로
        // 실제 진입 경로와 동일한 입력을 그대로 해석시켜 홈으로 안전 복귀하는지 확인한다.
        val hostile =
            listOf(
                "planterior://collection/plant/../../etc/passwd",
                "planterior://unknown",
                "https://evil.example.com/planterior://home",
                "planterior://collection/plant/" + "a".repeat(65),
            )

        hostile.forEach { uri ->
            assertEquals(
                "적대적 route는 홈으로 되돌아야 한다: $uri",
                com.planterior.helper.navigation.PlanteriorRoute.Home,
                com.planterior.helper.navigation.PlanteriorRouteResolver.resolve(uri),
            )
        }

        signedIn()
        setDebugHomeScenario(context, WEATHER_OK)
        relaunch { it is HomeUiState.Empty }
        assertHome()
    }

    private fun pressBack() {
        navigateTo(PlanteriorRoute.Home) {
            composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        }
        assertHome()
    }

    /** 트리거 전에 실제 NavController listener를 붙이고 정확한 새 목적지 하나만 받는다. */
    private fun navigateTo(expectedRoute: PlanteriorRoute, trigger: () -> Unit) {
        val controller = composeRule.activity.navigationController
        val previousEntry = controller.currentBackStackEntry
        ExactEventSubscription<RouteEvent>(
                matches = { it.route == expectedRoute },
                subscribe = { receiver -> subscribeToDestinations(controller, receiver) },
            )
            .use { subscription ->
                subscription.arm()
                trigger()
                val observed =
                    subscription.await(
                        EVENT_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS,
                        expectedRoute.toString(),
                    )
                composeRule.waitForIdle()
                composeRule.runOnIdle {
                    assertEquals(
                        expectedRoute,
                        controller.currentBackStackEntry.toPlanteriorRoute(),
                    )
                    assertSame(observed.entry, controller.currentBackStackEntry)
                    assertNotSame(previousEntry, observed.entry)
                }
            }
    }

    private fun subscribeToDestinations(
        controller: NavController,
        receiver: (RouteEvent) -> Unit,
    ): ExactEventRegistration {
        // NavController는 destination callback과 listener 제거를 모두 main thread에서 직렬 실행한다.
        // 같은 main queue의 제거가 반환되면 먼저 캡처된 callback도 모두 반환되었고 이후 callback은 불가능하다.
        val listener = NavController.OnDestinationChangedListener { current, _, _ ->
            val entry = current.currentBackStackEntry
            receiver(RouteEvent(entry.toPlanteriorRoute(), entry))
        }
        composeRule.runOnIdle { controller.addOnDestinationChangedListener(listener) }
        return ExactEventRegistration {
            composeRule.runOnIdle { controller.removeOnDestinationChangedListener(listener) }
        }
    }

    /** 탭 복귀는 스크롤 위치도 복원하므로 인사말을 화면 안으로 옮긴 뒤 단언한다. */
    private fun assertHome() {
        composeRule.onNodeWithTag(HomeTestTags.GREETING).performScrollTo().assertIsDisplayed()
    }

    private data class RouteEvent(
        val route: PlanteriorRoute?,
        val entry: NavBackStackEntry?,
    )

    private companion object {
        const val EVENT_TIMEOUT_SECONDS = 10L
    }
}
