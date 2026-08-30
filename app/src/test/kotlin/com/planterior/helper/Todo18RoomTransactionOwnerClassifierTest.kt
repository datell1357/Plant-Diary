package com.planterior.helper

import com.planterior.helper.core.database.RoomTransactionOwner
import com.planterior.helper.diagnostic.Todo18RoomTransactionOwnerClassification
import com.planterior.helper.diagnostic.Todo18RoomTransactionOwnerClassificationEvent
import com.planterior.helper.diagnostic.Todo18RoomTransactionOwnerClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

class Todo18RoomTransactionOwnerClassifierTest {
    @Test
    fun `exact owner begins before second read and returns while it is held`() {
        val result =
            Todo18RoomTransactionOwnerClassifier.classify(
                listOf(
                    returnedRead(1),
                    began(RoomTransactionOwner.ACCOUNT_SYNC_WRITE, 17),
                    enteredRead(2),
                    terminal(RoomTransactionOwner.ACCOUNT_SYNC_WRITE, 17),
                    returnedRead(2),
                )
            )

        assertEquals(
            Todo18RoomTransactionOwnerClassification.Exact(
                RoomTransactionOwner.ACCOUNT_SYNC_WRITE,
                17,
            ),
            result,
        )
    }

    @Test
    fun `zero multiple queued only and untagged remain unknown`() {
        val cases =
            listOf(
                listOf(returnedRead(1), enteredRead(2), returnedRead(2)),
                listOf(
                    returnedRead(1),
                    began(RoomTransactionOwner.ACCOUNT_SYNC_WRITE, 1),
                    began(RoomTransactionOwner.ANALYTICS_ENQUEUE, 2),
                    enteredRead(2),
                    terminal(RoomTransactionOwner.ACCOUNT_SYNC_WRITE, 1),
                    terminal(RoomTransactionOwner.ANALYTICS_ENQUEUE, 2),
                    returnedRead(2),
                ),
                listOf(
                    returnedRead(1),
                    enteredRead(2),
                    began(RoomTransactionOwner.ANALYTICS_WORKER_DELIVERY, 3),
                    returnedRead(2),
                ),
                listOf(returnedRead(1), enteredRead(2)),
            )

        cases.forEach { events ->
            assertEquals(
                Todo18RoomTransactionOwnerClassification.Unknown,
                Todo18RoomTransactionOwnerClassifier.classify(events),
            )
        }
    }

    @Test
    fun `late begin malformed order and duplicate pairs remain unknown`() {
        val owner = RoomTransactionOwner.ANALYTICS_ENQUEUE
        val cases =
            listOf(
                listOf(
                    returnedRead(1),
                    enteredRead(2),
                    began(owner, 10),
                    terminal(owner, 10),
                    returnedRead(2),
                ),
                listOf(
                    returnedRead(1),
                    terminal(owner, 11),
                    began(owner, 11),
                    enteredRead(2),
                    terminal(owner, 11),
                    returnedRead(2),
                ),
                listOf(
                    returnedRead(1),
                    began(owner, 12),
                    began(owner, 12),
                    enteredRead(2),
                    terminal(owner, 12),
                    returnedRead(2),
                ),
                listOf(
                    returnedRead(1),
                    began(owner, 13),
                    enteredRead(2),
                    terminal(owner, 13),
                    terminal(owner, 13),
                    returnedRead(2),
                ),
            )

        cases.forEach { events ->
            assertEquals(
                Todo18RoomTransactionOwnerClassification.Unknown,
                Todo18RoomTransactionOwnerClassifier.classify(events),
            )
        }
    }

    private fun enteredRead(readId: Long) =
        Todo18RoomTransactionOwnerClassificationEvent.PublicationReadEntered(readId)

    private fun returnedRead(readId: Long) =
        Todo18RoomTransactionOwnerClassificationEvent.PublicationReadReturned(readId)

    private fun began(owner: RoomTransactionOwner, token: Long) =
        Todo18RoomTransactionOwnerClassificationEvent.Began(owner, token)

    private fun terminal(owner: RoomTransactionOwner, token: Long) =
        Todo18RoomTransactionOwnerClassificationEvent.Terminal(owner, token)
}
