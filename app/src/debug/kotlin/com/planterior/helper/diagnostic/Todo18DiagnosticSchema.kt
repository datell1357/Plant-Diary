package com.planterior.helper.diagnostic

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId

enum class Todo18WaitId {
    CONFLICT_BEGIN_EDIT,
    OFFLINE_BEGIN_EDIT,
    OFFLINE_INITIAL_VIEWING,
    REGISTRATION_SELECT_CONTENT,
    REGISTRATION_COMMIT,
}

enum class Todo18DiagnosticClassification {
    INVALID_CAPTURE,
    CROSS_TEST_LEAK,
    RUNTIME_SINK_IDENTITY_MISMATCH,
    TEST_INTERACTION_REJECTED,
    ROUTE_ACTION_NOT_FORWARDED,
    CONTROLLER_REJECTED_ACTION,
    STALE_UI_CONTROLLER_BINDING,
    ROUTE_STATE_NOT_OBSERVED,
    TASK1_PUBLICATION_MISSING,
    STREAM_DISPATCH_MISSED,
    OFFLINE_CALLBACK_MISSING,
    OFFLINE_CALLBACK_STALE,
    OFFLINE_ACTIVITY_NAVHOST_MISMATCH,
    OFFLINE_CALLBACK_SINK_BINDING_MISMATCH,
    OFFLINE_SINK_ENTRY_MISSING,
    OFFLINE_SINK_RETURN_MISSING,
    OFFLINE_TASK1_PUBLICATION_MISSING,
    OFFLINE_PRIMARY_DISPATCH_MISSING,
    PROBE_REGISTRATION_MISSED,
    PROBE_REJECTED_MATCH,
    PREDICATE_MISMATCH,
    UI_POSTCONDITION_MISSING,
    EXPECTED_TRANSITION_OBSERVED,
    COMMIT_SUBMIT_CALLBACK_MISSED,
    COMMIT_CONTROLLER_ENTRY_MISSED,
    COMMIT_VALIDATION_MISSED,
    COMMIT_VALIDATION_REJECTED,
    COMMIT_DUPLICATE_LOOKUP_MISSED,
    COMMIT_DUPLICATE_LOOKUP_REJECTED,
    COMMIT_REPOSITORY_ENTRY_MISSED,
    COMMIT_REPOSITORY_REJECTED,
    COMMIT_REMOTE_COMMIT_MISSED,
    COMMIT_COMPLETED_PUBLICATION_MISSED,
    COMMIT_NAVIGATION_ENQUEUE_MISSED,
    COMMIT_NAVIGATION_DISPATCH_MISSED,
    COMMIT_NAVIGATION_DESTINATION_MISSED,
}

enum class Todo18DiagnosticFailure {
    RECORDER_CALLBACK_FAILED,
    OBSERVER_CALLBACK_FAILED,
    ACTION_CALLBACK_FAILED,
    ROUTE_CALLBACK_FAILED,
    DISPATCH_FAILED,
    CAPTURE_LIMIT_EXCEEDED,
}

enum class Todo18PipelineEventKind {
    FRAMEWORK_ACTION_BEGIN,
    FRAMEWORK_ACTION_RETURN,
    FRAMEWORK_ACTION_FAILURE,
    SCREEN_CALLBACK,
    CONTROLLER_ENTRY,
    CONTROLLER_TARGET_STATE,
    ROUTE_STATE_OBSERVED,
    DISPLAYED_CALLBACK_ENTRY,
    DISPLAYED_SINK_ENTRY,
    DISPLAYED_SINK_RETURN,
    DISPLAYED_CALLBACK_RETURN,
    TASK1_PUBLICATION,
    PRIMARY_DISPATCH_BEGIN,
    PRIMARY_DISPATCH_RETURN,
    PRIMARY_DISPATCH_FAILURE,
    SUBSCRIPTION_RECEIVE,
    PREDICATE_TRUE,
    PREDICATE_FALSE,
    EVENT_ACCEPTED,
    EVENT_REJECTED,
    AWAIT_SUCCESS,
    AWAIT_FAILURE,
    UI_POSTCONDITION,
    DETACH,
    DRAIN,
    SUBMIT_CALLBACK,
    REGISTRATION_CONTROLLER_ENTRY,
    REGISTRATION_VALIDATION_ACCEPTED,
    REGISTRATION_VALIDATION_REJECTED,
    DUPLICATE_LOOKUP_BEGIN,
    DUPLICATE_LOOKUP_EMPTY,
    DUPLICATE_LOOKUP_FOUND,
    DUPLICATE_LOOKUP_FAILED,
    DUPLICATE_LOOKUP_CANCELLED,
    REGISTRATION_REPOSITORY_ENTRY,
    REGISTRATION_REPOSITORY_COMPLETED,
    REGISTRATION_REPOSITORY_FAILED,
    REGISTRATION_REPOSITORY_CANCELLED,
    REMOTE_COMMIT,
    REGISTRATION_COMMITTED_READ_ENTERED,
    REGISTRATION_COMMITTED_READ_RETURNED,
    REGISTRATION_COMMITTED_READ_THREW,
    REGISTRATION_COMMITTED_READ_CANCELLED,
    REGISTRATION_CACHE_UPSERT_ENTERED,
    REGISTRATION_CACHE_UPSERT_RETURNED,
    REGISTRATION_CACHE_UPSERT_THREW,
    REGISTRATION_CACHE_UPSERT_CANCELLED,
    REGISTRATION_OUTBOX_REMOVE_ENTERED,
    REGISTRATION_OUTBOX_REMOVE_RETURNED,
    REGISTRATION_OUTBOX_REMOVE_THREW,
    REGISTRATION_OUTBOX_REMOVE_CANCELLED,
    REGISTRATION_COMPLETED_RETURNED,
    REGISTRATION_COMPLETED_PUBLICATION,
    REGISTRATION_NAVIGATION_ENQUEUED,
    REGISTRATION_NAVIGATION_DISPATCHED,
    REGISTRATION_NAVIGATION_DESTINATION,
}

data class Todo18PipelineEvent(
    val ordinal: Long,
    val kind: Todo18PipelineEventKind,
    val sourceSequence: Long? = null,
    val controllerIdentity: Int? = null,
    val requestedContentId: PlantContentId? = null,
    val beforeState: Todo18StateKind? = null,
    val afterState: Todo18StateKind? = null,
    val registrationPlantId: PersonalPlantId? = null,
    val registrationOperationId: OperationId? = null,
    val registrationAccountId: AccountId? = null,
    val repositoryIdentity: Int? = null,
    val navigationIdentity: String? = null,
    val runtimeBinding: Todo18RuntimeBinding? = null,
    val elapsedNanos: Long? = null,
)

data class Todo18RuntimeBinding(
    val controllerIdentity: String,
    val controllerEpoch: Long,
    val controllerGeneration: Long,
    val collectorGeneration: Long,
    val callbackGeneration: Long,
    val attachGeneration: Long,
    val disposeGeneration: Long,
    val lifecycleOwnerIdentity: String,
    val lifecycleState: String,
    val activityIdentity: String,
    val navHostIdentity: String,
    val callbackSinkIdentity: String,
)

data class Todo18DiagnosticEnvelope(
    val schema: String?,
    val waitId: Todo18WaitId?,
    val expectedSourceSha256: String?,
    val embeddedSourceSha256: String?,
    val expectedAppApkSha256: String?,
    val observedAppApkSha256: String?,
    val expectedAndroidTestApkSha256: String?,
    val observedAndroidTestApkSha256: String?,
    val bindingValidated: Boolean?,
    val installedSinkIdentity: String?,
    val runtimeSinkIdentity: String?,
    val activitySinkIdentity: String?,
    val activityInstanceIdentity: String? = null,
    val navHostInstanceIdentity: String? = null,
    val freshSink: Boolean?,
    val initialSequence: Long?,
    val initialCurrentsEmpty: Boolean?,
    val initialListenerCount: Int?,
    val priorActivityCount: Int?,
    val priorOverridePresent: Boolean?,
    val overrideInstalledAtCapture: Boolean?,
    val activityCreateCount: Int?,
    val activityDestroyCount: Int?,
    val activityActiveCount: Int?,
    val previousTeardownComplete: Boolean?,
    val captureFinalized: Boolean?,
    val detached: Boolean?,
    val drained: Boolean?,
    val finalListenerCount: Int?,
    val diagnosticFailures: List<Todo18DiagnosticFailure>,
) {
    companion object {
        const val SCHEMA = "todo18-transition-diagnostic-v3"
    }
}

data class Todo18DiagnosticReceipt(
    val envelope: Todo18DiagnosticEnvelope,
    val pipeline: List<Todo18PipelineEvent>,
)

internal data class Todo18WaitContract(
    val screenCallback: Boolean,
    val controllerEntry: Boolean,
    val controllerTarget: Boolean,
    val controllerIdentity: Boolean,
    val offlineRuntimeBinding: Boolean,
)

internal fun Todo18WaitId.contract(): Todo18WaitContract =
    when (this) {
        Todo18WaitId.CONFLICT_BEGIN_EDIT,
        Todo18WaitId.OFFLINE_BEGIN_EDIT,
        Todo18WaitId.REGISTRATION_SELECT_CONTENT ->
            Todo18WaitContract(
                screenCallback = true,
                controllerEntry = true,
                controllerTarget = true,
                controllerIdentity = true,
                offlineRuntimeBinding = this == Todo18WaitId.OFFLINE_BEGIN_EDIT,
            )
        Todo18WaitId.OFFLINE_INITIAL_VIEWING,
        Todo18WaitId.REGISTRATION_COMMIT ->
            Todo18WaitContract(
                screenCallback = false,
                controllerEntry = false,
                controllerTarget = false,
                controllerIdentity = false,
                offlineRuntimeBinding = false,
            )
    }
