package com.planterior.helper.core.data

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class FirebaseRemoteMutationGatewayTest {
    @Test
    fun `callable request and all typed receipts preserve stable mutation identity`() = runTest {
        val callable =
            RecordingOwnerMutationCallable(mapOf("kind" to "duplicate", "revision" to 1L))
        val gateway = FirebaseRemoteMutationGateway(callable)
        val command =
            RemoteMutationCommand(
                AccountId("account-a"),
                OperationId("operation-stable"),
                "personalPlants",
                "plant-stable",
                "CREATE",
                Revision(0),
                "{\"displayName\":\"몬스테라\",\"registrationMethod\":\"MANUAL\"}",
            )

        assertEquals(RemoteMutationResult.Duplicate(1), gateway.apply(command))
        assertEquals("personalPlants", callable.input.single()["collection"])
        assertEquals("account-a", callable.input.single()["expectedOwnerUid"])
        assertEquals("plant-stable", callable.input.single()["documentId"])
        assertEquals("CREATE", callable.input.single()["mutationType"])
        assertEquals("operation-stable", callable.input.single()["idempotencyKey"])
        assertEquals(0L, callable.input.single()["expectedRevision"])
    }

    @Test
    fun `watering outbox replay routes to the atomic completion callable`() = runTest {
        val owner = RecordingOwnerMutationCallable(mapOf("kind" to "applied", "revision" to 5L))
        val watering =
            RecordingOwnerMutationCallable(mapOf("kind" to "duplicate", "revision" to 5L))
        val gateway = FirebaseRemoteMutationGateway(owner, watering)

        val result =
            gateway.apply(
                RemoteMutationCommand(
                    AccountId("account-a"),
                    OperationId("watering-operation-stable"),
                    "wateringCompletions",
                    "plant-a",
                    "UPDATE",
                    Revision(4),
                    "{\"wateredDate\":\"2026-08-12\"}",
                )
            )

        assertEquals(RemoteMutationResult.Duplicate(5), result)
        assertEquals(0, owner.input.size)
        assertEquals("wateringCompletions", watering.input.single()["collection"])
    }

    @Test
    fun `malformed callable response fails closed`() = runTest {
        val gateway =
            FirebaseRemoteMutationGateway(
                RecordingOwnerMutationCallable(mapOf("kind" to "applied"))
            )
        assertEquals(
            RemoteMutationResult.Failed("MALFORMED_RESPONSE"),
            gateway.apply(
                RemoteMutationCommand(
                    AccountId("account-a"),
                    OperationId("operation-stable"),
                    "personalPlants",
                    "plant-stable",
                    "CREATE",
                    Revision(0),
                    "{}",
                )
            ),
        )
    }

    @Test
    fun `caller cancellation propagates instead of becoming an unavailable receipt`() = runTest {
        val cancellation = CancellationException("caller left")
        val gateway = FirebaseRemoteMutationGateway(OwnerMutationCallable { throw cancellation })

        try {
            gateway.apply(
                RemoteMutationCommand(
                    AccountId("account-a"),
                    OperationId("operation-stable"),
                    "personalPlants",
                    "plant-stable",
                    "CREATE",
                    Revision(0),
                    "{}",
                )
            )
            fail("CancellationException expected")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
    }

    private class RecordingOwnerMutationCallable(private val result: Map<String, Any>) :
        OwnerMutationCallable {
        val input = mutableListOf<Map<String, Any?>>()

        override suspend fun call(data: Map<String, Any?>): Map<String, Any> {
            input += data
            return result
        }
    }
}
