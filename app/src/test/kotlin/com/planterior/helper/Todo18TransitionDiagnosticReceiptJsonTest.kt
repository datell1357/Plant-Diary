package com.planterior.helper

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.diagnostic.Todo18CaptureFreshness
import com.planterior.helper.diagnostic.Todo18DiagnosticCaptureSnapshot
import com.planterior.helper.diagnostic.Todo18DiagnosticReceiptFixtures
import com.planterior.helper.diagnostic.Todo18PipelineEvent
import com.planterior.helper.diagnostic.Todo18PipelineEventKind
import com.planterior.helper.diagnostic.Todo18WaitId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Todo18TransitionDiagnosticReceiptJsonTest {
    @Test
    fun `receipt json emits registration persistence identities and explicit nulls`() {
        val populated =
            Todo18PipelineEvent(
                ordinal = 1,
                kind = Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_RETURNED,
                registrationAccountId = AccountId("account"),
                registrationOperationId = OperationId("operation"),
                registrationPlantId = PersonalPlantId("plant"),
                elapsedNanos = 19,
            )
        val absent =
            Todo18PipelineEvent(
                ordinal = 2,
                kind = Todo18PipelineEventKind.REGISTRATION_COMPLETED_RETURNED,
            )
        val receipt =
            Todo18DiagnosticReceiptFixtures.valid(Todo18WaitId.REGISTRATION_COMMIT)
                .copy(pipeline = listOf(populated, absent))
        val snapshot =
            Todo18DiagnosticCaptureSnapshot(
                waitId = Todo18WaitId.REGISTRATION_COMMIT,
                freshness =
                    Todo18CaptureFreshness(
                        initialSequence = 0,
                        initialCurrentsEmpty = true,
                        initialListenerCount = 0,
                        isolatedInstance = true,
                    ),
                pipeline = receipt.pipeline,
                stateDispatches = emptyList(),
                exactEvents = emptyList(),
                failures = emptyList(),
                closed = true,
            )

        val parsed = JSONObject(receipt.toCompactJson(snapshot)).getJSONArray("pipeline")
        val populatedJson = parsed.getJSONObject(0)
        val absentJson = parsed.getJSONObject(1)

        assertEquals("account", populatedJson.getString("registrationAccountId"))
        assertEquals("operation", populatedJson.getString("registrationOperationId"))
        assertEquals("plant", populatedJson.getString("registrationPlantId"))
        assertEquals(19, populatedJson.getLong("elapsedNanos"))
        assertTrue(absentJson.isNull("registrationAccountId"))
        assertTrue(absentJson.isNull("registrationOperationId"))
        assertTrue(absentJson.isNull("registrationPlantId"))
        assertTrue(absentJson.isNull("elapsedNanos"))
    }
}
