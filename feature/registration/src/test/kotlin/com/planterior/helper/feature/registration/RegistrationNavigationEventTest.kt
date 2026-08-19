package com.planterior.helper.feature.registration

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import java.lang.ref.WeakReference
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RegistrationNavigationEventTest {
    private val existingId = PersonalPlantId("existing-plant")
    private val ownerA = AccountId("account-a")
    private val ownerB = AccountId("account-b")
    private val clock = Clock.fixed(Instant.parse("2026-08-18T03:00:00Z"), ZoneId.of("UTC"))

    @Test
    fun `stale collector cannot navigate and one identity dispatches exactly once`() = runTest {
        val identities = ArrayDeque(listOf("event-1", "event-2"))
        val controller = controller(identityFactory = { identities.removeFirst() })
        requestOpenExisting(controller)
        val event = requireNotNull(controller.navigationEvent.value)
        val stale = controller.attachNavigationCollector()
        val current = controller.attachNavigationCollector()
        val navigated = mutableListOf<RegistrationNavigationEvent>()

        assertFalse(
            controller.dispatchNavigationEvent(
                stale,
                event.identity,
                ownerA.authenticated(),
                navigated::add,
            )
        )
        assertTrue(
            controller.dispatchNavigationEvent(
                current,
                event.identity,
                ownerA.authenticated(),
                navigated::add,
            )
        )
        assertFalse(
            controller.dispatchNavigationEvent(
                current,
                event.identity,
                ownerA.authenticated(),
                navigated::add,
            )
        )
        assertEquals(listOf(event), navigated)
        assertNull(controller.navigationEvent.value)

        controller.openExisting(existingId)
        assertEquals("event-2", controller.navigationEvent.value?.identity)
    }

    @Test
    fun `event emitted while old collector detaches waits for current collector`() = runTest {
        val controller = controller(identityFactory = { "during-recreation" })
        controller.start()
        val old = controller.attachNavigationCollector()
        controller.detachNavigationCollector(old)
        requestOpenExisting(controller)
        val event = requireNotNull(controller.navigationEvent.value)
        val lateOldCalls = mutableListOf<RegistrationNavigationEvent>()

        assertFalse(
            controller.dispatchNavigationEvent(
                old,
                event.identity,
                ownerA.authenticated(),
                lateOldCalls::add,
            )
        )
        val current = controller.attachNavigationCollector()
        val currentCalls = mutableListOf<RegistrationNavigationEvent>()
        assertTrue(
            controller.dispatchNavigationEvent(
                current,
                event.identity,
                ownerA.authenticated(),
                currentCalls::add,
            )
        )
        assertTrue(lateOldCalls.isEmpty())
        assertEquals(listOf(event), currentCalls)
    }

    @Test
    fun `pending event survives process restoration once and account switch cancels it`() =
        runTest {
            val handle = SavedStateHandle()
            val original = controller({ "persisted-event" }, handle)
            requestOpenExisting(original)
            val expected = requireNotNull(original.navigationEvent.value)

            val restored = controller({ error("restoration must retain event identity") }, handle)
            assertEquals(expected, restored.navigationEvent.value)
            val collector = restored.attachNavigationCollector()
            val calls = mutableListOf<RegistrationNavigationEvent>()
            assertTrue(
                restored.dispatchNavigationEvent(
                    collector,
                    expected.identity,
                    ownerA.authenticated(),
                    calls::add,
                )
            )
            assertEquals(listOf(expected), calls)
            assertNull(restored.navigationEvent.value)

            val afterConsumption = controller({ "unused" }, handle)
            assertNull(afterConsumption.navigationEvent.value)
            requestOpenExisting(afterConsumption)
            val switchedEvent = requireNotNull(afterConsumption.navigationEvent.value)
            afterConsumption.dispatchNavigationEvent(
                afterConsumption.attachNavigationCollector(),
                switchedEvent.identity,
                ownerB.authenticated(),
            ) {
                error("account switch must not navigate")
            }
            assertNull(afterConsumption.navigationEvent.value)
        }

    @Test
    fun `restoring and unknown preserve process-restored event until same owner dispatches once`() =
        runTest {
            val handle = SavedStateHandle()
            val original = controller({ "typed-restored-event" }, handle)
            requestOpenExisting(original)
            val expected = requireNotNull(original.navigationEvent.value)
            val restored = controller({ error("identity must restore") }, handle)
            val collector = restored.attachNavigationCollector()
            val calls = mutableListOf<RegistrationNavigationEvent>()

            assertFalse(
                restored.dispatchNavigationEvent(
                    collector,
                    expected.identity,
                    RegistrationAuthOwnership.Restoring,
                    calls::add,
                )
            )
            assertEquals(expected, restored.navigationEvent.value)
            assertFalse(
                restored.dispatchNavigationEvent(
                    collector,
                    expected.identity,
                    RegistrationAuthOwnership.Unknown,
                    calls::add,
                )
            )
            assertEquals(expected, restored.navigationEvent.value)
            assertTrue(
                restored.dispatchNavigationEvent(
                    collector,
                    expected.identity,
                    RegistrationAuthOwnership.Authenticated(ownerA),
                    calls::add,
                )
            )
            assertEquals(listOf(expected), calls)
            assertNull(restored.navigationEvent.value)
            assertFalse(
                restored.dispatchNavigationEvent(
                    collector,
                    expected.identity,
                    RegistrationAuthOwnership.Authenticated(ownerA),
                    calls::add,
                )
            )
        }

    @Test
    fun `process-restored event is cancelled only by authoritative signed out or other owner`() =
        runTest {
            suspend fun restored(
                identity: String
            ): Pair<RegistrationController, RegistrationNavigationEvent> {
                val handle = SavedStateHandle()
                val original = controller({ identity }, handle)
                requestOpenExisting(original)
                val event = requireNotNull(original.navigationEvent.value)
                return controller({ error("identity must restore") }, handle) to event
            }

            val (signedOut, signedOutEvent) = restored("signed-out-event")
            assertFalse(
                signedOut.dispatchNavigationEvent(
                    signedOut.attachNavigationCollector(),
                    signedOutEvent.identity,
                    RegistrationAuthOwnership.SignedOut,
                ) {
                    error("signed out must not navigate")
                }
            )
            assertNull(signedOut.navigationEvent.value)

            val (differentOwner, differentOwnerEvent) = restored("different-owner-event")
            assertFalse(
                differentOwner.dispatchNavigationEvent(
                    differentOwner.attachNavigationCollector(),
                    differentOwnerEvent.identity,
                    RegistrationAuthOwnership.Authenticated(ownerB),
                ) {
                    error("another owner must not navigate")
                }
            )
            assertNull(differentOwner.navigationEvent.value)
        }

    @Test
    fun `delayed auth login return survives activity collector recreation and stale restoring`() =
        runTest {
            val controller = controller(identityFactory = { "delayed-auth-event" })
            requestOpenExisting(controller)
            val event = requireNotNull(controller.navigationEvent.value)
            val old = controller.attachNavigationCollector()
            val calls = mutableListOf<RegistrationNavigationEvent>()

            assertFalse(
                controller.dispatchNavigationEvent(
                    old,
                    event.identity,
                    RegistrationAuthOwnership.Restoring,
                    calls::add,
                )
            )
            controller.detachNavigationCollector(old)
            val current = controller.attachNavigationCollector()
            assertFalse(
                controller.dispatchNavigationEvent(
                    old,
                    event.identity,
                    RegistrationAuthOwnership.Authenticated(ownerA),
                    calls::add,
                )
            )
            assertFalse(
                controller.dispatchNavigationEvent(
                    current,
                    event.identity,
                    RegistrationAuthOwnership.Unknown,
                    calls::add,
                )
            )
            assertEquals(event, controller.navigationEvent.value)
            assertTrue(
                controller.dispatchNavigationEvent(
                    current,
                    event.identity,
                    RegistrationAuthOwnership.Authenticated(ownerA),
                    calls::add,
                )
            )
            assertFalse(
                controller.dispatchNavigationEvent(
                    current,
                    event.identity,
                    RegistrationAuthOwnership.Restoring,
                    calls::add,
                )
            )
            assertEquals(listOf(event), calls)
        }

    @Test
    fun `ViewModel clearing cancels the pending event and collector`() = runTest {
        val controller = controller(identityFactory = { "cleared-event" })
        requestOpenExisting(controller)
        val event = requireNotNull(controller.navigationEvent.value)
        val collector = controller.attachNavigationCollector()
        val store = ViewModelStore()
        val provider =
            ViewModelProvider.create(
                store,
                viewModelFactory { initializer { RegistrationViewModel(controller) } },
            )
        assertSame(controller, provider[RegistrationViewModel::class.java].controller)

        store.clear()

        assertNull(controller.navigationEvent.value)
        assertFalse(
            controller.dispatchNavigationEvent(
                collector,
                event.identity,
                ownerA.authenticated(),
            ) {
                error("cleared collector must not navigate")
            }
        )
    }

    @Test
    fun `controller never retains the activity callback graph`() = runTest {
        val controller = controller(identityFactory = { "leak-check" })
        requestOpenExisting(controller)
        val event = requireNotNull(controller.navigationEvent.value)
        val collector = controller.attachNavigationCollector()
        var activityGraph: Any? = Any()
        val weakGraph = WeakReference(activityGraph)
        var callback: ((RegistrationNavigationEvent) -> Unit)? = { delivered ->
            assertEquals(event, delivered)
            assertSame(activityGraph, weakGraph.get())
        }

        assertTrue(
            controller.dispatchNavigationEvent(
                collector,
                event.identity,
                ownerA.authenticated(),
                requireNotNull(callback),
            )
        )
        callback = null
        activityGraph = null

        assertFalse(objectGraphContains(controller, weakGraph.get()))
    }

    private suspend fun requestOpenExisting(controller: RegistrationController) {
        controller.start()
        if (controller.state.value !is RegistrationUiState.DuplicateFound) {
            controller.selectContent(
                RegistrationContent(PlantContentId("species-monstera"), "몬스테라")
            )
            controller.submit()
        }
        controller.openExisting(existingId)
    }

    private fun controller(
        identityFactory: () -> String,
        handle: SavedStateHandle? = null,
    ) =
        RegistrationController(
            seed = RegistrationSeed.Manual,
            repository = DuplicateRepository,
            clock = clock,
            navigationIdentityFactory = identityFactory,
            savedStateHandle = handle,
        )

    private fun AccountId.authenticated() = RegistrationAuthOwnership.Authenticated(this)

    private fun objectGraphContains(root: Any, target: Any?): Boolean {
        if (target == null) return false
        return root.javaClass.declaredFields.any { field ->
            field.isAccessible = true
            val value = field.get(root)
            value === target ||
                value?.javaClass?.declaredFields.orEmpty().any { nested ->
                    runCatching {
                            nested.isAccessible = true
                            nested.get(value) === target
                        }
                        .getOrDefault(false)
                }
        }
    }

    private object DuplicateRepository : RegistrationRepository {
        override suspend fun session() =
            RegistrationSession(AccountId("account-a"), ZoneId.of("Asia/Seoul"))

        override suspend fun searchPublicContents(query: String) = emptyList<RegistrationContent>()

        override suspend fun findDuplicates(
            accountId: AccountId,
            contentId: PlantContentId,
            excluding: PersonalPlantId,
        ) = listOf(ExistingPersonalPlant(PersonalPlantId("existing-plant"), "기존 몬스테라"))

        override suspend fun register(
            submission: PendingRegistration,
            checkpoint: RegistrationCheckpoint,
        ): RegistrationAttempt = error("not used")
    }
}
