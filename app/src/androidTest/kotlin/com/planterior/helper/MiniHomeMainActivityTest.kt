package com.planterior.helper

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.IdlingPolicies
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.IdlingResource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.planterior.helper.core.database.CachedMiniHomeEntity
import com.planterior.helper.core.database.CachedPlantEntity
import com.planterior.helper.feature.minihome.GridPosition
import com.planterior.helper.feature.minihome.MiniHomeIsometricProjection
import com.planterior.helper.feature.minihome.MiniHomeTestTags
import com.planterior.helper.home.SESSION_SIGNED_IN
import com.planterior.helper.home.setDebugHomeSession
import com.planterior.helper.minihome.DebugMiniHomeStateEvent
import com.planterior.helper.minihome.DebugMiniHomeStateMode
import com.planterior.helper.minihome.OUTCOME_CONFLICT
import com.planterior.helper.minihome.OUTCOME_FAILURE
import com.planterior.helper.minihome.OUTCOME_INVALID
import com.planterior.helper.minihome.OUTCOME_SUCCESS
import com.planterior.helper.minihome.OUTCOME_UNAVAILABLE
import com.planterior.helper.minihome.currentDebugMiniHomeState
import com.planterior.helper.minihome.setDebugMiniHomeSaveOutcome
import com.planterior.helper.minihome.subscribeToDebugMiniHomeStates
import com.planterior.helper.navigation.PlanteriorRoute
import com.planterior.helper.navigation.toPlanteriorRoute
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MiniHomeMainActivityTest {
    @get:Rule(order = 0) val localNetworkPermission = Api37LocalNetworkPermissionRule()
    @get:Rule(order = 1) val session = DebugHomeSessionRule(SESSION_SIGNED_IN, ACCOUNT_A)
    @get:Rule(order = 2) val fixture = MiniHomeFixtureRule()
    @get:Rule(order = 3) val runtimeCleanup = MiniHomeRuntimeCleanupRule()
    @get:Rule(order = 4) val compose = createAndroidComposeRule<MainActivity>()

    private val application: PlanteriorApplication
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun openMiniHome() {
        compose.runOnUiThread {
            compose.activity.navigationController.navigate(PlanteriorRoute.MiniHome)
        }
        compose.waitForIdle()
    }

    @Test
    fun placement_and_savedLayout_surviveActivityRecreation_andReentry() {
        compose.onNodeWithTag(MiniHomeTestTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithText("A의 미니 식물원").assertIsDisplayed()
        compose.onNodeWithTag(MiniHomeTestTags.EDIT).performScrollTo().performClick()
        compose.onNodeWithText("몬스테라 추가").performScrollTo().performClick()
        compose.onNodeWithText("원목 스탠드 추가").performScrollTo().performClick()
        val placement = compose.onNodeWithContentDescription("식물 몬스테라", substring = true)
        placement.performScrollTo().assertIsDisplayed()
        placement.performTouchInput { swipe(center, centerRight) }
        awaitMiniHomeState(
            matches = { event ->
                event.snapshot.accountId == ACCOUNT_A &&
                    event.snapshot.placements.singleOrNull { it.targetId == "plant-a" }?.row == 1
            }
        ) {
            compose.onNodeWithTag(MiniHomeTestTags.MOVE_DOWN).performScrollTo().performClick()
        }

        recreateAfterExactState()
        compose
            .onNodeWithContentDescription("식물 몬스테라", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(MiniHomeTestTags.SAVE).performScrollTo().performClick()
        compose
            .onNodeWithText("저장했어요", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()

        val persisted = runBlocking {
            val dao = requireNotNull(application.repositoryRuntimeOrNull()).database.cacheDao()
            val home = requireNotNull(dao.miniHome(ACCOUNT_A))
            val placements = dao.miniHomePlacements(ACCOUNT_A, home.miniHomeId, home.revision)
            home.revision to placements.map { it.placementId }
        }
        assertEquals(2, persisted.second.size)
        val restored = recreateAfterExactState()
        assertEquals(persisted.first, restored.snapshot.committedRevision)
        assertEquals(persisted.second.sorted(), restored.snapshot.placements.map { it.placementId })
        compose
            .onNodeWithContentDescription("식물 몬스테라", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithContentDescription("장식 원목 스탠드", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        val evidence = requireNotNull(application.getExternalFilesDir(null)).resolve(EVIDENCE_FILE)
        evidence.outputStream().use { output ->
            check(
                compose
                    .onRoot()
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output)
            )
        }
        ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .executeShellCommand(
                        "cp ${evidence.absolutePath} /sdcard/Download/$EVIDENCE_FILE"
                    )
            )
            .use { it.readBytes() }

        setDebugMiniHomeSaveOutcome(application, OUTCOME_FAILURE)
        compose.onNodeWithTag(MiniHomeTestTags.EDIT).performScrollTo().performClick()
        compose.onNodeWithContentDescription("식물 몬스테라", substring = true).performClick()
        compose.onNodeWithTag(MiniHomeTestTags.MOVE_UP).performScrollTo().performClick()
        compose.onNodeWithTag(MiniHomeTestTags.SAVE).performScrollTo().performClick()
        compose.onNodeWithTag(MiniHomeTestTags.SAVE_FAILURE).performScrollTo().assertIsDisplayed()
        setDebugMiniHomeSaveOutcome(application, OUTCOME_SUCCESS)
        compose.onNodeWithTag(MiniHomeTestTags.RETRY).performScrollTo().performClick()
        compose
            .onNodeWithText("저장했어요", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()

        setDebugMiniHomeSaveOutcome(application, OUTCOME_CONFLICT)
        compose.onNodeWithTag(MiniHomeTestTags.EDIT).performScrollTo().performClick()
        compose.onNodeWithContentDescription("식물 몬스테라", substring = true).performClick()
        compose.onNodeWithTag(MiniHomeTestTags.MOVE_DOWN).performScrollTo().performClick()
        compose.onNodeWithTag(MiniHomeTestTags.SAVE).performScrollTo().performClick()
        compose.onNodeWithTag(MiniHomeTestTags.RECONCILE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(MiniHomeTestTags.RECONCILE).performClick()
        compose.onNodeWithText("배치 대상을 확인했어요").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("다른 기기에서 저장한 방").performScrollTo().assertIsDisplayed()
        setDebugMiniHomeSaveOutcome(application, OUTCOME_SUCCESS)
        compose.onNodeWithText("수정한 배치 저장").performScrollTo().performClick()
        compose
            .onNodeWithText("저장했어요", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()

        val ownerTransitionEvents = CopyOnWriteArrayList<DebugMiniHomeStateEvent>()
        val ownerTransitionSubscription = subscribeToDebugMiniHomeStates(ownerTransitionEvents::add)
        try {
            awaitMiniHomeState(
                matches = { event ->
                    event.snapshot.accountId == ACCOUNT_B &&
                        event.snapshot.mode == DebugMiniHomeStateMode.VIEWING &&
                        event.snapshot.name == "B의 미니 식물원"
                }
            ) {
                setDebugHomeSession(application, SESSION_SIGNED_IN, ACCOUNT_B, "다른 계정")
                compose.activityRule.scenario.recreate()
            }
        } finally {
            ownerTransitionSubscription.close()
        }
        assertTrue(ownerTransitionEvents.isNotEmpty())
        assertTrue(
            ownerTransitionEvents.none { event ->
                event.snapshot.accountId == ACCOUNT_A ||
                    event.snapshot.name?.contains("A의 미니 식물원") == true ||
                    event.snapshot.placements.any { it.targetId == "plant-a" }
            }
        )
        compose.onNodeWithText("B의 미니 식물원").assertIsDisplayed()
        assertEquals(
            PlanteriorRoute.MiniHome,
            compose.activity.navigationController.currentBackStackEntry.toPlanteriorRoute(),
        )
    }

    @Test
    fun handleless_failure_authoritatively_confirms_no_outbox_before_exit() {
        compose.onNodeWithTag(MiniHomeTestTags.EDIT).performScrollTo().performClick()
        compose.onNodeWithText("A의 미니 식물원").performTextReplacement("로컬 실패 후 종료")
        setDebugMiniHomeSaveOutcome(application, OUTCOME_FAILURE)
        compose.onNodeWithTag(MiniHomeTestTags.SAVE).performScrollTo().performClick()
        compose.onNodeWithTag(MiniHomeTestTags.SAVE_FAILURE).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("홈으로 돌아가기").performClick()
        compose.onNodeWithText("저장 안 함").performClick()
        compose.waitForIdle()

        assertTrue(
            runBlocking {
                application
                    .repositoryRuntimeOrNull()
                    ?.database
                    ?.syncDao()
                    ?.pending(ACCOUNT_A)
                    .orEmpty()
                    .none { it.aggregateType == "miniHomeLayouts" }
            }
        )
        assertEquals(
            PlanteriorRoute.Home,
            compose.activity.navigationController.currentBackStackEntry.toPlanteriorRoute(),
        )
    }

    @Test
    fun permanent_validation_keeps_name_editable_and_corrected_save_succeeds() {
        compose.onNodeWithTag(MiniHomeTestTags.EDIT).performScrollTo().performClick()
        compose.onNodeWithText("A의 미니 식물원").performTextReplacement("서버가 거절한 편집")
        setDebugMiniHomeSaveOutcome(application, OUTCOME_INVALID)

        compose.onNodeWithTag(MiniHomeTestTags.SAVE).performScrollTo().performClick()

        compose.onNodeWithText("저장 요청을 수정해야 해요").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("서버가 거절한 편집").assertIsEnabled()
        setDebugMiniHomeSaveOutcome(application, OUTCOME_SUCCESS)
        compose.onNodeWithText("서버가 거절한 편집").performTextReplacement("수정한 미니 식물원")
        compose.onNodeWithTag(MiniHomeTestTags.SAVE).performScrollTo().performClick()
        compose
            .onNodeWithText("저장했어요", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun projection_anchor_and_unavailable_reconciliation_are_verified_on_api37_surface() {
        compose.onNodeWithTag(MiniHomeTestTags.EDIT).performScrollTo().performClick()
        compose.onNodeWithText("몬스테라 추가").performScrollTo().performClick()
        compose.onNodeWithTag(MiniHomeTestTags.CANVAS).performScrollTo()
        val canvas =
            compose.onNodeWithTag(MiniHomeTestTags.CANVAS).fetchSemanticsNode().boundsInRoot
        val miniature =
            compose
                .onNodeWithContentDescription("식물 몬스테라", substring = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val expected =
            MiniHomeIsometricProjection(canvas.width, canvas.height).cellCenter(GridPosition(0, 0))
        assertTrue(abs(miniature.center.x - (canvas.left + expected.x)) <= 2f)
        assertTrue(abs(miniature.bottom - (canvas.top + expected.y)) <= 2f)

        runBlocking {
            application.repositoryRuntimeOrNull()?.database?.cacheDao()?.clearPlants(ACCOUNT_A)
        }
        setDebugMiniHomeSaveOutcome(application, OUTCOME_UNAVAILABLE)
        compose.onNodeWithTag(MiniHomeTestTags.SAVE).performScrollTo().performClick()
        compose.onNodeWithTag(MiniHomeTestTags.RECONCILE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(MiniHomeTestTags.RECONCILE).performClick()
        compose.onNodeWithText("배치 대상을 확인했어요").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("사용할 수 없는 대상 1개", substring = true).assertIsDisplayed()
    }

    private fun awaitMiniHomeState(
        matches: (DebugMiniHomeStateEvent) -> Boolean,
        trigger: () -> Unit,
    ): DebugMiniHomeStateEvent {
        val baselineSequence = requireNotNull(currentDebugMiniHomeState()).sequence
        val observed = AtomicReference<DebugMiniHomeStateEvent>()
        val reached = CountDownLatch(1)
        val subscription = subscribeToDebugMiniHomeStates { event ->
            if (
                event.sequence > baselineSequence &&
                    matches(event) &&
                    observed.compareAndSet(null, event)
            ) {
                reached.countDown()
            }
        }
        return try {
            trigger()
            compose.waitForIdle()
            check(reached.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "expected mini-home state was not rendered"
            }
            requireNotNull(observed.get())
        } finally {
            subscription.close()
        }
    }

    private fun recreateAfterExactState(): DebugMiniHomeStateEvent {
        val runtimeGeneration = application.repositoryRuntimeSnapshot().generation
        val baseline = requireNotNull(currentDebugMiniHomeState())
        val restored = RestoredMiniHomeStateIdlingResource(baseline)
        val previousPolicy = IdlingPolicies.getDynamicIdlingResourceErrorPolicy()
        IdlingPolicies.setIdlingResourceTimeout(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        IdlingRegistry.getInstance().register(restored)
        return try {
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            assertEquals(runtimeGeneration, application.repositoryRuntimeSnapshot().generation)
            restored.requireEvent()
        } finally {
            IdlingRegistry.getInstance().unregister(restored)
            restored.close()
            IdlingPolicies.setIdlingResourceTimeout(
                previousPolicy.idleTimeout,
                previousPolicy.idleTimeoutUnit,
            )
        }
    }

    private class RestoredMiniHomeStateIdlingResource(
        private val baseline: DebugMiniHomeStateEvent
    ) : IdlingResource, AutoCloseable {
        private val restored = AtomicReference<DebugMiniHomeStateEvent>()
        @Volatile private var callback: IdlingResource.ResourceCallback? = null
        private val subscription = subscribeToDebugMiniHomeStates { event ->
            if (
                event.sequence > baseline.sequence &&
                    event.activityIdentity != baseline.activityIdentity &&
                    event.snapshot == baseline.snapshot &&
                    restored.compareAndSet(null, event)
            ) {
                callback?.onTransitionToIdle()
            }
        }

        override fun getName(): String =
            "mini-home-state-${baseline.snapshot.mode}-revision-${baseline.snapshot.layoutRevision}"

        override fun isIdleNow(): Boolean = restored.get() != null

        override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback) {
            this.callback = callback
            if (isIdleNow) callback.onTransitionToIdle()
        }

        fun requireEvent(): DebugMiniHomeStateEvent = requireNotNull(restored.get())

        override fun close() = subscription.close()
    }

    class MiniHomeFixtureRule : ExternalResource() {
        override fun before() {
            val application = ApplicationProvider.getApplicationContext<PlanteriorApplication>()
            setDebugMiniHomeSaveOutcome(application, OUTCOME_SUCCESS)
            val runtime = requireNotNull(application.repositoryRuntimeOrNull())
            runtime.database.clearAllTables()
            runBlocking {
                seed(runtime, ACCOUNT_A, "A의 미니 식물원", "plant-a", "몬스테라")
                seed(runtime, ACCOUNT_B, "B의 미니 식물원", "plant-b", "스투키")
            }
        }

        override fun after() {
            setDebugMiniHomeSaveOutcome(
                ApplicationProvider.getApplicationContext(),
                OUTCOME_SUCCESS,
            )
        }

        private suspend fun seed(
            runtime: com.planterior.helper.auth.AuthRepositoryRuntime,
            account: String,
            name: String,
            plantId: String,
            plantName: String,
        ) {
            runtime.database
                .cacheDao()
                .upsertPlant(CachedPlantEntity(account, plantId, plantName, null, 1, 1))
            runtime.database
                .cacheDao()
                .upsertMiniHome(CachedMiniHomeEntity(account, "home-$account", name, 0, 1, 1))
        }
    }

    class MiniHomeRuntimeCleanupRule : ExternalResource() {
        override fun after() {
            val application = ApplicationProvider.getApplicationContext<PlanteriorApplication>()
            val runtime = application.repositoryRuntimeOrNull() ?: return
            runtime.database.clearAllTables()
            val receipt = application.shutdownRepositoryRuntime()
            assertTrue(receipt.closed)
            assertTrue(!runtime.isDatabaseOpen)
            assertTrue(!application.repositoryRuntimeSnapshot().active)
        }
    }

    private companion object {
        const val ACCOUNT_A = "mini-home-account-a"
        const val ACCOUNT_B = "mini-home-account-b"
        const val EVIDENCE_FILE = "task-13-mini-home.png"
        const val EVENT_TIMEOUT_SECONDS = 15L
    }
}
