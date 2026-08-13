package com.planterior.helper.home

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.database.CachedMiniHomeEntity
import com.planterior.helper.core.database.CachedPlantEntity
import com.planterior.helper.core.database.CachedWateringScheduleEntity
import com.planterior.helper.core.database.LastSyncEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.feature.auth.AuthAccount
import com.planterior.helper.feature.auth.AuthProvider
import com.planterior.helper.feature.auth.AuthUiState
import com.planterior.helper.feature.auth.SyncSummary
import com.planterior.helper.feature.home.HomeSession
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 제품이 실제로 쓰는 저장소를 실제 Room 위에서 검증한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CachedHomeRepositoryTest {
    private lateinit var database: PlanteriorDatabase
    private val authState = MutableStateFlow<AuthUiState>(AuthUiState.Restoring)

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    PlanteriorDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
    }

    @After fun tearDown() = database.close()

    private fun repository(fallback: ZoneId = ZoneId.of("Asia/Seoul")) =
        CachedHomeRepository(database, authState, { Result.success(null) }, fallback)

    private fun signIn(uid: String) {
        authState.value =
            AuthUiState.Authenticated(
                AuthAccount(uid, "$uid@example.invalid", "민지", setOf(AuthProvider.GOOGLE)),
                SyncSummary.EMPTY,
            )
    }

    @Test
    fun `restoring auth maps to a restoring session rather than signed out`() = runTest {
        authState.value = AuthUiState.Restoring

        assertEquals(HomeSession.Restoring, repository().sessions().first())
    }

    @Test
    fun `signing in also counts as restoring so home does not flash the guest view`() = runTest {
        authState.value = AuthUiState.SigningIn(AuthProvider.GOOGLE)

        assertEquals(HomeSession.Restoring, repository().sessions().first())
    }

    @Test
    fun `signed out auth maps to a signed out session`() = runTest {
        authState.value = AuthUiState.SignedOut()

        assertEquals(HomeSession.SignedOut, repository().sessions().first())
    }

    @Test
    fun `the persisted mini home configuration is what home renders`() = runTest {
        signIn("account-a")
        database
            .cacheDao()
            .upsertMiniHome(CachedMiniHomeEntity("account-a", "home-a", "민지의 미니 식물원", 3, 1, 10))

        val preview = repository().miniHomePreview()

        assertEquals("민지의 미니 식물원", preview?.title)
        assertEquals(3, preview?.placedPlantCount)
    }

    @Test
    fun `another account's mini home is never visible`() = runTest {
        database
            .cacheDao()
            .upsertMiniHome(CachedMiniHomeEntity("account-b", "home-b", "남의 방", 9, 1, 10))
        signIn("account-a")

        assertNull(repository().miniHomePreview())
    }

    @Test
    fun `no mini home yet renders no preview instead of invented content`() = runTest {
        signIn("account-a")

        assertNull(repository().miniHomePreview())
    }

    @Test
    fun `each schedule keeps its own zone and a broken zone falls back safely`() = runTest {
        signIn("account-a")
        val dao = database.cacheDao()
        dao.upsertPlant(CachedPlantEntity("account-a", "p-la", "LA 식물", null, 1, 1))
        dao.upsertPlant(CachedPlantEntity("account-a", "p-broken", "깨진 시간대", null, 1, 1))
        dao.upsertSchedule(
            CachedWateringScheduleEntity(
                "account-a",
                "s-la",
                "p-la",
                "2026-08-12",
                "09:00",
                "America/Los_Angeles",
                1,
                1,
            )
        )
        dao.upsertSchedule(
            CachedWateringScheduleEntity(
                "account-a",
                "s-broken",
                "p-broken",
                "2026-08-12",
                "09:00",
                "Not/AZone",
                1,
                1,
            )
        )

        val care = repository(fallback = ZoneId.of("UTC")).plantCare().getOrThrow()
        val byId = care.associateBy { it.plantId }

        assertEquals(ZoneId.of("America/Los_Angeles"), byId.getValue("p-la").zoneId)
        assertEquals(
            "해석할 수 없는 시간대는 기준 시간대로 물러나야 한다",
            ZoneId.of("UTC"),
            byId.getValue("p-broken").zoneId,
        )
    }

    @Test
    fun `a plant without any schedule stays unavailable rather than guessing a date`() = runTest {
        signIn("account-a")
        database
            .cacheDao()
            .upsertPlant(CachedPlantEntity("account-a", "p-none", "일정 없음", null, 1, 1))

        val care = repository().plantCare().getOrThrow().single()

        assertNull(care.nextWateringDate)
        assertNull(care.wateringIntervalDays)
    }

    @Test
    fun `a failed sync record marks the home as stale with the last success`() = runTest {
        signIn("account-a")
        database
            .syncDao()
            .upsertLastSync(LastSyncEntity("account-a", "PLANTS", 1_000, "SUCCESS", null))
        database
            .syncDao()
            .upsertLastSync(
                LastSyncEntity("account-a", "MINI_HOME", 2_000, "FAILED", "unavailable")
            )

        val status = repository().syncStatus()

        assertEquals(
            com.planterior.helper.feature.home.HomeSyncStatus.Stale(Instant.ofEpochMilli(1_000)),
            status,
        )
    }
}
