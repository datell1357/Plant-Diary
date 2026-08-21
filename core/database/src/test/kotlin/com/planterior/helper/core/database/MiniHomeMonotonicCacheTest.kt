package com.planterior.helper.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MiniHomeMonotonicCacheTest {
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

    @After
    fun tearDown() {
        if (database.isOpen) database.close()
        ApplicationProvider.getApplicationContext<Context>()
            .deleteDatabase("mini-home-watermark-restart.db")
    }

    @Test
    fun `delayed revision one cannot overwrite revision two`() = runTest {
        val dao = database.cacheDao()
        assertTrue(
            dao.applyAuthoritativeMiniHome(layoutWrite(2, "operation-revision-two", "b"))
                is MiniHomeCacheApplyResult.Applied
        )

        val delayed = dao.applyAuthoritativeMiniHome(layoutWrite(1, "operation-revision-one", "a"))

        assertTrue(delayed is MiniHomeCacheApplyResult.Ignored)
        assertEquals(2L, delayed.current.watermark.generation)
        assertEquals("revision 2", delayed.current.home?.name)
        assertEquals("placement-2", delayed.current.placements.single().placementId)
    }

    @Test
    fun `higher response generation cannot regress a live layout revision`() = runTest {
        val dao = database.cacheDao()
        dao.applyAuthoritativeMiniHome(
            layoutWrite(2, "operation-revision-two", "b", layoutRevision = 2)
        )

        val staleResponse =
            dao.applyAuthoritativeMiniHome(
                layoutWrite(3, "operation-stale-response", "c", layoutRevision = 1)
            )

        assertTrue(staleResponse is MiniHomeCacheApplyResult.Ignored)
        assertEquals(2L, staleResponse.current.watermark.generation)
        assertEquals(2L, staleResponse.current.home?.revision)
        assertEquals("revision 2", staleResponse.current.home?.name)
    }

    @Test
    fun `delayed layout cannot resurrect after authoritative deletion`() = runTest {
        val dao = database.cacheDao()
        dao.applyAuthoritativeMiniHome(layoutWrite(1, "operation-revision-one", "a"))
        dao.applyAuthoritativeMiniHome(deletionWrite(2, "deletion-generation-two"))

        val delayed = dao.applyAuthoritativeMiniHome(layoutWrite(1, "operation-revision-one", "a"))

        assertTrue(delayed is MiniHomeCacheApplyResult.Ignored)
        assertEquals(MiniHomeCacheWatermarkKind.DELETED, delayed.current.watermark.kind)
        assertNull(delayed.current.home)
        assertEquals(emptyList<CachedMiniHomePlacementEntity>(), delayed.current.placements)
    }

    @Test
    fun `newer recreation applies after deletion even when layout revision resets`() = runTest {
        val dao = database.cacheDao()
        dao.applyAuthoritativeMiniHome(
            layoutWrite(4, "operation-before-delete", "a", layoutRevision = 4)
        )
        dao.applyAuthoritativeMiniHome(deletionWrite(5, "deletion-generation-five"))

        val recreated =
            dao.applyAuthoritativeMiniHome(
                layoutWrite(6, "operation-recreated", "c", layoutRevision = 1)
            )

        assertTrue(recreated is MiniHomeCacheApplyResult.Applied)
        assertEquals(6L, recreated.current.watermark.generation)
        assertEquals(1L, recreated.current.home?.revision)
        assertEquals("operation-recreated", recreated.current.watermark.operationId)
    }

    @Test
    fun `first verified response supersedes any unverified migration generation then ordering is strict`() =
        runTest {
            val dao = database.cacheDao()
            dao.upsertMiniHome(
                CachedMiniHomeEntity(
                    "account-a",
                    "home-account-a",
                    "legacy revision three",
                    0,
                    3,
                    300,
                )
            )
            dao.upsertMiniHomeCacheWatermark(
                MiniHomeCacheWatermarkEntity(
                    accountId = "account-a",
                    generation = 99,
                    kind = MiniHomeCacheWatermarkKind.PRESENT.name,
                    layoutRevision = 3,
                    miniHomeId = "home-account-a",
                    operationId = null,
                    payloadHash = null,
                    tombstoneId = null,
                    authoritativeAtEpochMillis = 300,
                    verified = false,
                )
            )

            val bootstrap = dao.applyAuthoritativeMiniHome(deletionWrite(1, "initial-missing"))
            val delayedPreBootstrap =
                dao.applyAuthoritativeMiniHome(
                    layoutWrite(1, "operation-delayed", "d", layoutRevision = 3)
                )
            val recreation =
                dao.applyAuthoritativeMiniHome(
                    layoutWrite(2, "operation-recreated", "e", layoutRevision = 1)
                )
            val delayedDeletion =
                dao.applyAuthoritativeMiniHome(deletionWrite(1, "initial-missing"))

            assertTrue(bootstrap is MiniHomeCacheApplyResult.Applied)
            assertTrue(bootstrap.current.watermark.verified)
            assertNull(bootstrap.current.home)
            assertTrue(delayedPreBootstrap is MiniHomeCacheApplyResult.Conflict)
            assertTrue(recreation is MiniHomeCacheApplyResult.Applied)
            assertEquals(1L, recreation.current.home?.revision)
            assertTrue(delayedDeletion is MiniHomeCacheApplyResult.Ignored)
            assertEquals("operation-recreated", delayedDeletion.current.watermark.operationId)
        }

    @Test
    fun `same generation is idempotent only for exact identity and content`() = runTest {
        val dao = database.cacheDao()
        val write = layoutWrite(2, "operation-revision-two", "b")
        dao.applyAuthoritativeMiniHome(write)

        val replay = dao.applyAuthoritativeMiniHome(write)
        val mismatchedHash =
            dao.applyAuthoritativeMiniHome(write.copy(payloadHash = "c".repeat(64)))
        val mismatchedContent =
            dao.applyAuthoritativeMiniHome(
                write.copy(home = write.home.copy(name = "forged same generation"))
            )

        assertTrue(replay is MiniHomeCacheApplyResult.Ignored)
        assertTrue(mismatchedHash is MiniHomeCacheApplyResult.Conflict)
        assertTrue(mismatchedContent is MiniHomeCacheApplyResult.Conflict)
        assertEquals("revision 2", mismatchedContent.current.home?.name)
    }

    @Test
    fun `concurrent writers serialize to the highest generation`() = runTest {
        val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        try {
            val first =
                async(dispatcher) {
                    database
                        .cacheDao()
                        .applyAuthoritativeMiniHome(layoutWrite(1, "operation-revision-one", "a"))
                }
            val second =
                async(dispatcher) {
                    database
                        .cacheDao()
                        .applyAuthoritativeMiniHome(layoutWrite(2, "operation-revision-two", "b"))
                }
            first.await()
            second.await()
        } finally {
            dispatcher.close()
        }

        val current = database.cacheDao().currentMiniHomeCache("account-a")
        assertEquals(2L, current?.watermark?.generation)
        assertEquals("revision 2", current?.home?.name)
    }

    @Test
    fun `process restart preserves deletion watermark against delayed resurrection`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database.close()
        context.deleteDatabase("mini-home-watermark-restart.db")
        database =
            Room.databaseBuilder(
                    context,
                    PlanteriorDatabase::class.java,
                    "mini-home-watermark-restart.db",
                )
                .build()
        database
            .cacheDao()
            .applyAuthoritativeMiniHome(layoutWrite(1, "operation-revision-one", "a"))
        database.cacheDao().applyAuthoritativeMiniHome(deletionWrite(2, "deletion-generation-two"))
        database.close()
        database =
            Room.databaseBuilder(
                    context,
                    PlanteriorDatabase::class.java,
                    "mini-home-watermark-restart.db",
                )
                .build()

        val delayed =
            database
                .cacheDao()
                .applyAuthoritativeMiniHome(layoutWrite(1, "operation-revision-one", "a"))

        assertTrue(delayed is MiniHomeCacheApplyResult.Ignored)
        assertEquals(MiniHomeCacheWatermarkKind.DELETED, delayed.current.watermark.kind)
        assertNull(delayed.current.home)
    }

    @Test
    fun `mismatched envelope tokens never form a Room snapshot and are purged`() = runTest {
        val dao = database.cacheDao()
        dao.applyAuthoritativeMiniHome(
            layoutWrite(2, "operation-revision-two", "b")
                .copy(
                    snapshotToken = "a".repeat(64),
                    snapshotGeneration = 7,
                )
        )
        dao.applyAuthoritativeInventory(
            AuthoritativeInventoryCacheWrite(
                accountId = "account-a",
                generation = 3,
                snapshotHash = "c".repeat(64),
                registeredPlantCount = 0,
                loadedAtEpochMillis = 300,
                partial = false,
                catalog = emptyList(),
                owned = emptyList(),
                snapshotToken = "b".repeat(64),
                snapshotGeneration = 7,
            )
        )

        val torn = dao.currentMiniHomeSnapshotCache("account-a")
        assertEquals(false, torn?.coherent)
        assertTrue(dao.purgeIncoherentMiniHomeSnapshot("account-a"))
        assertNull(dao.currentMiniHomeCache("account-a"))
        assertNull(dao.currentInventoryCache("account-a"))
    }

    @Test
    fun `watermarks and ordering are owner partitioned`() = runTest {
        val dao = database.cacheDao()
        dao.applyAuthoritativeMiniHome(layoutWrite(3, "operation-account-a", "a"))
        dao.applyAuthoritativeMiniHome(
            layoutWrite(1, "operation-account-b", "b", accountId = "account-b")
        )
        dao.applyAuthoritativeMiniHome(deletionWrite(4, "deletion-account-a"))

        assertNull(dao.currentMiniHomeCache("account-a")?.home)
        assertEquals("revision 1", dao.currentMiniHomeCache("account-b")?.home?.name)
    }

    private fun layoutWrite(
        generation: Long,
        operationId: String,
        hashSeed: String,
        layoutRevision: Long = generation,
        accountId: String = "account-a",
    ): AuthoritativeMiniHomeCacheWrite.Layout {
        val home =
            CachedMiniHomeEntity(
                accountId,
                "home-$accountId",
                "revision $layoutRevision",
                1,
                layoutRevision,
                generation * 100,
            )
        return AuthoritativeMiniHomeCacheWrite.Layout(
            accountId = accountId,
            generation = generation,
            operationId = operationId,
            payloadHash = hashSeed.repeat(64),
            home = home,
            placements =
                listOf(
                    CachedMiniHomePlacementEntity(
                        accountId,
                        "placement-$layoutRevision",
                        home.miniHomeId,
                        "plant-$layoutRevision",
                        null,
                        0.1,
                        0.125,
                        0,
                        layoutRevision,
                    )
                ),
        )
    }

    private fun deletionWrite(
        generation: Long,
        tombstoneId: String,
    ) =
        AuthoritativeMiniHomeCacheWrite.Deletion(
            accountId = "account-a",
            generation = generation,
            tombstoneId = tombstoneId,
            deletedAtEpochMillis = generation * 100,
        )
}
