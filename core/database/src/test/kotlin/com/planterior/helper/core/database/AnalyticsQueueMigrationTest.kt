package com.planterior.helper.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AnalyticsQueueMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun cleanUp() {
        context.deleteDatabase(DATABASE)
    }

    @Test
    fun `twenty to twenty one adds empty owner revision analytics queue`() {
        val current =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, DATABASE)
                .allowMainThreadQueries()
                .build()
        current.openHelper.writableDatabase
        current.close()
        context.openOrCreateDatabase(DATABASE, Context.MODE_PRIVATE, null).use { versionTwenty ->
            versionTwenty.execSQL("DROP TABLE analytics_event_queue")
            versionTwenty.version = 20
        }

        val migrated =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, DATABASE)
                .addMigrations(MIGRATION_20_21)
                .allowMainThreadQueries()
                .build()
        try {
            val columns =
                migrated.openHelper.readableDatabase
                    .query("PRAGMA table_info(analytics_event_queue)")
                    .use { cursor ->
                        buildList {
                            val name = cursor.getColumnIndexOrThrow("name")
                            while (cursor.moveToNext()) add(cursor.getString(name))
                        }
                    }
            assertEquals(
                listOf(
                    "accountId",
                    "eventId",
                    "eventName",
                    "consentRevision",
                    "enqueuedAtEpochMillis",
                ),
                columns,
            )
        } finally {
            migrated.close()
        }
    }

    private companion object {
        const val DATABASE = "analytics-queue-migration.db"
    }
}
