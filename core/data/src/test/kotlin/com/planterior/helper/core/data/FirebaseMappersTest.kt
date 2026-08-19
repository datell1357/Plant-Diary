package com.planterior.helper.core.data

import com.google.firebase.Timestamp
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FirebaseMappersTest {
    @Test
    fun `schedule Firestore map parses candidate timestamps without stale reminder fields`() {
        val next = Timestamp(1_755_043_200, 0)
        val updated = Timestamp(1_755_039_600, 0)
        val dto =
            mapOf<String, Any?>(
                    "ownerUid" to "account-a",
                    "plantId" to "plant-a",
                    "dueDate" to "2026-08-12",
                    "zoneId" to "Asia/Seoul",
                    "notificationCandidateActive" to true,
                    "nextNotificationAt" to next,
                    "revision" to 2L,
                    "expectedRevision" to 1L,
                    "idempotencyKey" to "operation-0001",
                    "updatedAt" to updated,
                )
                .toWateringScheduleDto()

        assertEquals(true, dto.notificationCandidateActive)
        assertEquals(
            FirestoreTimestampAdapter.toInstant(next),
            FirestoreTimestampAdapter.toInstant(requireNotNull(dto.nextNotificationAt)),
        )
        assertEquals(updated, dto.updatedAt)
    }

    @Test
    fun `delivery and history maps enforce shipped timestamp and status invariants`() {
        val scheduled = Timestamp(1_755_043_200, 0)
        val delivered = Timestamp(1_755_043_205, 0)
        val metadata = Timestamp(1_755_043_206, 0)
        val delivery =
            mapOf<String, Any?>(
                    "ownerUid" to "account-a",
                    "plantId" to "plant-a",
                    "dueDate" to "2026-08-12",
                    "attempt" to 0L,
                    "scheduledFor" to scheduled,
                    "deliveredAt" to delivered,
                    "status" to "SENT",
                    "deduplicationKey" to "account-a:plant-a:2026-08-12:0",
                    "revision" to 1L,
                    "expectedRevision" to 0L,
                    "idempotencyKey" to "delivery-0001",
                    "updatedAt" to metadata,
                )
                .toNotificationDeliveryDto()
        val history =
            mapOf<String, Any?>(
                    "ownerUid" to "account-a",
                    "plantId" to "plant-a",
                    "dueDate" to "2026-08-12",
                    "attempt" to 0L,
                    "status" to "DELIVERED_AMBIGUOUS",
                    "deliveryConfirmedAt" to delivered,
                    "failedAt" to null,
                    "ambiguousAt" to delivered,
                    "destinationOpened" to false,
                    "openedAt" to null,
                    "failureKind" to "TRANSPORT_UNKNOWN",
                    "deduplicationKey" to "account-a:plant-a:2026-08-12:0",
                    "revision" to 1L,
                    "expectedRevision" to 0L,
                    "idempotencyKey" to "delivery-history-0001",
                    "updatedAt" to metadata,
                )
                .toNotificationHistoryDto()

        assertEquals(NotificationDeliveryStatus.SENT, delivery.status)
        assertEquals(delivered, delivery.deliveredAt)
        assertEquals(NotificationHistoryStatus.DELIVERED_AMBIGUOUS, history.status)
        assertEquals("TRANSPORT_UNKNOWN", history.failureKind)

        assertThrows(IllegalArgumentException::class.java) {
            mapOf<String, Any?>(
                    "ownerUid" to "account-a",
                    "plantId" to "plant-a",
                    "dueDate" to "2026-08-12",
                    "attempt" to 0L,
                    "scheduledFor" to scheduled,
                    "deliveredAt" to null,
                    "status" to "FAILED",
                    "deduplicationKey" to "key",
                    "revision" to 1L,
                    "expectedRevision" to 0L,
                    "idempotencyKey" to "delivery-0002",
                    "updatedAt" to metadata,
                )
                .toNotificationDeliveryDto()
        }
    }

    @Test
    fun `owner collection paths include shipped notification preference and history collections`() {
        assertEquals(
            "users/account-a/notificationPlantSettings/plant-a",
            FirestoreContract.userDocument(
                com.planterior.helper.core.model.AccountId("account-a"),
                FirestoreContract.UserCollection.NOTIFICATION_PLANT_SETTINGS,
                "plant-a",
            ),
        )
        assertEquals(
            "notificationHistory",
            FirestoreContract.UserCollection.NOTIFICATION_HISTORY.segment,
        )
    }

    @Test
    fun `public share serializes typed Instant as Firestore Timestamp`() {
        val expiresAt = Instant.parse("2099-01-01T00:00:00Z")
        val dto =
            PublicShareSnapshotDto(
                publicationState = "PUBLIC",
                sourceRevision = 4,
                snapshotPath = "share-images/account-a/share-a/share.png",
                expiresAt = FirestoreTimestampAdapter.fromInstant(expiresAt),
                revokedAt = null,
            )

        assertEquals(expiresAt, FirestoreTimestampAdapter.toInstant(dto.expiresAt))
    }
}
