package com.planterior.helper.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.ROBOLECTRIC_MAX_SDK
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.feature.auth.AccountSessionCache
import com.planterior.helper.feature.auth.AccountSynchronizer
import com.planterior.helper.feature.auth.AuthAccount
import com.planterior.helper.feature.auth.AuthCoordinator
import com.planterior.helper.feature.auth.FirebaseIdentityGateway
import com.planterior.helper.feature.auth.ProviderProof
import com.planterior.helper.feature.collection.CollectionLoad
import com.planterior.helper.feature.collection.CollectionPlant
import com.planterior.helper.feature.collection.CollectionRepository
import com.planterior.helper.feature.collection.CollectionTestTags
import com.planterior.helper.feature.collection.DetailLoad
import com.planterior.helper.feature.collection.EditResult
import com.planterior.helper.feature.collection.PlantEditRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [ROBOLECTRIC_MAX_SDK],
    qualifiers = "w402dp-h874dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
class CollectionNavigationContractTest {
    @get:Rule val composeRule = createComposeRule()

    private lateinit var navController: NavHostController

    @Test
    fun `empty collection routes both identification and direct registration through production nav host`() {
        start(FakeCollectionRepository(CollectionLoad.Fresh(emptyList())))

        composeRule.onNodeWithTag(CollectionTestTags.IDENTIFY).performClick()
        composeRule.waitForIdle()
        assertEquals(PlanteriorRoute.Camera, currentRoute())

        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CollectionTestTags.REGISTER_DIRECT).performClick()
        composeRule.waitForIdle()
        assertEquals(PlanteriorRoute.Registration, currentRoute())
    }

    @Test
    fun `live signed out session guards collection with a return route`() = runTest {
        val coordinator = signedOutCoordinator()
        coordinator.restore()

        start(FakeCollectionRepository(CollectionLoad.Fresh(emptyList())), coordinator)

        assertEquals(PlanteriorRoute.Login("planterior://collection"), currentRoute())
    }

    @Test
    fun `live logout redirects an open collection to login with return route`() = runTest {
        val identity = SessionIdentity(AuthAccount("account-a", null, "민지", emptySet()))
        val coordinator = coordinator(identity)
        coordinator.restore()
        start(FakeCollectionRepository(CollectionLoad.Fresh(emptyList())), coordinator)
        assertEquals(PlanteriorRoute.Collection, currentRoute())

        coordinator.logout()
        composeRule.waitForIdle()

        assertEquals(PlanteriorRoute.Login("planterior://collection"), currentRoute())
    }

    @Test
    fun `collection row opens typed plant detail and back restores collection destination`() {
        start(
            FakeCollectionRepository(
                CollectionLoad.Fresh(
                    listOf(CollectionPlant(PersonalPlantId("plant-a"), "몬스테라", null))
                )
            )
        )

        composeRule.onNodeWithTag("${CollectionTestTags.ITEM}:plant-a").performClick()
        composeRule.waitForIdle()
        assertEquals(PlanteriorRoute.PlantDetail("plant-a"), currentRoute())

        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitForIdle()
        assertEquals(PlanteriorRoute.Collection, currentRoute())
    }

    private fun start(
        repository: CollectionRepository,
        authCoordinator: AuthCoordinator? = null,
    ) {
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                navController = rememberNavController()
                PlanteriorNavHost(
                    navController = navController,
                    startRoute = PlanteriorRoute.Collection,
                    collectionRepository = repository,
                    authCoordinator = authCoordinator,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun currentRoute(): PlanteriorRoute? =
        navController.currentBackStackEntry.toPlanteriorRoute()

    private fun signedOutCoordinator() = coordinator(SessionIdentity(null))

    private fun coordinator(identity: SessionIdentity) =
        AuthCoordinator(
            providers = emptyMap(),
            identity = identity,
            profiles = { _ -> },
            cache =
                object : AccountSessionCache {
                    override suspend fun clearVisible(accountUid: String?) = Unit

                    override fun activate(accountUid: String?) = Unit
                },
            synchronizer =
                AccountSynchronizer { com.planterior.helper.feature.auth.SyncSummary.EMPTY },
        )

    private class SessionIdentity(var account: AuthAccount?) : FirebaseIdentityGateway {
        override fun current(): AuthAccount? = account

        override suspend fun signIn(proof: ProviderProof): AuthAccount = error("unused")

        override suspend fun reauthenticate(proof: ProviderProof): AuthAccount = error("unused")

        override suspend fun link(proof: ProviderProof): AuthAccount = error("unused")

        override suspend fun signOut() {
            account = null
        }
    }

    private class FakeCollectionRepository(private val collection: CollectionLoad) :
        CollectionRepository {
        override suspend fun loadCollection() = collection

        override suspend fun loadDetail(plantId: PersonalPlantId) = DetailLoad.NotFound

        override suspend fun saveEdit(request: PlantEditRequest) = EditResult.NotFound

        override suspend fun reconcileFailedEdit(
            accountId: AccountId,
            plantId: PersonalPlantId,
            operationId: OperationId,
        ) = DetailLoad.NotFound
    }
}
