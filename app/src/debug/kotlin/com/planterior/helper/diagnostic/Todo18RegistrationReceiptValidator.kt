package com.planterior.helper.diagnostic

internal object Todo18RegistrationReceiptValidator {
    fun isValid(pipeline: List<Todo18PipelineEvent>): Boolean =
        pipeline.hasCommitOrdering() && pipeline.hasCommitBindings()

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
}
