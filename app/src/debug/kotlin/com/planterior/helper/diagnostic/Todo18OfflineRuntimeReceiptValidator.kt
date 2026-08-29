package com.planterior.helper.diagnostic

internal object Todo18OfflineRuntimeReceiptValidator {
    fun hasRuntimeMetadata(receipt: Todo18DiagnosticReceipt): Boolean =
        receipt.pipeline.any { it.runtimeBinding != null || it.kind in DISPLAYED_RUNTIME_EVENTS } ||
            receipt.envelope.activityInstanceIdentity != null ||
            receipt.envelope.navHostInstanceIdentity != null

    fun isValid(receipt: Todo18DiagnosticReceipt): Boolean {
        if (!hasRuntimeMetadata(receipt)) return true
        val envelope = receipt.envelope
        if (envelope.activityInstanceIdentity.isNullOrBlank()) return false
        if (envelope.navHostInstanceIdentity.isNullOrBlank()) return false
        val runtimeEvents = receipt.pipeline.filter { it.kind in RUNTIME_EVENTS }
        if (runtimeEvents.any { it.runtimeBinding == null }) return false
        if (runtimeEvents.any { !it.hasValidRuntimeBinding() }) return false
        val stages =
            receipt.pipeline.filter { it.kind in STAGE_ORDER }.map { STAGE_ORDER.getValue(it.kind) }
        return stages == stages.sorted()
    }

    private fun Todo18PipelineEvent.hasValidRuntimeBinding(): Boolean {
        val binding = runtimeBinding ?: return false
        return controllerIdentity?.toString() == binding.controllerIdentity &&
            binding.controllerEpoch >= 0L &&
            binding.controllerGeneration >= 0L &&
            binding.collectorGeneration > 0L &&
            binding.callbackGeneration > 0L &&
            binding.attachGeneration > 0L &&
            binding.disposeGeneration >= 0L &&
            binding.lifecycleOwnerIdentity.isNotBlank() &&
            binding.lifecycleState.isNotBlank() &&
            binding.activityIdentity.isNotBlank() &&
            binding.navHostIdentity.isNotBlank() &&
            binding.callbackSinkIdentity.isNotBlank()
    }

    private val DISPLAYED_RUNTIME_EVENTS =
        setOf(
            Todo18PipelineEventKind.DISPLAYED_CALLBACK_ENTRY,
            Todo18PipelineEventKind.DISPLAYED_SINK_ENTRY,
            Todo18PipelineEventKind.DISPLAYED_SINK_RETURN,
            Todo18PipelineEventKind.DISPLAYED_CALLBACK_RETURN,
        )
    private val RUNTIME_EVENTS =
        DISPLAYED_RUNTIME_EVENTS + Todo18PipelineEventKind.ROUTE_STATE_OBSERVED
    private val STAGE_ORDER =
        listOf(
                Todo18PipelineEventKind.ROUTE_STATE_OBSERVED,
                Todo18PipelineEventKind.DISPLAYED_CALLBACK_ENTRY,
                Todo18PipelineEventKind.DISPLAYED_SINK_ENTRY,
                Todo18PipelineEventKind.TASK1_PUBLICATION,
                Todo18PipelineEventKind.PRIMARY_DISPATCH_BEGIN,
                Todo18PipelineEventKind.PRIMARY_DISPATCH_RETURN,
                Todo18PipelineEventKind.PRIMARY_DISPATCH_FAILURE,
                Todo18PipelineEventKind.DISPLAYED_SINK_RETURN,
                Todo18PipelineEventKind.DISPLAYED_CALLBACK_RETURN,
            )
            .withIndex()
            .associate { (index, kind) -> kind to index }
}
