package com.planterior.helper.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MiniHomeWatermarkMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun cleanUp() {
        context.deleteDatabase(DATABASE)
    }

    @Test
    fun `version twelve layout backfills an upgradeable owner watermark`() {
        context.deleteDatabase(DATABASE)
        val current =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, DATABASE)
                .allowMainThreadQueries()
                .build()
        runBlocking {
            current
                .cacheDao()
                .upsertMiniHome(
                    CachedMiniHomeEntity(
                        "account-a",
                        "home-a",
                        "legacy revision three",
                        0,
                        3,
                        300,
                    )
                )
        }
        current.close()
        context.openOrCreateDatabase(DATABASE, Context.MODE_PRIVATE, null).use { versionTwelve ->
            versionTwelve.execSQL("DROP TABLE mini_home_cache_watermarks")
            versionTwelve.version = 12
        }

        val migrated =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, DATABASE)
                .addMigrations(MIGRATION_12_13, MIGRATION_13_14)
                .allowMainThreadQueries()
                .build()
        try {
            val (watermark, result, home) =
                runBlocking {
                    val watermark = migrated.cacheDao().miniHomeCacheWatermark("account-a")
                    val result =
                        migrated
                            .cacheDao()
                            .applyAuthoritativeMiniHome(
                                AuthoritativeMiniHomeCacheWrite.Deletion(
                                    "account-a",
                                    1,
                                    "initial-missing",
                                    301,
                                )
                            )
                    Triple(watermark, result, migrated.cacheDao().miniHome("account-a"))
                }
            assertEquals(2L, watermark?.generation)
            assertEquals("PRESENT", watermark?.kind)
            assertEquals(3L, watermark?.layoutRevision)
            assertEquals(false, watermark?.verified)
            assertEquals(true, result is MiniHomeCacheApplyResult.Applied)
            assertEquals(true, result.current.watermark.verified)
            assertEquals(null, home)
        } finally {
            migrated.close()
        }
    }

    private companion object {
        const val DATABASE = "mini-home-watermark-migration.db"
    }
}
