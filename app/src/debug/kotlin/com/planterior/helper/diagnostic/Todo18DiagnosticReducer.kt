package com.planterior.helper.diagnostic

object Todo18DiagnosticReducer {
    fun classify(receipt: Todo18DiagnosticReceipt): Todo18DiagnosticClassification {
        if (!Todo18DiagnosticReceiptValidator.isValid(receipt)) {
            return Todo18DiagnosticClassification.INVALID_CAPTURE
        }
        val envelope = receipt.envelope
        if (
            envelope.freshSink != true ||
                envelope.initialSequence != 0L ||
                envelope.initialCurrentsEmpty != true ||
                envelope.initialListenerCount != 0 ||
                envelope.priorActivityCount != 0 ||
                envelope.priorOverridePresent != false ||
                envelope.overrideInstalledAtCapture != true ||
                envelope.activityCreateCount != 1 ||
                envelope.activityDestroyCount != 0 ||
                envelope.activityActiveCount != 1 ||
                envelope.previousTeardownComplete != true
        ) {
            return Todo18DiagnosticClassification.CROSS_TEST_LEAK
        }
        if (
            envelope.installedSinkIdentity != envelope.runtimeSinkIdentity ||
                envelope.runtimeSinkIdentity != envelope.activitySinkIdentity
        ) {
            return Todo18DiagnosticClassification.RUNTIME_SINK_IDENTITY_MISMATCH
        }

        val waitId = checkNotNull(envelope.waitId)
        if (waitId == Todo18WaitId.REGISTRATION_COMMIT) {
            return classifyRegistrationCommit(receipt)
        }
        val contract = waitId.contract()
        val kinds = receipt.pipeline.map(Todo18PipelineEvent::kind).toSet()
        if (
            Todo18PipelineEventKind.FRAMEWORK_ACTION_FAILURE in kinds ||
                Todo18PipelineEventKind.FRAMEWORK_ACTION_RETURN !in kinds ||
                (contract.screenCallback && Todo18PipelineEventKind.SCREEN_CALLBACK !in kinds)
        ) {
            return Todo18DiagnosticClassification.TEST_INTERACTION_REJECTED
        }
        if (contract.controllerEntry && Todo18PipelineEventKind.CONTROLLER_ENTRY !in kinds) {
            return Todo18DiagnosticClassification.ROUTE_ACTION_NOT_FORWARDED
        }
        if (
            contract.controllerTarget && Todo18PipelineEventKind.CONTROLLER_TARGET_STATE !in kinds
        ) {
            return Todo18DiagnosticClassification.CONTROLLER_REJECTED_ACTION
        }
        if (contract.controllerIdentity && receipt.hasControllerIdentityMismatch()) {
            return Todo18DiagnosticClassification.STALE_UI_CONTROLLER_BINDING
        }
        if (Todo18PipelineEventKind.ROUTE_STATE_OBSERVED !in kinds) {
            return Todo18DiagnosticClassification.ROUTE_STATE_NOT_OBSERVED
        }
        if (
            waitId == Todo18WaitId.OFFLINE_BEGIN_EDIT &&
                Todo18OfflineRuntimeReceiptValidator.hasRuntimeMetadata(receipt)
        ) {
            Todo18OfflineRuntimeReducer.classify(receipt)?.let {
                return it
            }
        }
        if (Todo18PipelineEventKind.TASK1_PUBLICATION !in kinds) {
            return Todo18DiagnosticClassification.TASK1_PUBLICATION_MISSING
        }
        if (
            Todo18PipelineEventKind.PRIMARY_DISPATCH_BEGIN !in kinds ||
                Todo18PipelineEventKind.PRIMARY_DISPATCH_RETURN !in kinds
        ) {
            return Todo18DiagnosticClassification.STREAM_DISPATCH_MISSED
        }
        if (Todo18PipelineEventKind.SUBSCRIPTION_RECEIVE !in kinds) {
            return Todo18DiagnosticClassification.PROBE_REGISTRATION_MISSED
        }
        if (
            Todo18PipelineEventKind.PREDICATE_TRUE !in kinds &&
                Todo18PipelineEventKind.PREDICATE_FALSE !in kinds
        ) {
            return Todo18DiagnosticClassification.PROBE_REJECTED_MATCH
        }
        if (Todo18PipelineEventKind.PREDICATE_FALSE in kinds) {
            return Todo18DiagnosticClassification.PREDICATE_MISMATCH
        }
        if (Todo18PipelineEventKind.EVENT_ACCEPTED !in kinds) {
            return Todo18DiagnosticClassification.PROBE_REJECTED_MATCH
        }
        if (Todo18PipelineEventKind.UI_POSTCONDITION !in kinds) {
            return Todo18DiagnosticClassification.UI_POSTCONDITION_MISSING
        }
        return Todo18DiagnosticClassification.EXPECTED_TRANSITION_OBSERVED
    }

    private fun classifyRegistrationCommit(
        receipt: Todo18DiagnosticReceipt
    ): Todo18DiagnosticClassification {
        val kinds = receipt.pipeline.map(Todo18PipelineEvent::kind).toSet()
        if (
            Todo18PipelineEventKind.FRAMEWORK_ACTION_FAILURE in kinds ||
                Todo18PipelineEventKind.FRAMEWORK_ACTION_RETURN !in kinds
        ) {
            return Todo18DiagnosticClassification.TEST_INTERACTION_REJECTED
        }
        if (Todo18PipelineEventKind.SUBMIT_CALLBACK !in kinds) {
            return Todo18DiagnosticClassification.COMMIT_SUBMIT_CALLBACK_MISSED
        }
        if (Todo18PipelineEventKind.REGISTRATION_CONTROLLER_ENTRY !in kinds) {
            return Todo18DiagnosticClassification.COMMIT_CONTROLLER_ENTRY_MISSED
        }
        if (Todo18PipelineEventKind.REGISTRATION_VALIDATION_REJECTED in kinds) {
            return Todo18DiagnosticClassification.COMMIT_VALIDATION_REJECTED
        }
        if (Todo18PipelineEventKind.REGISTRATION_VALIDATION_ACCEPTED !in kinds) {
            return Todo18DiagnosticClassification.COMMIT_VALIDATION_MISSED
        }
        if (
            Todo18PipelineEventKind.DUPLICATE_LOOKUP_FOUND in kinds ||
                Todo18PipelineEventKind.DUPLICATE_LOOKUP_FAILED in kinds ||
                Todo18PipelineEventKind.DUPLICATE_LOOKUP_CANCELLED in kinds
        ) {
            return Todo18DiagnosticClassification.COMMIT_DUPLICATE_LOOKUP_REJECTED
        }
        if (
            Todo18PipelineEventKind.DUPLICATE_LOOKUP_BEGIN !in kinds ||
                Todo18PipelineEventKind.DUPLICATE_LOOKUP_EMPTY !in kinds
        ) {
            return Todo18DiagnosticClassification.COMMIT_DUPLICATE_LOOKUP_MISSED
        }
        if (Todo18PipelineEventKind.REGISTRATION_REPOSITORY_ENTRY !in kinds) {
            return Todo18DiagnosticClassification.COMMIT_REPOSITORY_ENTRY_MISSED
        }
        if (
            Todo18PipelineEventKind.REGISTRATION_REPOSITORY_FAILED in kinds ||
                Todo18PipelineEventKind.REGISTRATION_REPOSITORY_CANCELLED in kinds
        ) {
            return Todo18DiagnosticClassification.COMMIT_REPOSITORY_REJECTED
        }
        if (Todo18PipelineEventKind.REGISTRATION_REPOSITORY_COMPLETED !in kinds) {
            return Todo18DiagnosticClassification.COMMIT_REPOSITORY_REJECTED
        }
        if (Todo18PipelineEventKind.REMOTE_COMMIT !in kinds) {
            return Todo18DiagnosticClassification.COMMIT_REMOTE_COMMIT_MISSED
        }
        if (Todo18PipelineEventKind.REGISTRATION_COMPLETED_PUBLICATION !in kinds) {
            return Todo18DiagnosticClassification.COMMIT_COMPLETED_PUBLICATION_MISSED
        }
        if (Todo18PipelineEventKind.REGISTRATION_NAVIGATION_ENQUEUED !in kinds) {
            return Todo18DiagnosticClassification.COMMIT_NAVIGATION_ENQUEUE_MISSED
        }
        if (Todo18PipelineEventKind.REGISTRATION_NAVIGATION_DISPATCHED !in kinds) {
            return Todo18DiagnosticClassification.COMMIT_NAVIGATION_DISPATCH_MISSED
        }
        if (Todo18PipelineEventKind.REGISTRATION_NAVIGATION_DESTINATION !in kinds) {
            return Todo18DiagnosticClassification.COMMIT_NAVIGATION_DESTINATION_MISSED
        }
        return Todo18DiagnosticClassification.EXPECTED_TRANSITION_OBSERVED
    }

    private fun Todo18DiagnosticReceipt.hasControllerIdentityMismatch(): Boolean {
        val target = pipeline.singleOrNull {
            it.kind == Todo18PipelineEventKind.CONTROLLER_TARGET_STATE
        }
        val route = pipeline.singleOrNull {
            it.kind == Todo18PipelineEventKind.ROUTE_STATE_OBSERVED
        }
        if (target == null || route == null) return false
        return target.controllerIdentity != route.controllerIdentity
    }
}
