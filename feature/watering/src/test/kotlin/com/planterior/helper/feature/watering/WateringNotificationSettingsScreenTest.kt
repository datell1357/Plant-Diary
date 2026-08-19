package com.planterior.helper.feature.watering

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.core.model.PersonalPlantId
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-normal-long-notround-any-420dpi-keyshidden-nonav")
class WateringNotificationSettingsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `denied permission explains the alternative without hiding editable schedules`() {
        var requests = 0
        var settings = 0
        var plantEnabled = true
        show(
            state = WateringNotificationSettingsState.Ready(fixture()),
            permissionGranted = false,
            canRequestPermission = true,
            onRequestPermission = { requests++ },
            onOpenSettings = { settings++ },
            onPlantEnabled = { _, enabled -> plantEnabled = enabled },
        )

        composeRule.onNodeWithText("권한이 없어도 앱에서 예정일을 확인하고 물 주기를 완료할 수 있어요.").assertIsDisplayed()
        composeRule.onNodeWithText("알림 허용").performClick()
        composeRule.onNodeWithText("기기 설정").assertDoesNotExist()
        composeRule
            .onNodeWithTag(WateringNotificationSettingsTestTags.plantEnabled("plant-a"))
            .performClick()

        assertEquals(1, requests)
        assertEquals(0, settings)
        assertEquals(false, plantEnabled)
    }

    @Test
    fun `previously denied permission exposes settings instead of requesting again`() {
        show(
            state = WateringNotificationSettingsState.Ready(fixture()),
            permissionGranted = false,
            canRequestPermission = false,
        )

        composeRule.onNodeWithText("알림 허용").assertDoesNotExist()
        composeRule.onNodeWithText("기기 설정").assertIsDisplayed()
    }

    @Test
    fun `pre android thirteen disabled app notifications show settings while schedules stay editable`() {
        var settings = 0
        var plantEnabled = true
        show(
            state = WateringNotificationSettingsState.Ready(fixture()),
            permissionGranted = false,
            canRequestPermission = false,
            onOpenSettings = { settings += 1 },
            onPlantEnabled = { _, enabled -> plantEnabled = enabled },
        )

        composeRule.onNodeWithText("기기 설정").performClick()
        composeRule
            .onNodeWithTag(WateringNotificationSettingsTestTags.plantEnabled("plant-a"))
            .performClick()

        assertEquals(1, settings)
        assertEquals(false, plantEnabled)
    }

    @Test
    fun `save failure is announced as an accessible error`() {
        show(
            state =
                WateringNotificationSettingsState.SaveFailed(
                    confirmed = fixture(),
                    draft = fixture().copy(global = fixture().global.copy(enabled = false)),
                ),
            permissionGranted = true,
        )

        composeRule
            .onNodeWithText("알림 설정을 저장하지 못했어요")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Error,
                    "편집한 값은 그대로 두었어요. 같은 설정으로 다시 시도해 주세요.",
                )
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Assertive,
                )
            )
    }

    @Test
    fun `saving locks the full draft and announces loading feedback`() {
        show(
            state = WateringNotificationSettingsState.Saving(fixture(), fixture()),
            permissionGranted = true,
        )

        composeRule
            .onNodeWithTag(WateringNotificationSettingsTestTags.SAVE)
            .assertIsNotEnabled()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "저장 중, 편집 잠김",
                )
            )
        composeRule.onNodeWithText("저장 중").assertIsDisplayed()
    }

    @Test
    fun `permission actions remain usable at large font scale on compact height`() {
        composeRule.setContent {
            val density = LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDensity provides
                    androidx.compose.ui.unit.Density(density.density, fontScale = 2f)
            ) {
                com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                    WateringNotificationSettingsScreen(
                        state = WateringNotificationSettingsState.Ready(fixture()),
                        notificationPermissionGranted = false,
                        canRequestNotificationPermission = false,
                        onRequestPermission = {},
                        onOpenSystemSettings = {},
                        onBack = {},
                        onGlobalEnabled = {},
                        onDefaultTime = {},
                        onPlantEnabled = { _, _ -> },
                        onPlantTime = { _, _ -> },
                        onSave = {},
                        onRetryLoad = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("알림 허용").assertDoesNotExist()
        composeRule.onNodeWithText("기기 설정").assertIsDisplayed().performClick()
    }

    @Test
    fun `edited global and plant settings expose one explicit save action`() {
        var saves = 0
        show(
            state =
                WateringNotificationSettingsState.Editing(
                    confirmed = fixture(),
                    draft =
                        fixture()
                            .copy(
                                global = fixture().global.copy(defaultTime = LocalTime.of(7, 30)),
                                plants =
                                    listOf(
                                        fixture()
                                            .plants
                                            .single()
                                            .copy(
                                                enabled = false,
                                                timeOverride = LocalTime.of(8, 15),
                                            )
                                    ),
                            ),
                ),
            permissionGranted = true,
            onSave = { saves++ },
        )

        composeRule.onNodeWithText("기본 알림 시간: 07:30").assertIsDisplayed()
        composeRule.onNodeWithText("알림 시간: 08:15").assertIsDisplayed()
        composeRule.onNodeWithTag(WateringNotificationSettingsTestTags.SAVE).performClick()
        assertEquals(1, saves)
    }

    private fun show(
        state: WateringNotificationSettingsState,
        permissionGranted: Boolean,
        canRequestPermission: Boolean = false,
        onRequestPermission: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onPlantEnabled: (PersonalPlantId, Boolean) -> Unit = { _, _ -> },
        onSave: () -> Unit = {},
    ) {
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                WateringNotificationSettingsScreen(
                    state = state,
                    notificationPermissionGranted = permissionGranted,
                    canRequestNotificationPermission = canRequestPermission,
                    onRequestPermission = onRequestPermission,
                    onOpenSystemSettings = onOpenSettings,
                    onBack = {},
                    onGlobalEnabled = {},
                    onDefaultTime = {},
                    onPlantEnabled = onPlantEnabled,
                    onPlantTime = { _, _ -> },
                    onSave = onSave,
                    onRetryLoad = {},
                )
            }
        }
    }

    private fun fixture() =
        WateringNotificationSettings(
            global =
                GlobalWateringReminder(
                    enabled = true,
                    defaultTime = LocalTime.of(9, 0),
                    zoneId = ZoneId.of("Asia/Seoul"),
                ),
            plants =
                listOf(
                    PlantWateringReminder(
                        PersonalPlantId("plant-a"),
                        enabled = true,
                        timeOverride = null,
                        displayName = "몬스테라",
                    )
                ),
        )
}
