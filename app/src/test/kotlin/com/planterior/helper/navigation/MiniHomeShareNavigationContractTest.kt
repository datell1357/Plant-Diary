package com.planterior.helper.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.ROBOLECTRIC_MAX_SDK
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlacementId
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.minihome.GridPosition
import com.planterior.helper.feature.minihome.MiniHomeDiscardHandle
import com.planterior.helper.feature.minihome.MiniHomeDiscardResult
import com.planterior.helper.feature.minihome.MiniHomeLayout
import com.planterior.helper.feature.minihome.MiniHomeLoadResult
import com.planterior.helper.feature.minihome.MiniHomePlacement
import com.planterior.helper.feature.minihome.MiniHomePlacementTarget
import com.planterior.helper.feature.minihome.MiniHomePlantChoice
import com.planterior.helper.feature.minihome.MiniHomeRepository
import com.planterior.helper.feature.minihome.MiniHomeSaveRequest
import com.planterior.helper.feature.minihome.MiniHomeSaveResult
import com.planterior.helper.feature.minihome.MiniHomeTestTags
import com.planterior.helper.feature.minihome.MiniHomeZIndex
import com.planterior.helper.feature.share.MiniHomeShareCreateResult
import com.planterior.helper.feature.share.MiniHomeShareId
import com.planterior.helper.feature.share.MiniHomeShareLinkRequest
import com.planterior.helper.feature.share.MiniHomeShareLoadResult
import com.planterior.helper.feature.share.MiniHomeShareRepository
import com.planterior.helper.feature.share.MiniHomeShareRevokeResult
import com.planterior.helper.feature.share.MiniHomeShareTarget
import com.planterior.helper.feature.share.MiniHomeShareTestTags
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 미니 식물원 보기 상태의 공유 진입이 실제 nav host를 통해 인자 없는 공유 목적지를 연다. */
@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [ROBOLECTRIC_MAX_SDK],
    qualifiers = "w402dp-h874dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
class MiniHomeShareNavigationContractTest {
    @get:Rule val composeRule = createComposeRule()

    private lateinit var navController: NavHostController

    private val owner = AccountId("account-share")

    private fun layout() =
        MiniHomeLayout(
            MiniHomeId("mini-home-a"),
            "우리 집 식물원",
            listOf(
                MiniHomePlacement(
                    PlacementId("placement-a"),
                    MiniHomePlacementTarget.Plant(PersonalPlantId("plant-a")),
                    GridPosition(1, 1),
                    MiniHomeZIndex(0),
                )
            ),
            Revision(7),
            Instant.ofEpochMilli(1_700_000_000_000L),
        )

    @Test
    fun `mini home viewing footer opens the argument free share destination`() {
        start()

        composeRule.onNodeWithTag(MiniHomeTestTags.SHARE).performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(PlanteriorRoute.MiniHomeShare, currentRoute())
        composeRule
            .onNodeWithTag(MiniHomeShareTestTags.PRIVACY_NOTICE)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `share destination reloads authoritative committed state instead of reusing the mini home load`() {
        val shareRepository = FakeShareRepository()
        start(shareRepository = shareRepository)
        assertEquals(0, shareRepository.loadCount)

        composeRule.onNodeWithTag(MiniHomeTestTags.SHARE).performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(1, shareRepository.loadCount)
    }

    @Test
    fun `back from the share destination returns to the mini home destination`() {
        start()
        composeRule.onNodeWithTag(MiniHomeTestTags.SHARE).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MiniHomeShareTestTags.BACK).performClick()
        composeRule.waitForIdle()

        assertEquals(PlanteriorRoute.MiniHome, currentRoute())
    }

    @Test
    fun `share route never carries the link or any owner data in its back stack entry`() {
        start()
        composeRule.onNodeWithTag(MiniHomeTestTags.SHARE).performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(PlanteriorRoute.MiniHomeShare, currentRoute())
        assertNotEquals(PlanteriorRoute.MiniHome, currentRoute())
        val destinationRoute = navController.currentBackStackEntry?.destination?.route.orEmpty()
        assertEquals(PlanteriorRoute.MiniHomeShare::class.qualifiedName, destinationRoute)
        // 인자 자리 표시자가 없어야 링크나 소유자가 route로 흘러들 수 없다.
        assertEquals(false, destinationRoute.contains('/'))
        assertEquals(false, destinationRoute.contains('?'))
        assertEquals(false, destinationRoute.contains('{'))
    }

    @Test
    fun `share entry is absent when no share repository is wired`() {
        start(shareRepository = null)

        composeRule.onNodeWithTag(MiniHomeTestTags.SHARE).assertDoesNotExist()
    }

    private fun start(shareRepository: MiniHomeShareRepository? = FakeShareRepository()) {
        composeRule.setContent {
            PlanteriorTheme {
                navController = rememberNavController()
                PlanteriorNavHost(
                    navController = navController,
                    startRoute = PlanteriorRoute.MiniHome,
                    miniHomeRepository = FakeMiniHomeRepository(),
                    miniHomeShareRepository = shareRepository,
                    miniHomeAuthOwnershipOverride =
                        com.planterior.helper.feature.minihome.MiniHomeAuthOwnership.Authenticated(
                            owner
                        ),
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun currentRoute(): PlanteriorRoute? =
        navController.currentBackStackEntry.toPlanteriorRoute()

    private inner class FakeMiniHomeRepository : MiniHomeRepository {
        override suspend fun load(): MiniHomeLoadResult =
            MiniHomeLoadResult.Ready(
                accountId = owner,
                committed = layout(),
                plants = listOf(MiniHomePlantChoice(PersonalPlantId("plant-a"), "몬스테라", null)),
                decorations = emptyList(),
                stale = false,
                pending = null,
            )

        override suspend fun save(request: MiniHomeSaveRequest): MiniHomeSaveResult =
            MiniHomeSaveResult.Forbidden

        override suspend fun abandon(handle: MiniHomeDiscardHandle): MiniHomeDiscardResult =
            MiniHomeDiscardResult.Rejected
    }

    private inner class FakeShareRepository : MiniHomeShareRepository {
        var loadCount = 0

        override suspend fun loadCommitted(): MiniHomeShareLoadResult {
            loadCount += 1
            return MiniHomeShareLoadResult.Ready(
                MiniHomeShareTarget(
                    owner = owner,
                    committed = layout(),
                    plants = listOf(MiniHomePlantChoice(PersonalPlantId("plant-a"), "몬스테라", null)),
                    decorations = emptyList(),
                )
            )
        }

        override suspend fun createLink(
            request: MiniHomeShareLinkRequest
        ): MiniHomeShareCreateResult =
            MiniHomeShareCreateResult.Failed(
                com.planterior.helper.feature.share.MiniHomeShareFailure.OFFLINE
            )

        override suspend fun revokeLink(shareId: MiniHomeShareId): MiniHomeShareRevokeResult =
            MiniHomeShareRevokeResult.Failed(
                com.planterior.helper.feature.share.MiniHomeShareFailure.OFFLINE
            )
    }
}
