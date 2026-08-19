package com.planterior.helper.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class NotificationOpenConfirmationTest {
    private lateinit var context: Context
    private lateinit var store: NotificationOpenConfirmationStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context
            .getSharedPreferences(
                NotificationOpenConfirmationStore.PREFERENCES,
                Context.MODE_PRIVATE,
            )
            .edit()
            .clear()
            .commit()
        store = NotificationOpenConfirmationStore(context)
    }

    @Test
    fun `authenticated detail tap confirms once without persisting endpoint data`() = runTest {
        val deliveryId = "123e4567-e89b-12d3-a456-426614174000"
        store.recordTap("planterior://collection/plant/plant-a?deliveryId=$deliveryId")
        val calls = mutableListOf<Pair<String, String>>()

        store.confirmPending("user-a") { ownerUid, id ->
            calls += ownerUid to id
            NotificationOpenConfirmationResult.CONFIRMED
        }
        store.confirmPending("user-a") { ownerUid, id ->
            calls += ownerUid to id
            NotificationOpenConfirmationResult.CONFIRMED
        }

        assertEquals(listOf("user-a" to deliveryId), calls)
        assertEquals(emptySet<String>(), store.pending())
    }

    @Test
    fun `logged-out tap persists confirmation until authenticated retry succeeds`() = runTest {
        val deliveryId = "123e4567-e89b-12d3-a456-426614174000"
        store.recordTap("planterior://collection/plant/deleted?deliveryId=$deliveryId")

        assertEquals(setOf(deliveryId), store.pending())
        store.confirmPending("user-a") { _, _ -> NotificationOpenConfirmationResult.CONFIRMED }
        assertEquals(emptySet<String>(), store.pending())
    }

    @Test
    fun `permanent and transient failures are isolated from valid queue entries across restart`() =
        runTest {
            val permanent = "123e4567-e89b-12d3-a456-426614174001"
            val transient = "123e4567-e89b-12d3-a456-426614174002"
            val valid = "123e4567-e89b-12d3-a456-426614174003"
            listOf(permanent, transient, valid).forEach { deliveryId ->
                store.recordTap("planterior://collection/plant/plant-a?deliveryId=$deliveryId")
            }
            val calls = mutableListOf<String>()

            store.confirmPending("user-a") { _, deliveryId ->
                calls += deliveryId
                when (deliveryId) {
                    permanent -> NotificationOpenConfirmationResult.PERMANENT_FAILURE
                    transient -> NotificationOpenConfirmationResult.RETRYABLE_FAILURE
                    else -> NotificationOpenConfirmationResult.CONFIRMED
                }
            }

            assertEquals(setOf(permanent, transient, valid), calls.toSet())
            assertEquals(setOf(transient), NotificationOpenConfirmationStore(context).pending())
        }

    @Test
    fun `wrong-account absence terminalizes only after horizon and retries for later account`() =
        runTest {
            val deliveryId = "123e4567-e89b-12d3-a456-426614174004"
            var now = 1_000L
            val timedStore = NotificationOpenConfirmationStore(context) { now }
            timedStore.recordTap("planterior://collection/plant/plant-a?deliveryId=$deliveryId")
            val calls = mutableListOf<String>()

            timedStore.confirmPending("user-b") { ownerUid, _ ->
                calls += ownerUid
                NotificationOpenConfirmationResult.NOT_FOUND_FOR_ACCOUNT
            }
            now += NotificationOpenConfirmationStore.NOT_FOUND_RETRY_HORIZON_MILLIS
            NotificationOpenConfirmationStore(context) { now }
                .confirmPending("user-b") { ownerUid, _ ->
                    calls += ownerUid
                    NotificationOpenConfirmationResult.NOT_FOUND_FOR_ACCOUNT
                }
            NotificationOpenConfirmationStore(context) { now }
                .confirmPending("user-b") { ownerUid, _ ->
                    calls += ownerUid
                    NotificationOpenConfirmationResult.CONFIRMED
                }
            NotificationOpenConfirmationStore(context) { now }
                .confirmPending("user-a") { ownerUid, _ ->
                    calls += ownerUid
                    NotificationOpenConfirmationResult.CONFIRMED
                }

            assertEquals(listOf("user-b", "user-b", "user-a"), calls)
            assertEquals(emptySet<String>(), timedStore.pending())
        }

    @Test
    fun `tap before history retries and confirms when history appears`() = runTest {
        val deliveryId = "123e4567-e89b-12d3-a456-426614174006"
        var now = 2_000L
        val timedStore = NotificationOpenConfirmationStore(context) { now }
        timedStore.recordTap("planterior://collection/plant/plant-a?deliveryId=$deliveryId")
        val calls = mutableListOf<String>()

        timedStore.confirmPending("user-a") { _, id ->
            calls += id
            NotificationOpenConfirmationResult.NOT_FOUND_FOR_ACCOUNT
        }
        now += 1
        timedStore.confirmPending("user-a") { _, id ->
            calls += id
            NotificationOpenConfirmationResult.CONFIRMED
        }

        assertEquals(listOf(deliveryId, deliveryId), calls)
        assertEquals(emptySet<String>(), timedStore.pending())
    }

    @Test
    fun `early absence survives restart until later history appears`() = runTest {
        val deliveryId = "123e4567-e89b-12d3-a456-426614174007"
        var now = 3_000L
        val timedStore = NotificationOpenConfirmationStore(context) { now }
        timedStore.recordTap("planterior://collection/plant/plant-a?deliveryId=$deliveryId")
        timedStore.confirmPending("user-a") { _, _ ->
            NotificationOpenConfirmationResult.NOT_FOUND_FOR_ACCOUNT
        }

        now += NotificationOpenConfirmationStore.NOT_FOUND_RETRY_HORIZON_MILLIS - 1
        val restarted = NotificationOpenConfirmationStore(context) { now }
        restarted.confirmPending("user-a") { _, _ ->
            NotificationOpenConfirmationResult.CONFIRMED
        }

        assertEquals(emptySet<String>(), restarted.pending())
    }

    @Test
    fun `expired genuine absence does not block later queue entries`() = runTest {
        val absent = "123e4567-e89b-12d3-a456-426614174008"
        val valid = "123e4567-e89b-12d3-a456-426614174009"
        var now = 4_000L
        val timedStore = NotificationOpenConfirmationStore(context) { now }
        listOf(absent, valid).forEach { deliveryId ->
            timedStore.recordTap("planterior://collection/plant/plant-a?deliveryId=$deliveryId")
        }
        val calls = mutableListOf<String>()

        timedStore.confirmPending("user-a") { _, deliveryId ->
            calls += deliveryId
            if (deliveryId == absent) {
                NotificationOpenConfirmationResult.NOT_FOUND_FOR_ACCOUNT
            } else {
                NotificationOpenConfirmationResult.CONFIRMED
            }
        }
        now += NotificationOpenConfirmationStore.NOT_FOUND_RETRY_HORIZON_MILLIS
        NotificationOpenConfirmationStore(context) { now }
            .confirmPending("user-a") { _, deliveryId ->
                calls += deliveryId
                NotificationOpenConfirmationResult.NOT_FOUND_FOR_ACCOUNT
            }
        NotificationOpenConfirmationStore(context) { now }
            .confirmPending("user-a") { _, deliveryId ->
                calls += deliveryId
                NotificationOpenConfirmationResult.CONFIRMED
            }

        assertEquals(listOf(absent, valid, absent), calls)
        assertEquals(setOf(absent), timedStore.pending())
    }

    @Test
    fun `malformed persisted ids are discarded without blocking valid confirmations`() = runTest {
        val valid = "123e4567-e89b-12d3-a456-426614174005"
        context
            .getSharedPreferences(
                NotificationOpenConfirmationStore.PREFERENCES,
                Context.MODE_PRIVATE,
            )
            .edit()
            .putStringSet("pending-delivery-ids", setOf("malformed", valid))
            .commit()
        val calls = mutableListOf<String>()

        store.confirmPending("user-a") { _, deliveryId ->
            calls += deliveryId
            NotificationOpenConfirmationResult.CONFIRMED
        }

        assertEquals(listOf(valid), calls)
        assertEquals(emptySet<String>(), store.pending())
    }
}
