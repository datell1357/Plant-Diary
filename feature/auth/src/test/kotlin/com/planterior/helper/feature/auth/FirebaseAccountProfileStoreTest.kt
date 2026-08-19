package com.planterior.helper.feature.auth

import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class FirebaseAccountProfileStoreTest {
    @Test
    fun `failed atomic profile update is retried after store recreation without a client-side timezone commit`() =
        runTest {
            val callable = RecordingAccountProfileCallable(failFirst = true)
            val account = AuthAccount("account-a", null, "A", setOf(AuthProvider.GOOGLE))
            val first =
                FirestoreAccountProfileStore(callable) {
                    ZoneId.of("America/Los_Angeles")
                }

            try {
                first.upsert(account)
                fail("Expected the atomic callable failure")
            } catch (_: IllegalStateException) {
                // The server transaction did not commit; recreation retries the same desired zone.
            }

            val restarted =
                FirestoreAccountProfileStore(callable) {
                    ZoneId.of("America/Los_Angeles")
                }
            restarted.upsert(account)

            assertEquals(2, callable.calls.size)
            assertEquals("account-a", callable.calls.last()["expectedOwnerUid"])
            assertEquals("America/Los_Angeles", callable.calls.last()["zoneId"])
            assertEquals(listOf("GOOGLE"), callable.calls.last()["providers"])
        }

    private class RecordingAccountProfileCallable(private var failFirst: Boolean) :
        AccountProfileCallable {
        val calls = mutableListOf<Map<String, Any?>>()

        override suspend fun call(data: Map<String, Any?>) {
            calls += data
            if (failFirst) {
                failFirst = false
                error("atomic callable unavailable")
            }
        }
    }
}
