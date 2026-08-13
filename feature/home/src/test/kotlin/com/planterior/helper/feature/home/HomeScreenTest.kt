package com.planterior.helper.feature.home

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 홈 화면의 네 가지 필수 상태를 Figma 뷰포트(402x874dp)에서 검증한다.
 *
 * 스크린샷 승인만으로 통과했다고 주장하지 않도록, 각 상태에서 실제로 존재해야 하는 노드와 존재하면 안 되는 노드를 함께 단언한다.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [ROBOLECTRIC_MAX_SDK],
    qualifiers = "w402dp-h874dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
class HomeScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private val strings = HomeStrings.Korean

    private fun show(
        state: HomeUiState,
        onSignIn: () -> Unit = {},
        onNotifications: () -> Unit = {},
        onIdentify: () -> Unit = {},
        onOpenMiniHome: () -> Unit = {},
        onOpenCollection: () -> Unit = {},
        onOpenPlant: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                HomeScreen(
                    state = state,
                    onSignIn = onSignIn,
                    onNotifications = onNotifications,
                    onIdentify = onIdentify,
                    onOpenMiniHome = onOpenMiniHome,
                    onOpenCollection = onOpenCollection,
                    onOpenPlant = onOpenPlant,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun content(
        careItems: List<HomeCareItem> =
            listOf(HomeCareItem("p-1", "몬몬이 (몬스테라)", HomeCareStatus.DueToday)),
        weather: HomeWeatherState = HomeWeatherState.Available("서울 성동구", 28.0, NOW, null),
        sync: HomeSyncState = HomeSyncState.Fresh,
        miniHome: HomeMiniHomePreview? = HomeMiniHomePreview("민지의 미니 식물원", 2),
    ) =
        HomeUiState.Content(
            greetingName = "민지",
            careItems = careItems,
            dueTodayCount = careItems.count { it.status == HomeCareStatus.DueToday },
            miniHome = miniHome,
            weather = weather,
            sync = sync,
        )

    @Test
    fun `logged out home offers sign in and never renders plant care`() {
        show(HomeUiState.LoggedOut)

        composeRule.onNodeWithTag(HomeTestTags.GREETING).assertIsDisplayed()
        composeRule.onNodeWithText("안녕하세요, 게스트님!").assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTestTags.SIGN_IN).assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithTag(HomeTestTags.CARE_SECTION).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `empty home shows the registration guidance and zero care cards`() {
        show(
            HomeUiState.Empty(
                greetingName = "민지",
                weather = HomeWeatherState.Available("서울 성동구", 28.0, NOW, null),
                sync = HomeSyncState.Fresh,
            )
        )

        composeRule.onNodeWithText("안녕하세요, 민지님!").assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTestTags.EMPTY).performScrollTo().assertIsDisplayed()
        assertEquals(
            "빈 홈에는 관리 카드가 하나도 없어야 한다",
            0,
            composeRule.onAllNodesWithTag(HomeTestTags.CARE_SECTION).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `content home renders every care item in the given order`() {
        show(
            content(
                careItems =
                    listOf(
                        HomeCareItem("p-today", "몬몬이 (몬스테라)", HomeCareStatus.DueToday),
                        HomeCareItem("p-overdue", "지연이", HomeCareStatus.Overdue(2)),
                        HomeCareItem("p-upcoming", "뾰족이 (스투키)", HomeCareStatus.Upcoming(3)),
                    )
            )
        )

        composeRule.onNodeWithTag(HomeTestTags.CARE_SECTION).performScrollTo().assertIsDisplayed()
        listOf("p-today", "p-overdue", "p-upcoming").forEach { id ->
            composeRule
                .onNodeWithTag("${HomeTestTags.CARE_ITEM}:$id")
                .performScrollTo()
                .assertIsDisplayed()
        }
        composeRule.onNodeWithText("오늘 물 주는 날").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("3일 후 물주기").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `weather partial failure keeps care cards and states the degradation`() {
        show(content(weather = HomeWeatherState.Unavailable))

        composeRule
            .onNodeWithTag(HomeTestTags.WEATHER_UNAVAILABLE)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag("${HomeTestTags.CARE_ITEM}:p-1")
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(
            "날씨가 없으면 위험 배너도 없어야 한다",
            0,
            composeRule.onAllNodesWithTag(HomeTestTags.WEATHER_RISK).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `the highest priority weather risk is the only banner rendered`() {
        show(
            content(
                weather =
                    HomeWeatherState.Available(
                        regionName = "서울 성동구",
                        temperatureCelsius = 35.0,
                        observedAt = NOW,
                        topRisk = HomeWeatherRisk.HighTemperature("오늘 기온이 35°C로 높아요!"),
                    )
            )
        )

        composeRule.onNodeWithTag(HomeTestTags.WEATHER_RISK).performScrollTo().assertIsDisplayed()
        assertEquals(
            1,
            composeRule.onAllNodesWithTag(HomeTestTags.WEATHER_RISK).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `a stale synchronization shows the last successful sync alongside cached cards`() {
        show(content(sync = HomeSyncState.Stale(Instant.parse("2026-08-10T02:30:00Z"))))

        composeRule.onNodeWithTag(HomeTestTags.SYNC_STALE).performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithTag("${HomeTestTags.CARE_ITEM}:p-1")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `tapping a care card reports the plant id exactly once`() {
        val opened = mutableListOf<String>()
        show(content(), onOpenPlant = { opened += it })

        composeRule.onNodeWithTag("${HomeTestTags.CARE_ITEM}:p-1").performScrollTo().performClick()

        assertEquals(listOf("p-1"), opened)
    }

    @Test
    fun `the identification call to action is reachable from every signed in state`() {
        var identifyClicks = 0
        show(content(), onIdentify = { identifyClicks++ })

        composeRule.onNodeWithTag(HomeTestTags.IDENTIFY_CTA).performScrollTo().performClick()

        assertEquals(1, identifyClicks)
    }

    @Test
    fun `the mini home preview opens the mini home rather than a plant`() {
        var miniHomeClicks = 0
        val opened = mutableListOf<String>()
        show(content(), onOpenMiniHome = { miniHomeClicks++ }, onOpenPlant = { opened += it })

        composeRule.onNodeWithTag(HomeTestTags.MINI_HOME).performScrollTo().performClick()

        assertEquals(1, miniHomeClicks)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `the notification entry point is reachable and named for screen readers`() {
        var notificationClicks = 0
        show(content(), onNotifications = { notificationClicks++ })

        composeRule.onNodeWithContentDescription(strings.notifications).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTestTags.NOTIFICATION).performClick()

        assertEquals(1, notificationClicks)
    }

    @Test
    fun `the notification entry point exists before login as well`() {
        var notificationClicks = 0
        show(HomeUiState.LoggedOut, onNotifications = { notificationClicks++ })

        composeRule.onNodeWithTag(HomeTestTags.NOTIFICATION).performClick()

        assertEquals(1, notificationClicks)
    }

    @Test
    fun `every home action meets the minimum accessible touch target`() {
        show(content())

        listOf(
                HomeTestTags.IDENTIFY_CTA,
                HomeTestTags.NOTIFICATION,
                "${HomeTestTags.CARE_ITEM}:p-1",
            )
            .forEach { tag ->
                composeRule
                    .onNodeWithTag(tag)
                    .performScrollTo()
                    .assertHeightIsAtLeast(48.dp)
                    .assertWidthIsAtLeast(48.dp)
            }
    }

    @Test
    fun `the error state never renders care cards`() {
        show(HomeUiState.Error(HomeSyncState.Stale(null)))

        composeRule.onNodeWithTag(HomeTestTags.ERROR).performScrollTo().assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithTag(HomeTestTags.CARE_SECTION).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `a long CJK plant name stays inside the card without pushing the badge off screen`() {
        show(
            content(
                careItems =
                    listOf(
                        HomeCareItem(
                            "p-long",
                            "아주아주기다란한국어식물이름몬스테라델리시오사바리에가타",
                            HomeCareStatus.DueToday,
                        )
                    )
            )
        )

        val card =
            composeRule
                .onNodeWithTag("${HomeTestTags.CARE_ITEM}:p-long")
                .performScrollTo()
                .fetchSemanticsNode()
        val root = composeRule.onRoot().fetchSemanticsNode()
        assertTrue(
            "긴 이름 카드가 화면 밖으로 넘치면 안 된다",
            card.boundsInRoot.right <= root.boundsInRoot.right + 1f,
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-12T00:00:00Z")
    }
}
