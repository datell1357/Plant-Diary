package com.planterior.helper.feature.minihome

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.planterior.helper.core.data.AuthoritativeCatalogItem
import com.planterior.helper.core.data.AuthoritativeInventory
import com.planterior.helper.core.data.AuthoritativeInventoryAvailability
import com.planterior.helper.core.data.AuthoritativeOwnedItem
import com.planterior.helper.core.data.INVENTORY_CONTRACT_VERSION
import com.planterior.helper.core.data.authoritativeInventorySnapshotHash
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.CatalogMediaIdentity
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.Revision
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 37, maxSdkVersion = 37)
class MiniHomeInventoryOfflineApi37Test {
    @Test
    fun verified_background_survives_database_reopen_and_offline_repository_recreation() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val databaseName = "api37-mini-home-inventory-offline.db"
            context.deleteDatabase(databaseName)
            val remote = Api37Remote(snapshot())
            var onlineDatabase: PlanteriorDatabase? =
                Room.databaseBuilder(context, PlanteriorDatabase::class.java, databaseName).build()
            var offlineDatabase: PlanteriorDatabase? = null
            try {
                val online =
                    FirebaseMiniHomeRepository(requireNotNull(onlineDatabase), remote).load()
                        as MiniHomeLoadResult.Ready
                assertFalse(online.stale)
                assertEquals(IDENTITY, online.decorations.single().mediaIdentity)

                onlineDatabase.close()
                onlineDatabase = null
                remote.offline = true
                offlineDatabase =
                    Room.databaseBuilder(context, PlanteriorDatabase::class.java, databaseName)
                        .build()
                val restored =
                    FirebaseMiniHomeRepository(requireNotNull(offlineDatabase), remote).load()
                        as MiniHomeLoadResult.Ready

                assertTrue(restored.stale)
                assertEquals(Revision(5), restored.committed.revision)
                assertEquals(ItemCategory.BACKGROUND, restored.decorations.single().category)
                assertEquals(IDENTITY, restored.decorations.single().mediaIdentity)
                assertFalse(restored.decorations.single().availableForApplication)
            } finally {
                onlineDatabase?.close()
                offlineDatabase?.close()
                context.deleteDatabase(databaseName)
            }
        }

    private class Api37Remote(private val snapshot: RemoteMiniHomeSnapshot) :
        MiniHomeRemoteDataSource {
        var offline = false

        override fun activeAccount(): AccountId = ACCOUNT

        override suspend fun load(accountId: AccountId): RemoteMiniHomeSnapshot {
            if (offline) throw IOException("offline")
            require(accountId == ACCOUNT)
            return snapshot
        }

        override suspend fun save(request: MiniHomeSaveRequest): RemoteMiniHomeSaveResult =
            error("not used")
    }

    private companion object {
        val ACCOUNT = AccountId("api37-owner")
        val IDENTITY = identity()

        fun snapshot(): RemoteMiniHomeSnapshot {
            val catalog =
                listOf(
                    AuthoritativeCatalogItem(
                        ItemId("api37-background"),
                        "API 37 배경",
                        "오프라인 복원 배경",
                        ItemCategory.BACKGROUND,
                        IDENTITY,
                        null,
                        Revision(7),
                        700,
                    )
                )
            val owned =
                listOf(
                    AuthoritativeOwnedItem(
                        ItemId("api37-background"),
                        701,
                        false,
                        Revision(8),
                        AuthoritativeInventoryAvailability.AVAILABLE,
                        null,
                    )
                )
            val inventory =
                AuthoritativeInventory(
                    INVENTORY_CONTRACT_VERSION,
                    ACCOUNT,
                    catalog,
                    owned,
                    1,
                    702,
                    partial = false,
                    generation = 9,
                    snapshotHash =
                        authoritativeInventorySnapshotHash(ACCOUNT, catalog, owned, 1, false),
                )
            return RemoteMiniHomeSnapshot(
                accountId = ACCOUNT,
                layout =
                    MiniHomeLayout(
                        MiniHomeId("api37-home"),
                        "API 37 미니홈",
                        emptyList(),
                        Revision(5),
                        Instant.ofEpochMilli(500),
                    ),
                plants = emptyList(),
                decorations = emptyList(),
                cacheGeneration = 5,
                cacheOperationId = "api37-cache-operation",
                cachePayloadHash = "0".repeat(64),
                authoritativeAtEpochMillis = 500,
                authoritativeInventory = inventory,
            )
        }

        fun identity(): CatalogMediaIdentity {
            val digest =
                MessageDigest.getInstance("SHA-256")
                    .digest("api37-offline-background".toByteArray())
                    .joinToString("") { "%02x".format(it) }
            return CatalogMediaIdentity(
                "catalog-assets/api37-background/$digest.webp",
                digest,
                4,
                "image/webp",
                1,
                1,
                Revision(3),
            )
        }
    }
}
