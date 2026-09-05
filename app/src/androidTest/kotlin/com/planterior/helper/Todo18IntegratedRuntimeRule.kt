package com.planterior.helper

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.auth.AuthRuntimeDependencyOverrides
import com.planterior.helper.auth.Todo18DebugRuntimeDependencies
import com.planterior.helper.auth.todo18DebugRuntimeDependencyOverrides
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.diagnostic.Todo18CaptureFreshness
import com.planterior.helper.diagnostic.Todo18DiagnosticProvenance
import com.planterior.helper.diagnostic.Todo18RoomTransactionOwnerRecorder
import com.planterior.helper.diagnostic.attachTodo18RoomTransactionOwnerListener
import com.planterior.helper.feature.camera.CameraPermission
import com.planterior.helper.feature.camera.Todo18DebugCameraBoundary
import com.planterior.helper.feature.collection.CollectionWateringPreparationSource
import com.planterior.helper.feature.collection.FirebaseCollectionRepository
import com.planterior.helper.feature.collection.PlaceholderPlantThumbnailLoader
import com.planterior.helper.feature.registration.FirebaseRegistrationRepository
import com.planterior.helper.feature.shop.FirebaseInventoryRepository
import com.planterior.helper.feature.shop.PlaceholderCatalogMediaLoader
import com.planterior.helper.feature.watering.OutboxWateringRepository
import com.planterior.helper.inventory.Todo18InventoryCacheSettlementRepository
import com.planterior.helper.inventory.Todo18InventorySettlementDiagnosticRecorder
import com.planterior.helper.inventory.Todo18InventorySettlementObservation
import com.planterior.helper.inventory.Todo18InventorySettlementStage
import com.planterior.helper.minihome.Todo18MiniHomeLoadDiagnosticRecorder
import com.planterior.helper.minihome.Todo18MiniHomeOwnerOperationDiagnosticRecorder
import com.planterior.helper.registration.Todo18RegistrationCommitDiagnosticRepository
import java.io.File
import org.junit.rules.ExternalResource
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Installs production Room repositories with deterministic fakes only at remote/OS boundaries. */
class Todo18IntegratedRuntimeRule(private val accountUid: String = ACCOUNT_UID) :
    ExternalResource() {
    override fun apply(base: Statement, description: Description): Statement =
        todo18RuntimeDiagnosticStatement(
            { before() },
            { base.evaluate() },
            { after() },
        ) {
            writeMiniHomeBoundarySnapshot(description.methodName, it)
        }

    lateinit var boundary: Todo18Scenario
        private set

    lateinit var database: PlanteriorDatabase
        private set

    internal val renderedStateSink = Todo18RenderedStateSink()
    private val actionDiagnostics = Todo18IntegratedActionDiagnostics()
    internal val actionRecorder: Todo18IntegratedActionRecorder
        get() = actionDiagnostics.recorder

    internal lateinit var miniHomeLoadDiagnostics: Todo18MiniHomeLoadDiagnosticRecorder
        private set

    internal lateinit var miniHomeOwnerDiagnostics: Todo18MiniHomeOwnerOperationDiagnosticRecorder
        private set

    internal val inventorySettlementDiagnostics = Todo18InventorySettlementDiagnosticRecorder()
    internal val roomTransactionOwners = Todo18RoomTransactionOwnerRecorder()
    private var roomTransactionOwnerListener: AutoCloseable? = null
    private var boundaryProvenance: Todo18DiagnosticProvenance? = null

    internal val initialSinkFreshness =
        Todo18CaptureFreshness(
            initialSequence = renderedStateSink.sequenceValue(),
            initialCurrentsEmpty =
                renderedStateSink.currentRawMiniHomeState() == null &&
                    renderedStateSink.currentRouteMiniHomeState() == null &&
                    renderedStateSink.currentDisplayedMiniHomeState() == null &&
                    renderedStateSink.currentRegistrationState() == null &&
                    renderedStateSink.currentInventoryFeedback() == null,
            initialListenerCount = renderedStateSink.primaryListenerCount(),
            isolatedInstance = true,
        )
    internal var priorActivityCount = -1
        private set

    internal var priorOverridePresent = true
        private set

    internal val previousTeardownComplete: Boolean
        get() = priorActivityCount == 0 && !priorOverridePresent

    private val activityTracker = Todo18IntegratedRuntimeActivityTracker()

    internal val activityCreateCount: Int
        get() = activityTracker.activityCreateCount

    internal val activityDestroyCount: Int
        get() = activityTracker.activityDestroyCount

    internal val activityActiveCount: Int
        get() = activityTracker.activityActiveCount

    private lateinit var application: PlanteriorApplication

    override fun before() {
        actionDiagnostics.install()
        roomTransactionOwnerListener =
            attachTodo18RoomTransactionOwnerListener(roomTransactionOwners::record)
        try {
            priorOverridePresent = todo18DebugRuntimeDependencyOverrides() != null
            priorActivityCount = activityTracker.currentMainActivityCount()
            if (priorOverridePresent) Todo18DebugRuntimeDependencies.clear()
            application = ApplicationProvider.getApplicationContext()
            application.registerActivityLifecycleCallbacks(activityTracker.activityCallbacks)
            val shared = requireNotNull(application.repositoryRuntimeOrNull())
            database = shared.database
            database.clearAllTables()
            boundary = Todo18Scenario(AccountId(accountUid))
            miniHomeLoadDiagnostics =
                Todo18MiniHomeLoadDiagnosticRecorder(boundary::emitMiniHomeLoadDiagnostic)
            miniHomeOwnerDiagnostics = Todo18MiniHomeOwnerOperationDiagnosticRecorder()
            renderedStateSink.observeInventoryDiagnostics(inventorySettlementDiagnostics::record)

            val plants = Todo18PlantRepositoryFixture(boundary)
            val callback = renderedStateSink::onRegistrationPersistenceDiagnostic
            val registration =
                Todo18RegistrationCommitDiagnosticRepository(
                    FirebaseRegistrationRepository(
                        database,
                        plants,
                        plants,
                        boundary::now,
                        callback,
                    ),
                    renderedStateSink::onRegistrationCommitRepositoryEvent,
                )
            val collection = FirebaseCollectionRepository(database, plants, plants, boundary::now)
            val miniHome =
                todo18MiniHomeRuntimeRepository(
                    database,
                    boundary,
                    miniHomeLoadDiagnostics,
                    miniHomeOwnerDiagnostics,
                )
            val inventoryRemote = Todo18InventoryRepositoryFixture(boundary)
            val inventory =
                Todo18InventoryCacheSettlementRepository(
                    delegate =
                        FirebaseInventoryRepository(database, inventoryRemote, boundary::now),
                    onSettled = {
                        boundary.emit("inventory-cache-settled", it.operationId.value)
                        inventorySettlementDiagnostics.record(
                            Todo18InventorySettlementObservation(
                                Todo18InventorySettlementStage.BOUNDARY_DELIVERY,
                                it,
                            )
                        )
                    },
                    onAcquired = renderedStateSink::armInventoryFeedback,
                    diagnostics = inventorySettlementDiagnostics,
                )
            val watering =
                OutboxWateringRepository(
                    database,
                    CollectionWateringPreparationSource(collection),
                    plants,
                    plants,
                    boundary::now,
                )

            Todo18DebugRuntimeDependencies.install(
                AuthRuntimeDependencyOverrides(
                    registrationRepository = registration,
                    collectionRepository = collection,
                    miniHomeRepository = miniHome,
                    miniHomeShareRepository = Todo18ShareRepositoryFixture(miniHome, boundary),
                    inventoryRepository = inventory,
                    wateringRepository = watering,
                    weatherRepository = Todo18WeatherRepositoryFixture(boundary),
                    weatherPermissionCapabilities = Todo18WeatherCapabilityStore(),
                    collectionThumbnailLoader = PlaceholderPlantThumbnailLoader,
                    catalogMediaLoader = PlaceholderCatalogMediaLoader,
                    accountDeletionDependencies =
                        Todo18AccountDeletionRepositoryFixture(boundary).dependencies,
                    renderedStateSink = renderedStateSink,
                )
            )
        } catch (failure: Throwable) {
            roomTransactionOwnerListener?.close()
            roomTransactionOwnerListener = null
            actionDiagnostics.close()
            throw failure
        }
    }

    override fun after() {
        try {
            boundaryProvenance = captureTodo18DiagnosticProvenance()
            if (::application.isInitialized) {
                application.unregisterActivityLifecycleCallbacks(activityTracker.activityCallbacks)
            }
            Todo18DebugCameraBoundary.clear()
            Todo18DebugRuntimeDependencies.clear()
            if (::database.isInitialized && database.isOpen) database.clearAllTables()
        } finally {
            roomTransactionOwnerListener?.close()
            roomTransactionOwnerListener = null
            actionDiagnostics.close()
        }
    }

    private fun writeMiniHomeBoundarySnapshot(
        methodName: String,
        primaryFailure: Throwable?,
    ): IllegalStateException? {
        if (!::application.isInitialized || !::miniHomeOwnerDiagnostics.isInitialized) return null
        return writeTodo18MiniHomeBoundarySnapshot(
            application,
            miniHomeOwnerDiagnostics,
            boundaryProvenance,
            methodName,
            primaryFailure,
            renderedStateSink.miniHomeShareDiagnostics.toJson(),
        )
    }

    internal fun actionListenerCount(): Int = actionDiagnostics.listenerCount()

    fun denyCameraPermission() {
        Todo18DebugCameraBoundary.installPermission(CameraPermission.Denied(permanently = true))
    }

    fun returnMalformedPickerUri() {
        val missingPhoto = File(application.cacheDir, "todo18/missing-photo.jpg")
        check(missingPhoto.exists().not()) {
            "Deterministic malformed-photo target unexpectedly exists: ${missingPhoto.absolutePath}"
        }
        Todo18DebugCameraBoundary.installPickerUri(Uri.fromFile(missingPhoto).toString())
    }

    companion object {
        const val ACCOUNT_UID = "todo18-integrated-owner"
    }
}
