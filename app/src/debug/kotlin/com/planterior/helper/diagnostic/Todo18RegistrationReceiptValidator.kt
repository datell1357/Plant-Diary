package com.planterior.helper.diagnostic

internal object Todo18RegistrationReceiptValidator {
    fun isValid(pipeline: List<Todo18PipelineEvent>): Boolean =
        pipeline.hasCommitOrdering() &&
            pipeline.hasCommitBindings() &&
            pipeline.hasPersistenceBindings() &&
            pipeline.hasPersistenceTerminals()

    private fun List<Todo18PipelineEvent>.hasCommitBindings(): Boolean {
        val controllerEvents = filter { it.kind in COMMIT_CONTROLLER_EVENTS }
        if (
            controllerEvents.isNotEmpty() &&
                controllerEvents.mapNotNull { it.controllerIdentity }.toSet().size != 1
        ) {
            return false
        }
        if (controllerEvents.any { it.controllerIdentity == null }) return false
        val repositoryEvents = filter { it.kind in COMMIT_REPOSITORY_EVENTS }
        if (
            repositoryEvents.any {
                it.repositoryIdentity == null || it.registrationOperationId == null
            }
        ) {
            return false
        }
        if (repositoryEvents.mapNotNull { it.repositoryIdentity }.toSet().size > 1) return false
        if (repositoryEvents.mapNotNull { it.registrationOperationId }.toSet().size > 1) {
            return false
        }
        val plantEvents = filter { it.kind in COMMIT_PLANT_EVENTS }
        if (plantEvents.any { it.registrationPlantId == null }) return false
        if (plantEvents.mapNotNull { it.registrationPlantId }.toSet().size > 1) return false
        val navigationEvents = filter { it.kind in COMMIT_NAVIGATION_EVENTS }
        if (navigationEvents.any { it.navigationIdentity.isNullOrBlank() }) return false
        return navigationEvents.mapNotNull { it.navigationIdentity }.toSet().size <= 1
    }

    private fun List<Todo18PipelineEvent>.hasPersistenceBindings(): Boolean {
        val events = filter { it.kind in PERSISTENCE_EVENTS }
        if (events.isEmpty()) return true
        if (events.any { it.registrationAccountId == null }) return false
        if (events.any { it.registrationOperationId == null }) return false
        if (events.any { it.registrationPlantId == null }) return false
        if (events.mapNotNull { it.registrationAccountId }.toSet().size != 1) return false
        if (events.mapNotNull { it.registrationOperationId }.toSet().size != 1) return false
        if (events.mapNotNull { it.registrationPlantId }.toSet().size != 1) return false
        if (events.any { it.elapsedNanos == null }) return false
        return events.mapNotNull { it.elapsedNanos } ==
            events.mapNotNull { it.elapsedNanos }.sorted()
    }

    private fun List<Todo18PipelineEvent>.hasPersistenceTerminals(): Boolean {
        PERSISTENCE_STAGES.forEach { stage ->
            val entered = filter { it.kind == stage.entered }
            val terminals = filter { it.kind in stage.terminals }
            if (entered.size > 1 || terminals.size > 1) return false
            if (entered.isEmpty() != terminals.isEmpty()) return false
            if (entered.isNotEmpty() && indexOf(entered.single()) >= indexOf(terminals.single())) {
                return false
            }
        }
        val completed = filter {
            it.kind == Todo18PipelineEventKind.REGISTRATION_COMPLETED_RETURNED
        }
        if (completed.size > 1) return false
        if (completed.isNotEmpty()) {
            if (
                PERSISTENCE_STAGES.any { stage ->
                    count { it.kind == stage.entered } != 1 ||
                        count { it.kind in stage.terminals } != 1
                }
            ) {
                return false
            }
            val outboxTerminal = single { it.kind in PERSISTENCE_STAGES.last().terminals }
            if (indexOf(outboxTerminal) >= indexOf(completed.single())) return false
        }
        return true
    }

    private fun List<Todo18PipelineEvent>.hasCommitOrdering(): Boolean {
        val stages = filter { it.kind in COMMIT_STAGE_ORDER }.map(Todo18PipelineEvent::kind)
        val indexes = stages.map(COMMIT_STAGE_ORDER::getValue)
        if (indexes != indexes.sorted()) return false
        val kinds = stages.toSet()
        if (
            hasBoth(
                kinds,
                Todo18PipelineEventKind.REGISTRATION_VALIDATION_ACCEPTED,
                Todo18PipelineEventKind.REGISTRATION_VALIDATION_REJECTED,
            )
        ) {
            return false
        }
        if (
            kinds.intersect(DUPLICATE_TERMINALS).size > 1 ||
                kinds.intersect(REPOSITORY_TERMINALS).size > 1
        ) {
            return false
        }
        return true
    }

    private fun hasBoth(
        kinds: Set<Todo18PipelineEventKind>,
        first: Todo18PipelineEventKind,
        second: Todo18PipelineEventKind,
    ): Boolean = first in kinds && second in kinds

    private val COMMIT_CONTROLLER_EVENTS =
        setOf(
            Todo18PipelineEventKind.SUBMIT_CALLBACK,
            Todo18PipelineEventKind.REGISTRATION_CONTROLLER_ENTRY,
            Todo18PipelineEventKind.REGISTRATION_VALIDATION_ACCEPTED,
            Todo18PipelineEventKind.REGISTRATION_VALIDATION_REJECTED,
            Todo18PipelineEventKind.DUPLICATE_LOOKUP_BEGIN,
            Todo18PipelineEventKind.DUPLICATE_LOOKUP_EMPTY,
            Todo18PipelineEventKind.DUPLICATE_LOOKUP_FOUND,
            Todo18PipelineEventKind.DUPLICATE_LOOKUP_FAILED,
            Todo18PipelineEventKind.DUPLICATE_LOOKUP_CANCELLED,
            Todo18PipelineEventKind.REGISTRATION_COMPLETED_PUBLICATION,
            Todo18PipelineEventKind.REGISTRATION_NAVIGATION_ENQUEUED,
            Todo18PipelineEventKind.REGISTRATION_NAVIGATION_DISPATCHED,
        )
    private val COMMIT_REPOSITORY_EVENTS =
        setOf(
            Todo18PipelineEventKind.REGISTRATION_REPOSITORY_ENTRY,
            Todo18PipelineEventKind.REGISTRATION_REPOSITORY_COMPLETED,
            Todo18PipelineEventKind.REGISTRATION_REPOSITORY_FAILED,
            Todo18PipelineEventKind.REGISTRATION_REPOSITORY_CANCELLED,
        )
    private val COMMIT_PLANT_EVENTS =
        setOf(
            Todo18PipelineEventKind.REGISTRATION_REPOSITORY_ENTRY,
            Todo18PipelineEventKind.REGISTRATION_REPOSITORY_COMPLETED,
            Todo18PipelineEventKind.REGISTRATION_REPOSITORY_FAILED,
            Todo18PipelineEventKind.REGISTRATION_REPOSITORY_CANCELLED,
            Todo18PipelineEventKind.REMOTE_COMMIT,
            Todo18PipelineEventKind.REGISTRATION_COMPLETED_PUBLICATION,
            Todo18PipelineEventKind.REGISTRATION_NAVIGATION_ENQUEUED,
            Todo18PipelineEventKind.REGISTRATION_NAVIGATION_DISPATCHED,
            Todo18PipelineEventKind.REGISTRATION_NAVIGATION_DESTINATION,
        )
    private val COMMIT_NAVIGATION_EVENTS =
        setOf(
            Todo18PipelineEventKind.REGISTRATION_NAVIGATION_ENQUEUED,
            Todo18PipelineEventKind.REGISTRATION_NAVIGATION_DISPATCHED,
        )
    private val COMMIT_STAGE_ORDER =
        listOf(
                Todo18PipelineEventKind.SUBMIT_CALLBACK,
                Todo18PipelineEventKind.REGISTRATION_CONTROLLER_ENTRY,
                Todo18PipelineEventKind.REGISTRATION_VALIDATION_ACCEPTED,
                Todo18PipelineEventKind.REGISTRATION_VALIDATION_REJECTED,
                Todo18PipelineEventKind.DUPLICATE_LOOKUP_BEGIN,
                Todo18PipelineEventKind.DUPLICATE_LOOKUP_EMPTY,
                Todo18PipelineEventKind.DUPLICATE_LOOKUP_FOUND,
                Todo18PipelineEventKind.DUPLICATE_LOOKUP_FAILED,
                Todo18PipelineEventKind.DUPLICATE_LOOKUP_CANCELLED,
                Todo18PipelineEventKind.REGISTRATION_REPOSITORY_ENTRY,
                Todo18PipelineEventKind.REMOTE_COMMIT,
                Todo18PipelineEventKind.REGISTRATION_COMMITTED_READ_ENTERED,
                Todo18PipelineEventKind.REGISTRATION_COMMITTED_READ_RETURNED,
                Todo18PipelineEventKind.REGISTRATION_COMMITTED_READ_THREW,
                Todo18PipelineEventKind.REGISTRATION_COMMITTED_READ_CANCELLED,
                Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_ENTERED,
                Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_RETURNED,
                Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_THREW,
                Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_CANCELLED,
                Todo18PipelineEventKind.REGISTRATION_OUTBOX_REMOVE_ENTERED,
                Todo18PipelineEventKind.REGISTRATION_OUTBOX_REMOVE_RETURNED,
                Todo18PipelineEventKind.REGISTRATION_OUTBOX_REMOVE_THREW,
                Todo18PipelineEventKind.REGISTRATION_OUTBOX_REMOVE_CANCELLED,
                Todo18PipelineEventKind.REGISTRATION_COMPLETED_RETURNED,
                Todo18PipelineEventKind.REGISTRATION_REPOSITORY_COMPLETED,
                Todo18PipelineEventKind.REGISTRATION_REPOSITORY_FAILED,
                Todo18PipelineEventKind.REGISTRATION_REPOSITORY_CANCELLED,
                Todo18PipelineEventKind.REGISTRATION_COMPLETED_PUBLICATION,
                Todo18PipelineEventKind.REGISTRATION_NAVIGATION_ENQUEUED,
                Todo18PipelineEventKind.REGISTRATION_NAVIGATION_DISPATCHED,
                Todo18PipelineEventKind.REGISTRATION_NAVIGATION_DESTINATION,
            )
            .withIndex()
            .associate { (index, kind) -> kind to index }
    private val DUPLICATE_TERMINALS =
        setOf(
            Todo18PipelineEventKind.DUPLICATE_LOOKUP_EMPTY,
            Todo18PipelineEventKind.DUPLICATE_LOOKUP_FOUND,
            Todo18PipelineEventKind.DUPLICATE_LOOKUP_FAILED,
            Todo18PipelineEventKind.DUPLICATE_LOOKUP_CANCELLED,
        )
    private val REPOSITORY_TERMINALS =
        setOf(
            Todo18PipelineEventKind.REGISTRATION_REPOSITORY_COMPLETED,
            Todo18PipelineEventKind.REGISTRATION_REPOSITORY_FAILED,
            Todo18PipelineEventKind.REGISTRATION_REPOSITORY_CANCELLED,
        )
    private val PERSISTENCE_STAGES =
        listOf(
            PersistenceStage(
                Todo18PipelineEventKind.REGISTRATION_COMMITTED_READ_ENTERED,
                setOf(
                    Todo18PipelineEventKind.REGISTRATION_COMMITTED_READ_RETURNED,
                    Todo18PipelineEventKind.REGISTRATION_COMMITTED_READ_THREW,
                    Todo18PipelineEventKind.REGISTRATION_COMMITTED_READ_CANCELLED,
                ),
            ),
            PersistenceStage(
                Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_ENTERED,
                setOf(
                    Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_RETURNED,
                    Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_THREW,
                    Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_CANCELLED,
                ),
            ),
            PersistenceStage(
                Todo18PipelineEventKind.REGISTRATION_OUTBOX_REMOVE_ENTERED,
                setOf(
                    Todo18PipelineEventKind.REGISTRATION_OUTBOX_REMOVE_RETURNED,
                    Todo18PipelineEventKind.REGISTRATION_OUTBOX_REMOVE_THREW,
                    Todo18PipelineEventKind.REGISTRATION_OUTBOX_REMOVE_CANCELLED,
                ),
            ),
        )
    private val PERSISTENCE_EVENTS =
        PERSISTENCE_STAGES.flatMap { listOf(it.entered) + it.terminals }.toSet() +
            Todo18PipelineEventKind.REGISTRATION_COMPLETED_RETURNED

    private data class PersistenceStage(
        val entered: Todo18PipelineEventKind,
        val terminals: Set<Todo18PipelineEventKind>,
    )
}
