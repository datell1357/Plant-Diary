package com.planterior.helper.feature.collection

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.data.RemoteMutationCommand
import com.planterior.helper.core.data.RemoteMutationGateway
import com.planterior.helper.core.data.RemoteMutationResult
import com.planterior.helper.core.database.CachedPlantEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.core.model.PublicationState
import com.planterior.helper.core.model.RegistrationMethod
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CollectionRepositoryTest {
    private lateinit var database: PlanteriorDatabase

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    PlanteriorDatabase::class.java,
                )
                .build()
    }

    @After fun tearDown() = database.close()

    @Test
    fun `collection refresh is account scoped and immediately returns current account content`() =
        runTest {
            database.cacheDao().upsertPlant(cached(account = "account-b", id = "foreign"))
            val remote = FakeRemote(plants = listOf(remotePlant(id = "plant-a")))
            val repository = repository(remote)

            val result = repository.loadCollection()

            assertEquals(
                listOf("plant-a"),
                (result as CollectionLoad.Fresh).items.map { it.id.value },
            )
            assertFalse(result.items.any { it.id.value == "foreign" })
            assertEquals(
                listOf("plant-a"),
                database.cacheDao().plants("account-a").map { it.plantId },
            )
            assertTrue(
                requireNotNull(database.cacheDao().plant("account-a", "plant-a")).detailsComplete
            )
            assertEquals(
                listOf("foreign"),
                database.cacheDao().plants("account-b").map { it.plantId },
            )
        }

    @Test
    fun `fresh collection follows cache order updated descending then plant id ascending`() =
        runTest {
            val result =
                repository(
                        FakeRemote(
                            plants =
                                listOf(
                                    remotePlant(id = "plant-c", updatedAt = "2026-08-17T00:00:00Z"),
                                    remotePlant(id = "plant-b", updatedAt = "2026-08-18T00:00:00Z"),
                                    remotePlant(id = "plant-a", updatedAt = "2026-08-18T00:00:00Z"),
                                )
                        )
                    )
                    .loadCollection() as CollectionLoad.Fresh

            assertEquals(listOf("plant-a", "plant-b", "plant-c"), result.items.map { it.id.value })
        }

    @Test
    fun `account switch during collection read exposes and caches none of captured account`() =
        runTest {
            val remote = FakeRemote(plants = listOf(remotePlant(id = "captured")))
            remote.afterPlants = { remote.activeAccount = AccountId("account-b") }

            val result = repository(remote).loadCollection()

            assertEquals(CollectionLoad.Failed, result)
            assertTrue(database.cacheDao().plants("account-a").isEmpty())
            assertTrue(database.cacheDao().plants("account-b").isEmpty())
        }

    @Test
    fun `collection remote failure uses only the current account cache as stale content`() =
        runTest {
            database.cacheDao().upsertPlant(cached(id = "cached-a"))
            database.cacheDao().upsertPlant(cached(account = "account-b", id = "cached-b"))
            val repository =
                repository(FakeRemote(collectionFailure = IllegalStateException("offline")))

            val result = repository.loadCollection()

            assertEquals(
                listOf("cached-a"),
                (result as CollectionLoad.Stale).items.map { it.id.value },
            )
        }

    @Test
    fun `collection failure without cache is an error rather than an invented fixture`() = runTest {
        val result =
            repository(FakeRemote(collectionFailure = IllegalStateException("offline")))
                .loadCollection()

        assertEquals(CollectionLoad.Failed, result)
    }

    @Test
    fun `production Room config loads collection detail from a main coroutine`() = runTest {
        val result =
            repository(
                    FakeRemote(
                        detail = RemotePlantLookup.Found(remotePlant()),
                        content = publicContent(),
                    )
                )
                .loadDetail(PersonalPlantId("plant-a"))

        assertTrue(result is DetailLoad.Fresh)
    }

    @Test
    fun `detail exposes all care standards and only public symptom guidance`() = runTest {
        val remote =
            FakeRemote(
                detail = RemotePlantLookup.Found(remotePlant()),
                content = publicContent(),
                symptoms =
                    listOf(
                        remoteSymptom("public", PublicationState.PUBLIC),
                        remoteSymptom("draft", PublicationState.DRAFT),
                        remoteSymptom("private", PublicationState.PRIVATE),
                    ),
            )

        val result = repository(remote).loadDetail(PersonalPlantId("plant-a"))

        val detail = (result as DetailLoad.Fresh).detail
        assertEquals(7, detail.guidance.wateringIntervalDays)
        assertEquals("밝은 간접광", detail.guidance.lightGuidance)
        assertEquals(18.0, detail.guidance.minimumTemperatureCelsius)
        assertEquals(28.0, detail.guidance.maximumTemperatureCelsius)
        assertEquals(40, detail.guidance.minimumHumidityPercent)
        assertEquals(70, detail.guidance.maximumHumidityPercent)
        assertEquals(listOf("public"), detail.guidance.symptoms.map { it.id })
        assertFalse(
            detail.guidance.symptoms.any {
                it.possibleCause.contains("draft") || it.action.contains("draft")
            }
        )
    }

    @Test
    fun `firestore watering interval is range checked as Long before Int conversion`() {
        assertEquals(1, 1L.toPublicWateringIntervalDays())
        assertEquals(365, 365L.toPublicWateringIntervalDays())
        assertEquals(null, 0L.toPublicWateringIntervalDays())
        assertEquals(null, 366L.toPublicWateringIntervalDays())
        assertEquals(null, Long.MAX_VALUE.toPublicWateringIntervalDays())
        assertEquals(null, Long.MIN_VALUE.toPublicWateringIntervalDays())
    }

    @Test
    fun `missing humidity is a partial detail while available standards remain visible`() =
        runTest {
            val remote =
                FakeRemote(
                    detail = RemotePlantLookup.Found(remotePlant()),
                    content =
                        publicContent(
                            minimumHumidityPercent = null,
                            maximumHumidityPercent = null,
                        ),
                )

            val result = repository(remote).loadDetail(PersonalPlantId("plant-a"))

            val partial = result as DetailLoad.Partial
            assertEquals(setOf(CareField.HUMIDITY), partial.missing)
            assertEquals("밝은 간접광", partial.detail.guidance.lightGuidance)
        }

    @Test
    fun `symptom fetch failure is partial while a successful empty symptom list is fresh`() =
        runTest {
            val missing =
                repository(
                        FakeRemote(
                            detail = RemotePlantLookup.Found(remotePlant()),
                            content = publicContent(),
                            symptomsFailure = IllegalStateException("unavailable"),
                        )
                    )
                    .loadDetail(PersonalPlantId("plant-a"))
            val empty =
                repository(
                        FakeRemote(
                            detail = RemotePlantLookup.Found(remotePlant()),
                            content = publicContent(),
                            symptoms = emptyList(),
                        )
                    )
                    .loadDetail(PersonalPlantId("plant-a"))

            assertEquals(
                setOf(CareField.SYMPTOMS),
                (missing as DetailLoad.Partial).missing,
            )
            assertTrue(empty is DetailLoad.Fresh)
            assertTrue((empty as DetailLoad.Fresh).detail.guidance.symptoms.isEmpty())
        }

    @Test
    fun `free entry plant has an explicit no standard content state`() = runTest {
        val remote =
            FakeRemote(
                detail =
                    RemotePlantLookup.Found(
                        remotePlant(contentId = null, method = RegistrationMethod.MANUAL)
                    )
            )

        val result = repository(remote).loadDetail(PersonalPlantId("plant-a"))

        assertTrue(result is DetailLoad.NoStandardContent)
        assertEquals("plant-a", (result as DetailLoad.NoStandardContent).plant.id.value)
    }

    @Test
    fun `private or missing standard content is never used as fallback`() = runTest {
        val remote =
            FakeRemote(
                detail = RemotePlantLookup.Found(remotePlant()),
                content = publicContent(publicationState = PublicationState.PRIVATE),
                symptoms = listOf(remoteSymptom("private", PublicationState.PRIVATE)),
            )

        val result = repository(remote).loadDetail(PersonalPlantId("plant-a"))

        assertTrue(result is DetailLoad.NoStandardContent)
    }

    @Test
    fun `offline detail uses cached owner data but never cached managed guidance`() = runTest {
        database
            .cacheDao()
            .upsertPlant(
                cached(
                    id = "plant-a",
                    contentId = "species-a",
                    location = "거실 창가",
                    note = "새잎 관찰",
                    lastWateredDate = "2026-08-11",
                )
            )
        val remote = FakeRemote(detail = RemotePlantLookup.Failed)

        val result = repository(remote).loadDetail(PersonalPlantId("plant-a"))

        val stale = result as DetailLoad.Stale
        assertTrue(stale.editingAllowed)
        assertEquals("거실 창가", stale.plant.location)
        assertEquals("새잎 관찰", stale.plant.privateNote)
        assertEquals(LocalDate.of(2026, 8, 11), stale.plant.lastWateredDate)
        assertEquals(null, stale.guidance)
    }

    @Test
    fun `legacy incomplete stale detail cannot be edited until an owner refresh`() = runTest {
        database.cacheDao().upsertPlant(cached(id = "plant-a", detailsComplete = false))

        val result =
            repository(FakeRemote(detail = RemotePlantLookup.Failed))
                .loadDetail(PersonalPlantId("plant-a")) as DetailLoad.Stale

        assertFalse(result.editingAllowed)
    }

    @Test
    fun `account switch after any detail suspension returns forbidden without cache mutation`() =
        runTest {
            suspend fun switched(remote: FakeRemote): DetailLoad {
                database.cacheDao().upsertPlant(cached(id = "existing"))
                return repository(remote).loadDetail(PersonalPlantId("plant-a"))
            }

            val afterZone = FakeRemote(detail = RemotePlantLookup.Found(remotePlant()))
            afterZone.afterAccountZone = { afterZone.activeAccount = AccountId("account-b") }
            assertEquals(DetailLoad.Forbidden, switched(afterZone))

            val afterPlant = FakeRemote(detail = RemotePlantLookup.Found(remotePlant()))
            afterPlant.afterPlant = { afterPlant.activeAccount = AccountId("account-b") }
            assertEquals(DetailLoad.Forbidden, switched(afterPlant))

            val afterContent =
                FakeRemote(
                    detail = RemotePlantLookup.Found(remotePlant()),
                    content = publicContent(),
                )
            afterContent.afterContent = { afterContent.activeAccount = AccountId("account-b") }
            assertEquals(DetailLoad.Forbidden, switched(afterContent))

            val afterSymptoms =
                FakeRemote(
                    detail = RemotePlantLookup.Found(remotePlant()),
                    content = publicContent(),
                )
            afterSymptoms.afterSymptoms = { afterSymptoms.activeAccount = AccountId("account-b") }
            assertEquals(DetailLoad.Forbidden, switched(afterSymptoms))

            assertEquals(null, database.cacheDao().plant("account-a", "plant-a"))
        }

    @Test
    fun `account switch immediately before stale fallback returns forbidden`() = runTest {
        database.cacheDao().upsertPlant(cached(id = "plant-a"))
        val remote = FakeRemote(detailFailure = IllegalStateException("offline"))
        remote.beforePlantFailure = { remote.activeAccount = AccountId("account-b") }

        assertEquals(
            DetailLoad.Forbidden,
            repository(remote).loadDetail(PersonalPlantId("plant-a")),
        )
    }

    @Test
    fun `forbidden and not found remain indistinguishable from foreign account contents`() =
        runTest {
            val forbidden =
                repository(FakeRemote(detail = RemotePlantLookup.Forbidden))
                    .loadDetail(PersonalPlantId("foreign-id"))
            val missing =
                repository(FakeRemote(detail = RemotePlantLookup.NotFound))
                    .loadDetail(PersonalPlantId("missing-id"))

            assertEquals(DetailLoad.Forbidden, forbidden)
            assertEquals(DetailLoad.NotFound, missing)
            assertFalse(database.cacheDao().plants("account-a").any { it.plantId == "foreign-id" })
        }

    @Test
    fun `failed edit retains one stable account scoped outbox and duplicate retry commits`() =
        runTest {
            val remote = FakeRemote(detail = RemotePlantLookup.Found(remotePlant()))
            val gateway = SequencedGateway()
            val repository = repository(remote, gateway)
            val request = editRequest()

            val failed = repository.saveEdit(request)
            assertTrue(failed is EditResult.Failed)
            assertEquals(1, database.syncDao().pending("account-a").size)
            assertEquals("거실", request.location)
            assertEquals("잎을 돌려줌", request.privateNote)

            remote.detail =
                RemotePlantLookup.Found(
                    remotePlant(
                        revision = 2,
                        location = request.location,
                        note = request.privateNote,
                        lastWateredDate = request.lastWateredDate,
                    )
                )
            val completed = repository.saveEdit(request)

            assertTrue(completed is EditResult.Saved)
            assertEquals(1, gateway.commands.map { it.operationId }.distinct().size)
            assertEquals(
                listOf("personalPlants"),
                gateway.commands.map { it.aggregateType }.distinct(),
            )
            assertEquals(listOf("UPDATE"), gateway.commands.map { it.mutationType }.distinct())
            assertEquals(
                setOf("lastWateredDate", "location", "note"),
                Json.parseToJsonElement(gateway.commands.first().draftPayload).jsonObject.keys,
            )
            assertEquals(0, database.syncDao().pending("account-a").size)
            val cached = database.cacheDao().plant("account-a", "plant-a")
            assertEquals("거실", cached?.location)
            assertEquals("잎을 돌려줌", cached?.note)
        }

    @Test
    fun `reconciliation keeps the old mismatched outbox non ready and reloads server detail`() =
        runTest {
            val request = editRequest()
            database
                .syncDao()
                .enqueue(
                    com.planterior.helper.core.database.OperationOutboxEntity(
                        operationId = request.operationId.value,
                        accountId = request.accountId.value,
                        aggregateType = "personalPlants",
                        aggregateId = request.plantId.value,
                        mutationType = "UPDATE",
                        expectedRevision = 1,
                        draftPayload = "{\"frozen\":true}",
                        createdAtEpochMillis = 1,
                    )
                )
            val remote =
                FakeRemote(
                    detail = RemotePlantLookup.Found(remotePlant(revision = 2, location = "서버 위치")),
                    content = publicContent(),
                )
            val repository = repository(remote)

            assertEquals(
                EditResult.Failed(EditFailure.OUTBOX_MISMATCH),
                repository.saveEdit(request),
            )

            val reloaded =
                repository.reconcileFailedEdit(
                    request.accountId,
                    request.plantId,
                    request.operationId,
                )

            assertTrue(reloaded is DetailLoad.Fresh)
            assertEquals(
                "서버 위치",
                (reloaded as DetailLoad.Fresh).detail.plant.location,
            )
            val retained =
                requireNotNull(
                    database.syncDao().operation(request.accountId.value, request.operationId.value)
                )
            assertEquals("FAILED", retained.state)
            assertEquals("OUTBOX_MISMATCH", retained.lastErrorCode)
            assertTrue(database.syncDao().ready(request.accountId.value).isEmpty())
        }

    @Test
    fun `account switch rejects edit before outbox and callable`() = runTest {
        val remote = FakeRemote(activeAccount = AccountId("account-b"))
        val gateway = SequencedGateway()

        val result = repository(remote, gateway).saveEdit(editRequest())

        assertEquals(EditResult.Forbidden, result)
        assertTrue(gateway.commands.isEmpty())
        assertTrue(database.syncDao().pending("account-a").isEmpty())
    }

    @Test
    fun `database shutdown is surfaced instead of running a detail query on a closed Room`() =
        runTest {
            database.close()

            try {
                repository(FakeRemote(detail = RemotePlantLookup.Found(remotePlant())))
                    .loadDetail(PersonalPlantId("plant-a"))
                fail("IllegalStateException expected")
            } catch (_: IllegalStateException) {
                assertFalse(database.isOpen)
            }
        }

    @Test
    fun `repository preserves cancellation from read and write boundaries`() = runTest {
        val readCancellation = CancellationException("detail left")
        try {
            repository(FakeRemote(detailFailure = readCancellation))
                .loadDetail(PersonalPlantId("plant-a"))
            fail("CancellationException expected")
        } catch (error: CancellationException) {
            assertSame(readCancellation, error)
        }

        val writeCancellation = CancellationException("edit left")
        try {
            repository(
                    FakeRemote(detail = RemotePlantLookup.Found(remotePlant())),
                    RemoteMutationGateway { throw writeCancellation },
                )
                .saveEdit(editRequest())
            fail("CancellationException expected")
        } catch (error: CancellationException) {
            assertSame(writeCancellation, error)
        }
    }

    private fun repository(
        remote: FakeRemote,
        gateway: RemoteMutationGateway = RemoteMutationGateway { RemoteMutationResult.Applied(2) },
    ) =
        FirebaseCollectionRepository(database, remote, gateway) {
            Instant.parse("2026-08-18T00:00:00Z")
        }

    private fun cached(
        account: String = "account-a",
        id: String = "plant-a",
        contentId: String? = "species-a",
        location: String? = null,
        note: String? = null,
        lastWateredDate: String? = null,
        detailsComplete: Boolean = true,
    ) =
        CachedPlantEntity(
            accountId = account,
            plantId = id,
            displayName = "몬스테라",
            representativePhotoPath = null,
            revision = 1,
            updatedAtEpochMillis = 1,
            contentId = contentId,
            registrationMethod = RegistrationMethod.IDENTIFIED.name,
            location = location,
            note = note,
            lastWateredDate = lastWateredDate,
            detailsComplete = detailsComplete,
        )

    private fun remotePlant(
        id: String = "plant-a",
        accountId: AccountId = AccountId("account-a"),
        contentId: String? = "species-a",
        method: RegistrationMethod = RegistrationMethod.IDENTIFIED,
        revision: Long = 1,
        location: String? = null,
        note: String? = null,
        lastWateredDate: LocalDate? = null,
        updatedAt: String = "2026-08-18T00:00:00Z",
    ) =
        RemotePersonalPlant(
            accountId = accountId,
            id = PersonalPlantId(id),
            displayName = "몬스테라",
            contentId = contentId?.let(::PlantContentId),
            registrationMethod = method,
            representativePhotoPath = null,
            location = location,
            privateNote = note,
            lastWateredDate = lastWateredDate,
            revision = revision,
            updatedAt = Instant.parse(updatedAt),
        )

    private fun publicContent(
        publicationState: PublicationState = PublicationState.PUBLIC,
        minimumHumidityPercent: Int? = 40,
        maximumHumidityPercent: Int? = 70,
    ) =
        RemotePlantContent(
            id = PlantContentId("species-a"),
            wateringIntervalDays = 7,
            lightGuidance = "밝은 간접광",
            minimumTemperatureCelsius = 18.0,
            maximumTemperatureCelsius = 28.0,
            minimumHumidityPercent = minimumHumidityPercent,
            maximumHumidityPercent = maximumHumidityPercent,
            publicationState = publicationState,
        )

    private fun remoteSymptom(id: String, publicationState: PublicationState) =
        RemoteSymptomGuidance(
            id = id,
            symptom = "잎 처짐",
            possibleCause = "$id cause",
            action = "$id action",
            publicationState = publicationState,
        )

    private fun editRequest() =
        PlantEditRequest(
            accountId = AccountId("account-a"),
            plantId = PersonalPlantId("plant-a"),
            operationId = OperationId("operation-edit-stable"),
            expectedRevision = 1,
            displayName = "몬스테라",
            contentId = PlantContentId("species-a"),
            registrationMethod = RegistrationMethod.IDENTIFIED,
            representativePhotoPath = null,
            lastWateredDate = LocalDate.of(2026, 8, 12),
            location = "거실",
            privateNote = "잎을 돌려줌",
            dirtyFields = PlantEditField.entries.toSet(),
        )

    private class SequencedGateway : RemoteMutationGateway {
        val commands = mutableListOf<RemoteMutationCommand>()

        override suspend fun apply(command: RemoteMutationCommand): RemoteMutationResult {
            commands += command
            return if (commands.size == 1) RemoteMutationResult.Failed("UNAVAILABLE")
            else RemoteMutationResult.Duplicate(2)
        }
    }

    private class FakeRemote(
        activeAccount: AccountId = AccountId("account-a"),
        private val plants: List<RemotePersonalPlant> = emptyList(),
        private val collectionFailure: Throwable? = null,
        detail: RemotePlantLookup = RemotePlantLookup.NotFound,
        private val detailFailure: Throwable? = null,
        private val content: RemotePlantContent? = null,
        private val symptoms: List<RemoteSymptomGuidance> = emptyList(),
        private val symptomsFailure: Throwable? = null,
    ) : CollectionRemoteDataSource {
        var activeAccount: AccountId = activeAccount
        var detail: RemotePlantLookup = detail
        var afterPlants: () -> Unit = {}
        var afterAccountZone: () -> Unit = {}
        var afterPlant: () -> Unit = {}
        var beforePlantFailure: () -> Unit = {}
        var afterContent: () -> Unit = {}
        var afterSymptoms: () -> Unit = {}

        override fun activeAccount() = activeAccount

        override suspend fun accountZone(accountId: AccountId): java.time.ZoneId {
            afterAccountZone()
            return java.time.ZoneId.of("Asia/Seoul")
        }

        override suspend fun plants(accountId: AccountId): List<RemotePersonalPlant> {
            collectionFailure?.let { throw it }
            afterPlants()
            return plants
        }

        override suspend fun plant(
            accountId: AccountId,
            plantId: PersonalPlantId,
        ): RemotePlantLookup {
            detailFailure?.let {
                beforePlantFailure()
                throw it
            }
            afterPlant()
            return detail
        }

        override suspend fun publicContent(contentId: PlantContentId): RemotePlantContent? {
            afterContent()
            return content
        }

        override suspend fun publicSymptoms(
            contentId: PlantContentId
        ): List<RemoteSymptomGuidance> {
            symptomsFailure?.let { throw it }
            afterSymptoms()
            return symptoms
        }
    }
}
