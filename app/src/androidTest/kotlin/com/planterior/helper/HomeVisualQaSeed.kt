package com.planterior.helper

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.core.database.CachedMiniHomeEntity
import com.planterior.helper.core.database.CachedPlantEntity
import com.planterior.helper.core.database.CachedWateringScheduleEntity
import com.planterior.helper.core.database.LastSyncEntity
import com.planterior.helper.core.database.MIGRATION_1_2
import com.planterior.helper.core.database.MIGRATION_2_3
import com.planterior.helper.core.database.MIGRATION_3_4
import com.planterior.helper.core.database.MIGRATION_4_5
import com.planterior.helper.core.database.PlanteriorDatabase
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 시각 QA용 데이터를 앱이 실제로 읽는 Room 파일에 넣는다.
 *
 * 스크린샷이 진짜 저장된 데이터에서 나오도록, 화면 코드가 아니라 제품 캐시에 직접 쓴다. 마지막에 다시 읽어 확인하므로 시드가 조용히 실패하면 이 테스트가 먼저 깨진다.
 */
@RunWith(AndroidJUnit4::class)
class HomeVisualQaSeed {
    @Test
    fun seedHomeFixtures() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, "planterior.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
        try {
            runBlocking {
                val dao = database.cacheDao()
                dao.clearVisibleAccount(ACCOUNT)
                val today = LocalDate.now(ZoneId.systemDefault())
                listOf(
                        Triple("plant-today", "몬몬이 (몬스테라)", today),
                        Triple("plant-overdue", "지연이 (스킨답서스)", today.minusDays(2)),
                        Triple("plant-upcoming", "뾰족이 (스투키)", today.plusDays(3)),
                    )
                    .forEach { (id, name, due) ->
                        dao.upsertPlant(CachedPlantEntity(ACCOUNT, id, name, null, 1, 1))
                        dao.upsertSchedule(
                            CachedWateringScheduleEntity(
                                ACCOUNT,
                                "schedule-$id",
                                id,
                                due.toString(),
                                "09:00",
                                ZoneId.systemDefault().id,
                                1,
                                1,
                            )
                        )
                    }
                dao.upsertMiniHome(
                    CachedMiniHomeEntity(ACCOUNT, "mini-home", "민지의 미니 식물원", 3, 1, 1)
                )
                database
                    .syncDao()
                    .upsertLastSync(
                        LastSyncEntity(ACCOUNT, "PLANTS", 1_786_500_000_000, "SUCCESS", null)
                    )

                assertEquals(3, dao.plants(ACCOUNT).size)
                assertEquals("민지의 미니 식물원", dao.miniHome(ACCOUNT)?.name)
            }
        } finally {
            database.close()
        }
    }

    private companion object {
        const val ACCOUNT = "qa-home-account"
    }
}
