package com.planterior.helper.diagnostic

internal object Todo18OfflineRuntimeReducer {
    fun classify(receipt: Todo18DiagnosticReceipt): Todo18DiagnosticClassification? {
        val kinds = receipt.pipeline.map(Todo18PipelineEvent::kind).toSet()
        if (
            Todo18PipelineEventKind.DISPLAYED_CALLBACK_ENTRY !in kinds ||
                Todo18PipelineEventKind.DISPLAYED_CALLBACK_RETURN !in kinds
        ) {
            return Todo18DiagnosticClassification.OFFLINE_CALLBACK_MISSING
        }
        val bindings = receipt.pipeline.mapNotNull(Todo18PipelineEvent::runtimeBinding)
        if (bindings.hasStaleCallback()) {
            return Todo18DiagnosticClassification.OFFLINE_CALLBACK_STALE
        }
        val envelope = receipt.envelope
        if (
            bindings.any {
                it.activityIdentity != envelope.activityInstanceIdentity ||
                    it.navHostIdentity != envelope.navHostInstanceIdentity
            }
        ) {
            return Todo18DiagnosticClassification.OFFLINE_ACTIVITY_NAVHOST_MISMATCH
        }
        if (
            bindings.any {
                it.callbackSinkIdentity != envelope.installedSinkIdentity ||
                    it.callbackSinkIdentity != envelope.runtimeSinkIdentity ||
                    it.callbackSinkIdentity != envelope.activitySinkIdentity
            }
        ) {
            return Todo18DiagnosticClassification.OFFLINE_CALLBACK_SINK_BINDING_MISMATCH
        }
        if (Todo18PipelineEventKind.DISPLAYED_SINK_ENTRY !in kinds) {
            return Todo18DiagnosticClassification.OFFLINE_SINK_ENTRY_MISSING
        }
        if (Todo18PipelineEventKind.TASK1_PUBLICATION !in kinds) {
            return Todo18DiagnosticClassification.OFFLINE_TASK1_PUBLICATION_MISSING
        }
        if (
            Todo18PipelineEventKind.PRIMARY_DISPATCH_BEGIN !in kinds ||
                Todo18PipelineEventKind.PRIMARY_DISPATCH_RETURN !in kinds ||
                Todo18PipelineEventKind.PRIMARY_DISPATCH_FAILURE in kinds
        ) {
            return Todo18DiagnosticClassification.OFFLINE_PRIMARY_DISPATCH_MISSING
        }
        if (Todo18PipelineEventKind.DISPLAYED_SINK_RETURN !in kinds) {
            return Todo18DiagnosticClassification.OFFLINE_SINK_RETURN_MISSING
        }
        return null
    }

    private fun List<Todo18RuntimeBinding>.hasStaleCallback(): Boolean {
        val expected = firstOrNull()?.callbackExecutionIdentity() ?: return true
        return any { binding ->
            binding.callbackExecutionIdentity() != expected ||
                binding.collectorGeneration <= binding.attachGeneration ||
                binding.callbackGeneration <= binding.attachGeneration ||
                binding.disposeGeneration >= binding.attachGeneration ||
                binding.lifecycleState != "RESUMED"
        }
    }

    private fun Todo18RuntimeBinding.callbackExecutionIdentity() =
        listOf(
            controllerIdentity,
            controllerEpoch,
            controllerGeneration,
            collectorGeneration,
            callbackGeneration,
            attachGeneration,
            disposeGeneration,
            lifecycleOwnerIdentity,
        )
}
