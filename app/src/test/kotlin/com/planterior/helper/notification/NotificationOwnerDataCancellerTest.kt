package com.planterior.helper.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class NotificationOwnerDataCancellerTest {
    @Test
    fun `production canceller removes already posted former-owner notifications`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(41, Notification())
        assertEquals(1, Shadows.shadowOf(manager).allNotifications.size)

        SystemNotificationOwnerDataCanceller(context).cancelFormerOwnerNotifications()

        assertEquals(0, Shadows.shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `post paused after uid check cannot escape account transition cancellation`() = runTest {
        var currentOwner: String? = "account-a"
        val uidChecked = CompletableDeferred<Unit>()
        val resumePost = CompletableDeferred<Unit>()
        var cancellations = 0
        var posts = 0
        val posting = async {
            NotificationAccountTransitionGate.postIfCurrent(
                ownerUid = "account-a",
                currentOwnerUid = { currentOwner },
                afterInitialOwnerCheck = {
                    uidChecked.complete(Unit)
                    resumePost.await()
                },
                post = {
                    posts += 1
                    true
                },
            )
        }
        uidChecked.await()

        NotificationAccountTransitionGate.transition(
            cancelFormerOwnerNotifications = { cancellations += 1 }
        ) {
            currentOwner = null
        }
        resumePost.complete(Unit)

        assertEquals(false, posting.await())
        assertEquals(1, cancellations)
        assertEquals(0, posts)
    }

    @Test
    fun `transient late credential identity is never visible at notification post boundary`() =
        runTest {
            var currentOwner: String? = null
            val identityMutated = CompletableDeferred<Unit>()
            val postCheckedIdentity = CompletableDeferred<Unit>()
            val finishRollback = CompletableDeferred<Unit>()
            var posts = 0
            val credentialExchange = async {
                NotificationAccountTransitionGate.transition(cancelFormerOwnerNotifications = {}) {
                    currentOwner = "account-a"
                    identityMutated.complete(Unit)
                    finishRollback.await()
                    currentOwner = null
                }
            }
            identityMutated.await()
            val posting = async {
                NotificationAccountTransitionGate.postIfCurrent(
                    ownerUid = "account-a",
                    currentOwnerUid = { currentOwner },
                    afterInitialOwnerCheck = { postCheckedIdentity.complete(Unit) },
                    post = {
                        posts += 1
                        true
                    },
                )
            }
            postCheckedIdentity.await()

            finishRollback.complete(Unit)
            credentialExchange.await()

            assertEquals(false, posting.await())
            assertEquals(0, posts)
            assertEquals(null, currentOwner)
        }

    @Test
    fun `account switch reopens posting only for the new owner`() = runTest {
        var currentOwner: String? = "account-a"
        val postedOwners = mutableListOf<String>()

        NotificationAccountTransitionGate.transition(cancelFormerOwnerNotifications = {}) {
            currentOwner = "account-b"
        }
        val formerPosted =
            NotificationAccountTransitionGate.postIfCurrent(
                "account-a",
                { currentOwner },
            ) {
                postedOwners += "account-a"
                true
            }
        val currentPosted =
            NotificationAccountTransitionGate.postIfCurrent(
                "account-b",
                { currentOwner },
            ) {
                postedOwners += "account-b"
                true
            }

        assertEquals(false, formerPosted)
        assertEquals(true, currentPosted)
        assertEquals(listOf("account-b"), postedOwners)
    }
}
