package com.planterior.helper

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.planterior.helper.home.SESSION_LOGGED_OUT
import com.planterior.helper.home.SESSION_RESTORING
import com.planterior.helper.home.SESSION_SIGNED_IN
import com.planterior.helper.home.WEATHER_FAILURE
import com.planterior.helper.home.WEATHER_OK
import com.planterior.helper.home.WEATHER_RISK
import com.planterior.helper.home.setDebugHomeScenario
import com.planterior.helper.home.setDebugHomeSession
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

    /**
     * 시나리오를 적용한 뒤 Activity를 다시 만들어 제품 시작 경로를 그대로 태운다.
     *
     * 재생성 직후에는 Compose 계층이 아직 없을 수 있으므로 고정 sleep 대신 계층이 생길 때까지 조건으로 기다린다.
     */
    private fun relaunch() {
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(WAIT_TIMEOUT_MILLIS) {
            runCatching {
                composeRule.onAllNodesWithTag(HomeTestTags.GREETING).fetchSemanticsNodes()
            }
                .isSuccess
        }
        composeRule.waitForIdle()
    }

    @Test
    fun loggedOutHomeOffersSignInAndHidesAllPlantData() {
        setDebugHomeSession(context, SESSION_LOGGED_OUT)
        relaunch()

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
        relaunch()

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
        relaunch()

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
        relaunch()

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
        relaunch()

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
        relaunch()
        composeRule.onNodeWithText("복원된 미니 식물원").performScrollTo().assertIsDisplayed()

        relaunch()

        composeRule.onNodeWithText("복원된 미니 식물원").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("배치한 식물 2개").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun weatherFailureKeepsEveryPersistedCareItem() {
        seedCare()
        signedIn()
        setDebugHomeScenario(context, WEATHER_FAILURE)
        relaunch()

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
        relaunch()

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
        relaunch()

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
        relaunch()

        composeRule.onNodeWithTag(HomeTestTags.MINI_HOME).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("미니 식물원").assertIsDisplayed()
        pressBack()

        composeRule.onNodeWithTag(HomeTestTags.NOTIFICATION).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("알림").assertIsDisplayed()
        pressBack()

        composeRule.onNodeWithTag(HomeTestTags.IDENTIFY_CTA).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("식물 촬영").assertIsDisplayed()
        pressBack()

        composeRule.onNodeWithContentDescription("도감").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("도감").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("설정").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("설정").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("홈").performClick()
        awaitHome()
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
        relaunch()
        awaitHome()
    }

    /** 뒤로 가기 후 홈으로 돌아올 때까지 기다린다. 고정 sleep 대신 조건으로만 대기한다. */
    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
        awaitHome()
    }

    /**
     * 홈 화면이 다시 앞에 나올 때까지 기다린 뒤 인사말이 보이는지 확인한다.
     *
     * 탭 복귀는 스크롤 위치까지 복원하므로 인사말이 화면 밖에 있을 수 있다. 먼저 위로 올린 뒤 단언한다.
     */
    private fun awaitHome() {
        composeRule.waitForIdle()
        composeRule.waitUntil(WAIT_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(HomeTestTags.GREETING).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(HomeTestTags.GREETING).performScrollTo().assertIsDisplayed()
    }

    private companion object {
        /** 조건 대기 상한. 이 안에 조건이 참이 되지 않으면 실패로 본다. */
        const val WAIT_TIMEOUT_MILLIS = 10_000L
    }
}
