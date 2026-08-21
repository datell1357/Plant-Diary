package com.planterior.helper.core.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LegacyMiniHomeNameMigrationTest {
    @Test
    fun `persisted legacy recovery has an exact Unicode safety boundary`() {
        assertEquals("é", recoverLegacyMiniHomeName("e\u0301"))
        assertEquals("😀".repeat(100), recoverLegacyMiniHomeName("😀".repeat(100)))
        assertNull(recoverLegacyMiniHomeName("😀".repeat(101)))
        assertNull(recoverLegacyMiniHomeName("\uD800"))
        assertNull(recoverLegacyMiniHomeName("\uDC00"))
        ((0x0000..0x001F) + (0x007F..0x009F)).forEach { codePoint ->
            assertNull(
                "control U+${codePoint.toString(16)}",
                recoverLegacyMiniHomeName("A${String(Character.toChars(codePoint))}B"),
            )
        }
        listOf(
                0x061C,
                0x200E,
                0x200F,
                0x202A,
                0x202B,
                0x202C,
                0x202D,
                0x202E,
                0x2066,
                0x2067,
                0x2068,
                0x2069,
            )
            .forEach { codePoint ->
                assertNull(
                    "bidi U+${codePoint.toString(16)}",
                    recoverLegacyMiniHomeName("A${String(Character.toChars(codePoint))}B"),
                )
            }
    }

    @Test
    fun `v10 to latest backfills a stable nonempty outbox row handle`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "outbox-row-handle-v10.db"
        createDatabaseFromSchema(context, name, 10) { database ->
            database.execSQL(
                "INSERT INTO operation_outbox (operationId, accountId, aggregateType, aggregateId, mutationType, expectedRevision, draftPayload, createdAtEpochMillis, state, lineageId) VALUES ('legacy-operation', 'account-a', 'miniHomeLayouts', 'home-a', 'REPLACE', 1, '{bad-json', 1, 'RECONCILIATION_REQUIRED', 'legacy-lineage')"
            )
        }

        val migrated =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, name)
                .addMigrations(
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                )
                .allowMainThreadQueries()
                .build()
        val first = runBlocking {
            requireNotNull(migrated.syncDao().operation("account-a", "legacy-operation"))
        }
        assertTrue(first.rowHandleId.isNotBlank())
        assertEquals(0L, first.rowVersion)
        migrated.close()

        val reopened =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, name)
                .addMigrations(
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                )
                .allowMainThreadQueries()
                .build()
        val second = runBlocking {
            requireNotNull(reopened.syncDao().operation("account-a", "legacy-operation"))
        }
        assertEquals(first.rowHandleId, second.rowHandleId)
        assertEquals(first.rowVersion, second.rowVersion)
        reopened.close()
        assertTrue(context.deleteDatabase(name))
    }

    @Test
    fun `v4 v7 and v9 to latest normalize recoverable names and quarantine unsafe names`() {
        listOf(4, 7, 9).forEach { version ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val name = "legacy-mini-home-name-v$version.db"
            createDatabaseFromSchema(context, name, version) { database ->
                legacyNames.forEach { (accountId, legacyName) ->
                    database.execSQL(
                        "INSERT INTO cached_mini_homes (accountId, miniHomeId, name, placedPlantCount, revision, updatedAtEpochMillis) VALUES (?, ?, ?, 0, 1, 1)",
                        arrayOf(accountId, "home-$accountId", legacyName),
                    )
                    if (version >= 7) {
                        database.execSQL(
                            "INSERT INTO cached_mini_home_placements (accountId, placementId, miniHomeId, plantId, itemId, normalizedX, normalizedY, zIndex, layoutRevision) VALUES (?, ?, ?, ?, NULL, 0.1, 0.125, 0, 1)",
                            arrayOf(
                                accountId,
                                "placement-$accountId",
                                "home-$accountId",
                                "plant-$accountId",
                            ),
                        )
                    }
                }
            }

            val migrated =
                Room.databaseBuilder(context, PlanteriorDatabase::class.java, name)
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                    )
                    .allowMainThreadQueries()
                    .build()
            runBlocking {
                assertEquals("é", migrated.cacheDao().miniHome("recoverable")?.name)
                assertEquals("정상 이름", migrated.cacheDao().miniHome("canonical")?.name)
                listOf("control", "bidi", "overlength").forEach { accountId ->
                    assertNull("v$version $accountId", migrated.cacheDao().miniHome(accountId))
                    assertTrue(
                        "v$version $accountId placements",
                        migrated
                            .cacheDao()
                            .miniHomePlacements(accountId, "home-$accountId", 1)
                            .isEmpty(),
                    )
                }
            }
            migrated.close()
            assertTrue(context.deleteDatabase(name))
        }
    }

    @Test
    fun `v4 v7 v9 and v12 caches bootstrap missing and existing server state then stay verified`() {
        listOf(4, 7, 9, 12).forEach { version ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val name = "mini-home-bootstrap-v$version.db"
            createDatabaseFromSchema(context, name, version) { database ->
                listOf("missing", "existing").forEach { accountId ->
                    database.execSQL(
                        "INSERT INTO cached_mini_homes (accountId, miniHomeId, name, placedPlantCount, revision, updatedAtEpochMillis) VALUES (?, ?, ?, 0, 3, 300)",
                        arrayOf(accountId, "home-$accountId", "legacy-$accountId"),
                    )
                    if (version >= 7) {
                        database.execSQL(
                            "INSERT INTO cached_mini_home_placements (accountId, placementId, miniHomeId, plantId, itemId, normalizedX, normalizedY, zIndex, layoutRevision) VALUES (?, ?, ?, ?, NULL, 0.1, 0.125, 0, 3)",
                            arrayOf(
                                accountId,
                                "placement-$accountId",
                                "home-$accountId",
                                "plant-$accountId",
                            ),
                        )
                    }
                }
            }

            val migrated =
                Room.databaseBuilder(context, PlanteriorDatabase::class.java, name)
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                    )
                    .allowMainThreadQueries()
                    .build()
            runBlocking {
                val dao = migrated.cacheDao()
                assertEquals("legacy-missing", dao.miniHome("missing")?.name)
                assertEquals("legacy-existing", dao.miniHome("existing")?.name)
                assertEquals(false, dao.miniHomeCacheWatermark("missing")?.verified)
                assertEquals(false, dao.miniHomeCacheWatermark("existing")?.verified)

                val missing =
                    dao.applyAuthoritativeMiniHome(
                        AuthoritativeMiniHomeCacheWrite.Deletion(
                            "missing",
                            1,
                            "initial-missing",
                            400,
                        )
                    )
                val existing =
                    dao.applyAuthoritativeMiniHome(
                        AuthoritativeMiniHomeCacheWrite.Layout(
                            "existing",
                            1,
                            "server-operation",
                            "a".repeat(64),
                            CachedMiniHomeEntity(
                                "existing",
                                "home-existing",
                                "server-existing",
                                0,
                                7,
                                400,
                            ),
                            emptyList(),
                        )
                    )

                assertTrue("v$version missing", missing is MiniHomeCacheApplyResult.Applied)
                assertTrue("v$version existing", existing is MiniHomeCacheApplyResult.Applied)
                assertEquals(true, missing.current.watermark.verified)
                assertEquals(true, existing.current.watermark.verified)
                assertNull(dao.miniHome("missing"))
                assertEquals("server-existing", dao.miniHome("existing")?.name)
            }
            migrated.close()

            val reopened =
                Room.databaseBuilder(context, PlanteriorDatabase::class.java, name)
                    .allowMainThreadQueries()
                    .build()
            runBlocking {
                assertEquals(true, reopened.cacheDao().miniHomeCacheWatermark("missing")?.verified)
                assertEquals(true, reopened.cacheDao().miniHomeCacheWatermark("existing")?.verified)
                assertNull(reopened.cacheDao().miniHome("missing"))
                assertEquals("server-existing", reopened.cacheDao().miniHome("existing")?.name)
            }
            reopened.close()
            assertTrue(context.deleteDatabase(name))
        }
    }

    private fun createDatabaseFromSchema(
        context: Context,
        name: String,
        version: Int,
        seed: (SupportSQLiteDatabase) -> Unit,
    ) {
        context.deleteDatabase(name)
        val schema = JSONObject(schemaFile(version).readText()).getJSONObject("database")
        val configuration =
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            val entities = schema.getJSONArray("entities")
                            repeat(entities.length()) { index ->
                                val entity = entities.getJSONObject(index)
                                val tableName = entity.getString("tableName")
                                db.execSQL(
                                    entity
                                        .getString("createSql")
                                        .replace("${'$'}{TABLE_NAME}", tableName)
                                )
                                val indices = entity.optJSONArray("indices")
                                if (indices != null) {
                                    repeat(indices.length()) { indexIndex ->
                                        db.execSQL(
                                            indices
                                                .getJSONObject(indexIndex)
                                                .getString("createSql")
                                                .replace("${'$'}{TABLE_NAME}", tableName)
                                        )
                                    }
                                }
                            }
                            db.execSQL(
                                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
                            )
                            db.execSQL(
                                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                                arrayOf(schema.getString("identityHash")),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    }
                )
                .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            seed(helper.writableDatabase)
        }
    }

    private fun schemaFile(version: Int): File {
        val relative =
            "core/database/schemas/com.planterior.helper.core.database.PlanteriorDatabase/$version.json"
        return sequenceOf(
                File(System.getProperty("user.dir"), relative),
                File(
                    System.getProperty("user.dir"),
                    relative.removePrefix("core/database/"),
                ),
            )
            .first { it.isFile }
    }

    private companion object {
        val legacyNames =
            listOf(
                "recoverable" to "e\u0301",
                "canonical" to "정상 이름",
                "control" to "A\u0000B",
                "bidi" to "A\u202EB",
                "overlength" to "x".repeat(101),
            )
    }
}
