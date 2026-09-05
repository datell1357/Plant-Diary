package com.planterior.helper.auth

import android.content.Context
import androidx.core.net.toUri
import androidx.room.Room
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.planterior.helper.BuildConfig
import com.planterior.helper.FirebaseRuntime
import com.planterior.helper.analytics.AnalyticsRuntime
import com.planterior.helper.analytics.FirebaseAnalyticsRemoteGateway
import com.planterior.helper.core.data.FirebasePrivateMediaGateway
import com.planterior.helper.core.data.FirebaseRemoteMutationGateway
import com.planterior.helper.core.data.OfflineFirstSyncRepository
import com.planterior.helper.core.database.MIGRATION_10_11
import com.planterior.helper.core.database.MIGRATION_11_12
import com.planterior.helper.core.database.MIGRATION_12_13
import com.planterior.helper.core.database.MIGRATION_13_14
import com.planterior.helper.core.database.MIGRATION_14_15
import com.planterior.helper.core.database.MIGRATION_15_16
import com.planterior.helper.core.database.MIGRATION_16_17
import com.planterior.helper.core.database.MIGRATION_17_18
import com.planterior.helper.core.database.MIGRATION_18_19
import com.planterior.helper.core.database.MIGRATION_19_20
import com.planterior.helper.core.database.MIGRATION_1_2
import com.planterior.helper.core.database.MIGRATION_20_21
import com.planterior.helper.core.database.MIGRATION_2_3
import com.planterior.helper.core.database.MIGRATION_3_4
import com.planterior.helper.core.database.MIGRATION_4_5
import com.planterior.helper.core.database.MIGRATION_5_6
import com.planterior.helper.core.database.MIGRATION_6_7
import com.planterior.helper.core.database.MIGRATION_7_8
import com.planterior.helper.core.database.MIGRATION_8_9
import com.planterior.helper.core.database.MIGRATION_9_10
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.database.RoomTransactionOwnerDiagnostics
import com.planterior.helper.diagnostic.roomTransactionOwnerDiagnostics
import com.planterior.helper.feature.collection.CollectionWateringPreparationSource
import com.planterior.helper.feature.collection.FirebaseCollectionRemoteDataSource
import com.planterior.helper.feature.collection.FirebaseCollectionRepository
import com.planterior.helper.feature.collection.FirebasePlantThumbnailLoader
import com.planterior.helper.feature.minihome.FirebaseMiniHomeRemoteDataSource
import com.planterior.helper.feature.minihome.FirebaseMiniHomeRepository
import com.planterior.helper.feature.registration.FirebaseRegistrationRemoteDataSource
import com.planterior.helper.feature.registration.FirebaseRegistrationRepository
import com.planterior.helper.feature.share.FirebaseMiniHomeShareRepository
import com.planterior.helper.feature.share.MiniHomeShareImageStore
import com.planterior.helper.feature.shop.FirebaseCatalogMediaLoader
import com.planterior.helper.feature.shop.FirebaseInventoryRemoteDataSource
import com.planterior.helper.feature.shop.FirebaseInventoryRepository
import com.planterior.helper.feature.watering.FirebaseWateringNotificationSettingsRepository
import com.planterior.helper.feature.watering.FirebaseWateringRemoteDataSource
import com.planterior.helper.feature.watering.OutboxWateringRepository
import com.planterior.helper.feature.weather.FirebaseWeatherRepository
import com.planterior.helper.weather.SharedPreferencesWeatherPermissionCapabilityStore
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Process-owned Room and repository graph. It holds only the application context. */
internal class AuthRepositoryRuntime
private constructor(
    val auth: FirebaseAuth,
    val firestore: FirebaseFirestore,
    val functions: FirebaseFunctions,
    val storage: FirebaseStorage,
    val database: PlanteriorDatabase,
    val syncRepository: OfflineFirstSyncRepository,
    val registrationRepository: FirebaseRegistrationRepository,
    val collectionRepository: FirebaseCollectionRepository,
    val miniHomeRepository: FirebaseMiniHomeRepository,
    val miniHomeShareRepository: FirebaseMiniHomeShareRepository,
    val inventoryRepository: FirebaseInventoryRepository,
    val wateringRepository: OutboxWateringRepository,
    val wateringNotificationSettingsRepository: FirebaseWateringNotificationSettingsRepository,
    val weatherRepository: FirebaseWeatherRepository,
    val weatherPermissionCapabilities: SharedPreferencesWeatherPermissionCapabilityStore,
    val collectionThumbnailLoader: FirebasePlantThumbnailLoader,
    val catalogMediaLoader: FirebaseCatalogMediaLoader,
    val analyticsRuntime: AnalyticsRuntime,
    val transactionOwnerDiagnostics: RoomTransactionOwnerDiagnostics,
    private val inventoryAuthListener: FirebaseAuth.AuthStateListener,
    private val inventoryLoadScope: CoroutineScope,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val isDatabaseOpen: Boolean
        get() = database.isOpen

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            analyticsRuntime.close()
            auth.removeAuthStateListener(inventoryAuthListener)
            inventoryRepository.close()
            inventoryLoadScope.cancel()
            catalogMediaLoader.close()
            database.close()
        }
    }

    companion object {
        private val emulatorsConnected = AtomicBoolean(false)

        fun create(context: Context): AuthRepositoryRuntime? {
            val applicationContext = context.applicationContext
            val app = FirebaseRuntime.initialize(applicationContext) ?: return null
            val auth = FirebaseAuth.getInstance(app)
            val firestore = FirebaseFirestore.getInstance(app)
            val functions = FirebaseFunctions.getInstance(app)
            val storage = FirebaseStorage.getInstance(app)
            if (BuildConfig.DEBUG && emulatorsConnected.compareAndSet(false, true)) {
                auth.useEmulator("10.0.2.2", 9099)
                firestore.useEmulator("10.0.2.2", 8180)
                functions.useEmulator("10.0.2.2", 5001)
                storage.useEmulator("10.0.2.2", 9199)
            }
            val database =
                Room.databaseBuilder(
                        applicationContext,
                        PlanteriorDatabase::class.java,
                        "planterior.db",
                    )
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
                        MIGRATION_20_21,
                    )
                    .build()
            var inventoryLoadScope: CoroutineScope? = null
            var inventoryRepository: FirebaseInventoryRepository? = null
            var inventoryAuthListener: FirebaseAuth.AuthStateListener? = null
            return try {
                val transactionOwnerDiagnostics = roomTransactionOwnerDiagnostics()
                val mutationGateway = FirebaseRemoteMutationGateway(functions)
                val syncRepository = OfflineFirstSyncRepository(database, mutationGateway)
                val registrationRepository =
                    FirebaseRegistrationRepository(
                        database,
                        FirebaseRegistrationRemoteDataSource(
                            auth,
                            firestore,
                            storage,
                            FirebasePrivateMediaGateway(functions),
                        ) { photo ->
                            val maximum = 20 * 1024 * 1024
                            applicationContext.contentResolver
                                .openInputStream(photo.privateUri.toUri())
                                .use { input ->
                                    val bytes = requireNotNull(input).readBounded(maximum)
                                    require(bytes.isNotEmpty() && bytes.size <= maximum)
                                    bytes
                                }
                        },
                        mutationGateway,
                    )
                val collectionRepository =
                    FirebaseCollectionRepository(
                        database,
                        FirebaseCollectionRemoteDataSource(auth, firestore),
                        mutationGateway,
                    )
                val wateringRepository =
                    OutboxWateringRepository(
                        database,
                        CollectionWateringPreparationSource(collectionRepository),
                        FirebaseWateringRemoteDataSource(auth, firestore),
                        mutationGateway,
                    )
                val weatherRepository = FirebaseWeatherRepository(auth, firestore, functions)
                val miniHomeRepository =
                    FirebaseMiniHomeRepository(
                        database,
                        FirebaseMiniHomeRemoteDataSource(auth, functions),
                    )
                inventoryLoadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                inventoryRepository =
                    FirebaseInventoryRepository(
                        database,
                        FirebaseInventoryRemoteDataSource(auth, functions),
                        loadScope = requireNotNull(inventoryLoadScope),
                    )
                inventoryAuthListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
                    requireNotNull(inventoryRepository)
                        .onAccountChanged(
                            firebaseAuth.currentUser?.uid?.let {
                                com.planterior.helper.core.model.AccountId(it)
                            }
                        )
                }
                auth.addAuthStateListener(requireNotNull(inventoryAuthListener))
                AuthRepositoryRuntime(
                    auth,
                    firestore,
                    functions,
                    storage,
                    database,
                    syncRepository,
                    registrationRepository,
                    collectionRepository,
                    miniHomeRepository,
                    FirebaseMiniHomeShareRepository(
                        auth,
                        functions,
                        miniHomeRepository,
                        MiniHomeShareImageStore(applicationContext),
                    ),
                    requireNotNull(inventoryRepository),
                    wateringRepository,
                    FirebaseWateringNotificationSettingsRepository(auth, firestore, functions),
                    weatherRepository,
                    SharedPreferencesWeatherPermissionCapabilityStore(applicationContext),
                    FirebasePlantThumbnailLoader(storage),
                    FirebaseCatalogMediaLoader(storage),
                    AnalyticsRuntime(
                        applicationContext,
                        database,
                        FirebaseAnalyticsRemoteGateway(functions),
                        transactionOwners = transactionOwnerDiagnostics,
                    ) {
                        auth.currentUser?.uid
                    },
                    transactionOwnerDiagnostics,
                    requireNotNull(inventoryAuthListener),
                    requireNotNull(inventoryLoadScope),
                )
            } catch (error: Throwable) {
                inventoryAuthListener?.let(auth::removeAuthStateListener)
                inventoryRepository?.close()
                inventoryLoadScope?.cancel()
                database.close()
                throw error
            }
        }
    }
}

private fun InputStream.readBounded(maximum: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= maximum) { "Representative photo is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
