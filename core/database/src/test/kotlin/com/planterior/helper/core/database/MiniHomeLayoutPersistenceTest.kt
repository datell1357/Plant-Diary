package com.planterior.helper.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MiniHomeLayoutPersistenceTest {
    private lateinit var database: PlanteriorDatabase

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    PlanteriorDatabase::class.java,
                )
                .build()
    }

    @After fun tearDown() = database.close()

    @Test
    fun `committed layout coordinates are atomic owner scoped and deleted with account`() =
        runTest {
            val home = CachedMiniHomeEntity("account-a", "home-a", "A의 방", 1, 3, 30)
            val placement =
                CachedMiniHomePlacementEntity(
                    "account-a",
                    "placement-a",
                    "home-a",
                    "plant-a",
                    null,
                    0.3,
                    0.625,
                    0,
                    3,
                )
            database
                .cacheDao()
                .applyAuthoritativeMiniHome(
                    AuthoritativeMiniHomeCacheWrite.Layout(
                        "account-a",
                        3,
                        "operation-account-a",
                        "a".repeat(64),
                        home,
                        listOf(placement),
                    )
                )
            database
                .cacheDao()
                .applyAuthoritativeMiniHome(
                    AuthoritativeMiniHomeCacheWrite.Layout(
                        "account-b",
                        1,
                        "operation-account-b",
                        "b".repeat(64),
                        CachedMiniHomeEntity("account-b", "home-b", "B의 방", 0, 1, 10),
                        emptyList(),
                    )
                )

            assertEquals(
                placement,
                database.cacheDao().miniHomePlacements("account-a", "home-a", 3).single(),
            )
            assertEquals("B의 방", database.cacheDao().miniHome("account-b")?.name)

            database.cacheDao().clearVisibleAccount("account-a")

            assertNull(database.cacheDao().miniHome("account-a"))
            assertEquals(
                emptyList<CachedMiniHomePlacementEntity>(),
                database.cacheDao().miniHomePlacements("account-a", "home-a", 3),
            )
            assertEquals("B의 방", database.cacheDao().miniHome("account-b")?.name)
        }
}
