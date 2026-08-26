package com.planterior.helper

import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.auth.AuthRuntimeDependencyOverrides
import com.planterior.helper.auth.Todo18DebugRuntimeDependencies
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
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
import org.junit.rules.ExternalResource

/** Installs production Room repositories with deterministic fakes only at remote/OS boundaries. */
class Todo18IntegratedRuntimeRule(private val accountUid: String = ACCOUNT_UID) :
    ExternalResource() {
    lateinit var boundary: Todo18Scenario
        private set

    lateinit var database: PlanteriorDatabase
        private set

    override fun before() {
        val application = ApplicationProvider.getApplicationContext<PlanteriorApplication>()
        val shared = requireNotNull(application.repositoryRuntimeOrNull())
        database = shared.database
        database.clearAllTables()
        boundary = Todo18Scenario(AccountId(accountUid))

        val plants = Todo18PlantRepositoryFixture(boundary)
        val registration = FirebaseRegistrationRepository(database, plants, plants, boundary::now)
        val collection = FirebaseCollectionRepository(database, plants, plants, boundary::now)
        val miniHome =
            FirebaseMiniHomeRepository(
                database,
                Todo18MiniHomeRepositoryFixture(boundary),
                boundary::now,
            )
        val inventory =
            FirebaseInventoryRepository(
                database,
                Todo18InventoryRepositoryFixture(boundary),
                boundary::now,
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
            )
        )
    }

    override fun after() {
        Todo18DebugCameraBoundary.clear()
        Todo18DebugRuntimeDependencies.clear()
        if (::database.isInitialized && database.isOpen) database.clearAllTables()
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
