package com.planterior.helper

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.planterior.helper.auth.AuthRuntimeDependencyOverrides
import com.planterior.helper.auth.Todo18DebugRuntimeDependencies
import com.planterior.helper.auth.todo18DebugRuntimeDependencyOverrides
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.diagnostic.Todo18CaptureFreshness
import com.planterior.helper.feature.camera.CameraPermission
import com.planterior.helper.feature.camera.Todo18DebugCameraBoundary
import com.planterior.helper.feature.collection.CollectionWateringPreparationSource
import com.planterior.helper.feature.collection.FirebaseCollectionRepository
import com.planterior.helper.feature.collection.PlaceholderPlantThumbnailLoader
import com.planterior.helper.feature.minihome.FirebaseMiniHomeRepository
import com.planterior.helper.feature.registration.FirebaseRegistrationRepository
import com.planterior.helper.feature.shop.FirebaseInventoryRepository
import com.planterior.helper.feature.shop.PlaceholderCatalogMediaLoader
import com.planterior.helper.feature.watering.OutboxWateringRepository
import com.planterior.helper.inventory.Todo18InventoryCacheSettlementRepository
import com.planterior.helper.minihome.Todo18MiniHomeLoadDiagnostic
import com.planterior.helper.minihome.Todo18MiniHomeLoadDiagnosticRecorder
import com.planterior.helper.minihome.Todo18MiniHomeLoadDiagnosticRepository
import com.planterior.helper.registration.Todo18RegistrationCommitDiagnosticRepository
import java.util.Collections
import java.util.IdentityHashMap
import org.junit.rules.ExternalResource

/** Installs production Room repositories with deterministic fakes only at remote/OS boundaries. */
class Todo18IntegratedRuntimeRule(private val accountUid: String = ACCOUNT_UID) :
    ExternalResource() {
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

    internal val initialSinkFreshness =
        Todo18CaptureFreshness(
            initialSequence = renderedStateSink.sequenceValue(),
            initialCurrentsEmpty =
                renderedStateSink.currentRawMiniHomeState() == null &&
                    renderedStateSink.currentDisplayedMiniHomeState() == null &&
                    renderedStateSink.currentRegistrationState() == null,
            initialListenerCount = renderedStateSink.primaryListenerCount(),
            isolatedInstance = true,
        )
    internal var priorActivityCount = -1
        private set

    internal var priorOverridePresent = true
        private set

    internal val previousTeardownComplete: Boolean
        get() = priorActivityCount == 0 && !priorOverridePresent

    internal val activityCreateCount: Int
        get() = synchronized(lifecycleLock) { createdActivities }

    internal val activityDestroyCount: Int
        get() = synchronized(lifecycleLock) { destroyedActivities }

    internal val activityActiveCount: Int
        get() = synchronized(lifecycleLock) { activeMainActivities.size }

    private val lifecycleLock = Any()
    private var createdActivities = 0
    private var destroyedActivities = 0
    private val activeMainActivities =
        Collections.newSetFromMap(IdentityHashMap<MainActivity, Boolean>())
    private lateinit var application: PlanteriorApplication
    private val activityCallbacks =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is MainActivity) {
                    synchronized(lifecycleLock) {
                        createdActivities += 1
                        activeMainActivities += activity
                    }
                }
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (activity is MainActivity) {
                    synchronized(lifecycleLock) {
                        destroyedActivities += 1
                        activeMainActivities -= activity
                    }
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        }

    override fun before() {
        actionDiagnostics.install()
        try {
            priorOverridePresent = todo18DebugRuntimeDependencyOverrides() != null
            priorActivityCount = currentMainActivityCount()
            if (priorOverridePresent) Todo18DebugRuntimeDependencies.clear()
            application = ApplicationProvider.getApplicationContext()
            application.registerActivityLifecycleCallbacks(activityCallbacks)
            val shared = requireNotNull(application.repositoryRuntimeOrNull())
            database = shared.database
            database.clearAllTables()
            boundary = Todo18Scenario(AccountId(accountUid))
            miniHomeLoadDiagnostics =
                Todo18MiniHomeLoadDiagnosticRecorder(boundary::emitMiniHomeLoadDiagnostic)

            val plants = Todo18PlantRepositoryFixture(boundary)
            val registration =
                Todo18RegistrationCommitDiagnosticRepository(
                    FirebaseRegistrationRepository(database, plants, plants, boundary::now),
                    renderedStateSink::onRegistrationCommitRepositoryEvent,
                )
            val collection = FirebaseCollectionRepository(database, plants, plants, boundary::now)
            val miniHomeDelegate =
                FirebaseMiniHomeRepository(
                    database,
                    Todo18MiniHomeRepositoryFixture(boundary, miniHomeLoadDiagnostics),
                    boundary::now,
                    beforeCacheApply = { accountId ->
                        miniHomeLoadDiagnostics.recordCurrent(
                            Todo18MiniHomeLoadDiagnostic.CacheApplyEntered(accountId)
                        )
                    },
                    afterCacheApply = { accountId, current ->
                        miniHomeLoadDiagnostics.recordCurrent(
                            Todo18MiniHomeLoadDiagnostic.CacheApplyReturned(accountId, current)
                        )
                    },
                    beforePublicationRead = {
                        miniHomeLoadDiagnostics.recordCurrent(
                            Todo18MiniHomeLoadDiagnostic.PublicationReadEntered
                        )
                    },
                )
            val miniHome =
                Todo18MiniHomeLoadDiagnosticRepository(
                    delegate = miniHomeDelegate,
                    diagnostics = miniHomeLoadDiagnostics,
                )
            val inventoryRemote = Todo18InventoryRepositoryFixture(boundary)
            val inventory =
                Todo18InventoryCacheSettlementRepository(
                    delegate =
                        FirebaseInventoryRepository(database, inventoryRemote, boundary::now),
                    onSettled = { boundary.emit("inventory-cache-settled", it.operationId.value) },
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
            actionDiagnostics.close()
            throw failure
        }
    }

    override fun after() {
        try {
            if (::application.isInitialized) {
                application.unregisterActivityLifecycleCallbacks(activityCallbacks)
            }
            Todo18DebugCameraBoundary.clear()
            Todo18DebugRuntimeDependencies.clear()
            if (::database.isInitialized && database.isOpen) database.clearAllTables()
        } finally {
            actionDiagnostics.close()
        }
    }

    internal fun actionListenerCount(): Int = actionDiagnostics.listenerCount()

    private fun currentMainActivityCount(): Int {
        var count = -1
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            val active = Collections.newSetFromMap(IdentityHashMap<MainActivity, Boolean>())
            Stage.values()
                .filterNot { it == Stage.DESTROYED }
                .forEach { stage ->
                    monitor
                        .getActivitiesInStage(stage)
                        .filterIsInstance<MainActivity>()
                        .forEach(active::add)
                }
            count = active.size
        }
        return count
    }

    fun denyCameraPermission() {
        Todo18DebugCameraBoundary.installPermission(CameraPermission.Denied(permanently = true))
    }

    fun returnMalformedPickerUri() {
        Todo18DebugCameraBoundary.installPickerUri("content://todo18.invalid/missing-photo")
    }

    companion object {
        const val ACCOUNT_UID = "todo18-integrated-owner"
    }
}
