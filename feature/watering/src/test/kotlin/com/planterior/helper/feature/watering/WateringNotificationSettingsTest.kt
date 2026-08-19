package com.planterior.helper.feature.watering

import androidx.lifecycle.SavedStateHandle
import com.planterior.helper.core.model.PersonalPlantId
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WateringNotificationSettingsTest {
    @Test
    fun `plant reminder inherits global time until an explicit override is saved`() {
        val global =
            GlobalWateringReminder(
                enabled = true,
                defaultTime = LocalTime.of(9, 0),
                zoneId = ZoneId.of("Asia/Seoul"),
            )

        assertEquals(
            EffectiveWateringReminder(true, LocalTime.of(9, 0), ZoneId.of("Asia/Seoul")),
            effectiveReminder(
                global,
                PlantWateringReminder(
                    PersonalPlantId("plant-a"),
                    enabled = true,
                    timeOverride = null,
                ),
            ),
        )
        assertEquals(
            EffectiveWateringReminder(true, LocalTime.of(7, 30), ZoneId.of("Asia/Seoul")),
            effectiveReminder(
                global,
                PlantWateringReminder(
                    PersonalPlantId("plant-a"),
                    enabled = true,
                    timeOverride = LocalTime.of(7, 30),
                ),
            ),
        )
    }

    @Test
    fun `disabled plant preserves due schedule and override while suppressing only push delivery`() {
        val plant =
            PlantWateringReminder(
                PersonalPlantId("plant-a"),
                enabled = false,
                timeOverride = LocalTime.of(8, 15),
            )
        val effective =
            effectiveReminder(
                GlobalWateringReminder(true, LocalTime.of(9, 0), ZoneId.of("Asia/Seoul")),
                plant,
            )

        assertFalse(effective.enabled)
        assertEquals(LocalTime.of(8, 15), effective.time)
        assertTrue(effective.scheduleRemainsVisible)
        assertTrue(effective.completionRemainsAvailable)
    }

    @Test
    fun `settings controller keeps confirmed state on failed save and exact retry succeeds`() =
        runTest {
            val repository = RecordingSettingsRepository()
            val controller = WateringNotificationSettingsController(repository)
            controller.load()
            val confirmed = controller.state.value as WateringNotificationSettingsState.Ready

            repository.failNext = true
            controller.setDefaultTime(LocalTime.of(7, 0))
            controller.save()
            val failed = controller.state.value as WateringNotificationSettingsState.SaveFailed
            assertEquals(confirmed.settings.global, failed.confirmed.global)
            assertEquals(LocalTime.of(7, 0), failed.draft.global.defaultTime)

            controller.save()
            val saved = controller.state.value as WateringNotificationSettingsState.Ready
            assertEquals(LocalTime.of(7, 0), saved.settings.global.defaultTime)
            assertEquals(2, repository.saved.size)
            assertNull(saved.errorMessage)
        }

    @Test
    fun `unsaved and failed drafts survive controller recreation while explicit refresh replaces them`() =
        runTest {
            val repository = RecordingSettingsRepository()
            val savedState = SavedStateHandle()
            val first = WateringNotificationSettingsController(repository, savedState)
            first.loadIfNeeded()
            first.setDefaultTime(LocalTime.of(7, 0))

            val recreated = WateringNotificationSettingsController(repository, savedState)
            recreated.loadIfNeeded()
            assertEquals(
                LocalTime.of(7, 0),
                (recreated.state.value as WateringNotificationSettingsState.Editing)
                    .draft
                    .global
                    .defaultTime,
            )
            assertEquals(1, repository.loads)

            repository.failNext = true
            recreated.save()
            val afterFailure = WateringNotificationSettingsController(repository, savedState)
            assertEquals(
                LocalTime.of(7, 0),
                (afterFailure.state.value as WateringNotificationSettingsState.SaveFailed)
                    .draft
                    .global
                    .defaultTime,
            )

            afterFailure.load()
            assertEquals(
                LocalTime.of(9, 0),
                (afterFailure.state.value as WateringNotificationSettingsState.Ready)
                    .settings
                    .global
                    .defaultTime,
            )
            assertEquals(2, repository.loads)
        }

    @Test
    fun `client adopts the authoritative committed revision instead of assuming one increment`() {
        assertEquals(9L, committedSettingsRevision(mapOf("revision" to 9), 3))
        assertNull(committedSettingsRevision(mapOf("revision" to 3), 3))
    }

    @Test
    fun `failed precondition is normalized to the same authoritative reload path as aborted`() {
        assertTrue(FirebaseWateringSettingsFailureClassifier.isConflict("ABORTED"))
        assertTrue(FirebaseWateringSettingsFailureClassifier.isConflict("FAILED_PRECONDITION"))
    }

    @Test
    fun `revision conflict reloads authoritative settings instead of retrying stale draft`() =
        runTest {
            val repository = RecordingSettingsRepository()
            val controller = WateringNotificationSettingsController(repository)
            controller.load()
            controller.setDefaultTime(LocalTime.of(7, 0))
            repository.conflictNext = true
            repository.authoritativeTimeOnConflict = LocalTime.of(10, 30)

            controller.save()

            val reloaded = controller.state.value as WateringNotificationSettingsState.Ready
            assertEquals(LocalTime.of(10, 30), reloaded.settings.global.defaultTime)
            assertEquals(2, reloaded.settings.revision)
            assertEquals("다른 기기에서 설정이 변경되어 최신 설정을 불러왔어요.", reloaded.errorMessage)
        }

    private class RecordingSettingsRepository : WateringNotificationSettingsRepository {
        var failNext = false
        var conflictNext = false
        var authoritativeTimeOnConflict = LocalTime.of(9, 0)
        var loads = 0
        val saved = mutableListOf<WateringNotificationSettings>()
        private var current =
            WateringNotificationSettings(
                global = GlobalWateringReminder(true, LocalTime.of(9, 0), ZoneId.of("Asia/Seoul")),
                plants =
                    listOf(PlantWateringReminder(PersonalPlantId("plant-a"), true, null, "몬스테라")),
                revision = 1,
            )

        override suspend fun load(): WateringNotificationSettings {
            loads += 1
            return current
        }

        override suspend fun save(
            settings: WateringNotificationSettings
        ): WateringSettingsSaveResult {
            saved += settings
            if (conflictNext) {
                conflictNext = false
                current =
                    current.copy(
                        global = current.global.copy(defaultTime = authoritativeTimeOnConflict),
                        revision = current.revision + 1,
                    )
                return WateringSettingsSaveResult.Conflict
            }
            if (failNext) {
                failNext = false
                return WateringSettingsSaveResult.Failed
            }
            current = settings.copy(revision = settings.revision + 1)
            return WateringSettingsSaveResult.Saved(current)
        }
    }
}
