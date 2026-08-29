package com.planterior.helper.registration

import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.feature.registration.PendingRegistration
import com.planterior.helper.feature.registration.RegistrationAttempt
import com.planterior.helper.feature.registration.RegistrationCheckpoint
import com.planterior.helper.feature.registration.RegistrationRepository
import kotlinx.coroutines.CancellationException

@JvmInline value class Todo18RegistrationRepositoryIdentity(val value: Int)

sealed interface Todo18RegistrationCommitRepositoryEvent {
    val repositoryIdentity: Todo18RegistrationRepositoryIdentity
    val operationId: OperationId
    val plantId: PersonalPlantId

    data class Entry(
        override val repositoryIdentity: Todo18RegistrationRepositoryIdentity,
        override val operationId: OperationId,
        override val plantId: PersonalPlantId,
    ) : Todo18RegistrationCommitRepositoryEvent

    data class Completed(
        override val repositoryIdentity: Todo18RegistrationRepositoryIdentity,
        override val operationId: OperationId,
        override val plantId: PersonalPlantId,
    ) : Todo18RegistrationCommitRepositoryEvent

    data class Failed(
        override val repositoryIdentity: Todo18RegistrationRepositoryIdentity,
        override val operationId: OperationId,
        override val plantId: PersonalPlantId,
    ) : Todo18RegistrationCommitRepositoryEvent

    data class Cancelled(
        override val repositoryIdentity: Todo18RegistrationRepositoryIdentity,
        override val operationId: OperationId,
        override val plantId: PersonalPlantId,
    ) : Todo18RegistrationCommitRepositoryEvent
}

/** Debug-only delegating boundary that preserves the installed repository's exact result. */
internal class Todo18RegistrationCommitDiagnosticRepository(
    private val delegate: RegistrationRepository,
    private val observer: (Todo18RegistrationCommitRepositoryEvent) -> Unit,
) : RegistrationRepository by delegate {
    private val identity = Todo18RegistrationRepositoryIdentity(System.identityHashCode(delegate))

    override suspend fun register(
        submission: PendingRegistration,
        checkpoint: RegistrationCheckpoint,
    ): RegistrationAttempt {
        report {
            Todo18RegistrationCommitRepositoryEvent.Entry(
                identity,
                submission.operationId,
                submission.plantId,
            )
        }
        return try {
            delegate.register(submission, checkpoint).also { result ->
                report {
                    when (result) {
                        is RegistrationAttempt.Completed ->
                            Todo18RegistrationCommitRepositoryEvent.Completed(
                                identity,
                                submission.operationId,
                                submission.plantId,
                            )
                        is RegistrationAttempt.Failed ->
                            Todo18RegistrationCommitRepositoryEvent.Failed(
                                identity,
                                submission.operationId,
                                submission.plantId,
                            )
                    }
                }
            }
        } catch (failure: CancellationException) {
            report {
                Todo18RegistrationCommitRepositoryEvent.Cancelled(
                    identity,
                    submission.operationId,
                    submission.plantId,
                )
            }
            throw failure
        }
    }

    private fun report(event: () -> Todo18RegistrationCommitRepositoryEvent) {
        try {
            observer(event())
        } catch (_: AssertionError) {
            // Diagnostic observation cannot alter repository execution.
        } catch (_: Exception) {
            // Diagnostic observation cannot alter repository execution.
        }
    }
}
