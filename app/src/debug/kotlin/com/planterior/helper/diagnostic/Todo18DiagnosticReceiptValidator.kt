package com.planterior.helper.diagnostic

internal object Todo18DiagnosticReceiptValidator {
    private val sha256 = Regex("^[0-9a-f]{64}$")

    fun isValid(receipt: Todo18DiagnosticReceipt): Boolean {
        val envelope = receipt.envelope
        val waitId = envelope.waitId ?: return false
        if (envelope.schema != Todo18DiagnosticEnvelope.SCHEMA) return false
        if (!envelope.expectedSourceSha256.isHash()) return false
        if (!envelope.embeddedSourceSha256.isHash()) return false
        if (!envelope.expectedAppApkSha256.isHash()) return false
        if (!envelope.observedAppApkSha256.isHash()) return false
        if (!envelope.expectedAndroidTestApkSha256.isHash()) return false
        if (!envelope.observedAndroidTestApkSha256.isHash()) return false
        if (envelope.expectedSourceSha256 != envelope.embeddedSourceSha256) return false
        if (envelope.expectedAppApkSha256 != envelope.observedAppApkSha256) return false
        if (envelope.expectedAndroidTestApkSha256 != envelope.observedAndroidTestApkSha256)
            return false
        if (envelope.bindingValidated != true) return false
        if (envelope.installedSinkIdentity.isNullOrBlank()) return false
        if (envelope.runtimeSinkIdentity.isNullOrBlank()) return false
        if (envelope.activitySinkIdentity.isNullOrBlank()) return false
        if (envelope.freshSink == null) return false
        if (envelope.initialSequence == null) return false
        if (envelope.initialCurrentsEmpty == null) return false
        if (envelope.initialListenerCount == null) return false
        if (envelope.priorActivityCount == null) return false
        if (envelope.priorOverridePresent == null) return false
        if (envelope.overrideInstalledAtCapture == null) return false
        if (envelope.activityCreateCount == null) return false
        if (envelope.activityDestroyCount == null) return false
        if (envelope.activityActiveCount == null) return false
        if (envelope.previousTeardownComplete == null) return false
        if (envelope.captureFinalized != true) return false
        if (envelope.detached != true || envelope.drained != true) return false
        if (envelope.finalListenerCount != 0) return false
        if (envelope.diagnosticFailures.isNotEmpty()) return false
        if (!receipt.pipeline.hasValidOrdering()) return false
        if (!receipt.pipeline.hasValidSequences()) return false
        if (!receipt.pipeline.hasUniqueEvents()) return false
        if (!receipt.pipeline.hasRequiredTerminal()) return false
        if (receipt.pipeline.hasContradiction(waitId.contract())) return false
        if (
            waitId.contract().offlineRuntimeBinding &&
                !Todo18OfflineRuntimeReceiptValidator.isValid(receipt)
        ) {
            return false
        }
        if (
            waitId == Todo18WaitId.REGISTRATION_COMMIT &&
                !Todo18RegistrationReceiptValidator.isValid(receipt.pipeline)
        ) {
            return false
        }
        return true
    }

    private fun String?.isHash(): Boolean = this != null && sha256.matches(this)

    private fun List<Todo18PipelineEvent>.hasValidOrdering(): Boolean =
        map(Todo18PipelineEvent::ordinal) == (1L..size.toLong()).toList()

    private fun List<Todo18PipelineEvent>.hasValidSequences(): Boolean {
        val sequences = mapNotNull(Todo18PipelineEvent::sourceSequence)
        return sequences.all { it > 0L } && sequences.zipWithNext().all { (a, b) -> a <= b }
    }

    private fun List<Todo18PipelineEvent>.hasUniqueEvents(): Boolean =
        size == map(Todo18PipelineEvent::kind).toSet().size

    private fun List<Todo18PipelineEvent>.hasRequiredTerminal(): Boolean {
        val kinds = kinds()
        return (Todo18PipelineEventKind.AWAIT_SUCCESS in kinds) xor
            (Todo18PipelineEventKind.AWAIT_FAILURE in kinds) &&
            Todo18PipelineEventKind.DETACH in kinds &&
            Todo18PipelineEventKind.DRAIN in kinds
    }

    private fun List<Todo18PipelineEvent>.hasContradiction(contract: Todo18WaitContract): Boolean {
        val kinds = kinds()
        if (
            hasBoth(
                kinds,
                Todo18PipelineEventKind.FRAMEWORK_ACTION_RETURN,
                Todo18PipelineEventKind.FRAMEWORK_ACTION_FAILURE,
            )
        )
            return true
        if (
            hasBoth(
                kinds,
                Todo18PipelineEventKind.PREDICATE_TRUE,
                Todo18PipelineEventKind.PREDICATE_FALSE,
            )
        )
            return true
        if (
            hasBoth(
                kinds,
                Todo18PipelineEventKind.EVENT_ACCEPTED,
                Todo18PipelineEventKind.EVENT_REJECTED,
            )
        )
            return true
        if (
            hasBoth(
                kinds,
                Todo18PipelineEventKind.PRIMARY_DISPATCH_RETURN,
                Todo18PipelineEventKind.PRIMARY_DISPATCH_FAILURE,
            )
        )
            return true
        if (
            Todo18PipelineEventKind.CONTROLLER_TARGET_STATE in kinds &&
                contract.controllerEntry &&
                Todo18PipelineEventKind.CONTROLLER_ENTRY !in kinds
        )
            return true
        if (
            Todo18PipelineEventKind.ROUTE_STATE_OBSERVED in kinds &&
                contract.controllerTarget &&
                Todo18PipelineEventKind.CONTROLLER_TARGET_STATE !in kinds
        )
            return true
        if (
            Todo18PipelineEventKind.TASK1_PUBLICATION in kinds &&
                Todo18PipelineEventKind.ROUTE_STATE_OBSERVED !in kinds
        )
            return true
        if (
            Todo18PipelineEventKind.PRIMARY_DISPATCH_BEGIN in kinds &&
                Todo18PipelineEventKind.TASK1_PUBLICATION !in kinds &&
                !contract.offlineRuntimeBinding
        )
            return true
        if (
            Todo18PipelineEventKind.PRIMARY_DISPATCH_RETURN in kinds &&
                Todo18PipelineEventKind.PRIMARY_DISPATCH_BEGIN !in kinds
        )
            return true
        if (
            Todo18PipelineEventKind.PRIMARY_DISPATCH_BEGIN in kinds &&
                Todo18PipelineEventKind.PRIMARY_DISPATCH_RETURN !in kinds &&
                Todo18PipelineEventKind.PRIMARY_DISPATCH_FAILURE !in kinds
        )
            return true
        if (
            (Todo18PipelineEventKind.PREDICATE_TRUE in kinds ||
                Todo18PipelineEventKind.PREDICATE_FALSE in kinds) &&
                Todo18PipelineEventKind.SUBSCRIPTION_RECEIVE !in kinds
        )
            return true
        if (
            Todo18PipelineEventKind.EVENT_ACCEPTED in kinds &&
                Todo18PipelineEventKind.PREDICATE_TRUE !in kinds
        )
            return true
        if (
            Todo18PipelineEventKind.UI_POSTCONDITION in kinds &&
                Todo18PipelineEventKind.EVENT_ACCEPTED !in kinds
        )
            return true
        return false
    }

    private fun List<Todo18PipelineEvent>.kinds(): Set<Todo18PipelineEventKind> =
        map(Todo18PipelineEvent::kind).toSet()

    private fun hasBoth(
        kinds: Set<Todo18PipelineEventKind>,
        first: Todo18PipelineEventKind,
        second: Todo18PipelineEventKind,
    ): Boolean = first in kinds && second in kinds
}
