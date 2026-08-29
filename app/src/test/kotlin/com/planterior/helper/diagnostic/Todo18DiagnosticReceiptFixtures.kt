package com.planterior.helper.diagnostic

internal object Todo18DiagnosticReceiptFixtures {
    fun valid(waitId: Todo18WaitId = Todo18WaitId.CONFLICT_BEGIN_EDIT): Todo18DiagnosticReceipt {
        val kinds = buildList {
            add(Todo18PipelineEventKind.FRAMEWORK_ACTION_BEGIN)
            add(Todo18PipelineEventKind.FRAMEWORK_ACTION_RETURN)
            if (waitId != Todo18WaitId.OFFLINE_INITIAL_VIEWING) {
                add(Todo18PipelineEventKind.SCREEN_CALLBACK)
                add(Todo18PipelineEventKind.CONTROLLER_ENTRY)
                add(Todo18PipelineEventKind.CONTROLLER_TARGET_STATE)
            }
            add(Todo18PipelineEventKind.ROUTE_STATE_OBSERVED)
            if (waitId == Todo18WaitId.OFFLINE_BEGIN_EDIT) {
                add(Todo18PipelineEventKind.DISPLAYED_CALLBACK_ENTRY)
                add(Todo18PipelineEventKind.DISPLAYED_SINK_ENTRY)
            }
            add(Todo18PipelineEventKind.TASK1_PUBLICATION)
            add(Todo18PipelineEventKind.PRIMARY_DISPATCH_BEGIN)
            add(Todo18PipelineEventKind.PRIMARY_DISPATCH_RETURN)
            if (waitId == Todo18WaitId.OFFLINE_BEGIN_EDIT) {
                add(Todo18PipelineEventKind.DISPLAYED_SINK_RETURN)
                add(Todo18PipelineEventKind.DISPLAYED_CALLBACK_RETURN)
            }
            add(Todo18PipelineEventKind.SUBSCRIPTION_RECEIVE)
            add(Todo18PipelineEventKind.PREDICATE_TRUE)
            add(Todo18PipelineEventKind.EVENT_ACCEPTED)
            add(Todo18PipelineEventKind.AWAIT_SUCCESS)
            add(Todo18PipelineEventKind.DETACH)
            add(Todo18PipelineEventKind.DRAIN)
            add(Todo18PipelineEventKind.UI_POSTCONDITION)
        }
        return Todo18DiagnosticReceipt(
            envelope =
                Todo18DiagnosticEnvelope(
                    schema = Todo18DiagnosticEnvelope.SCHEMA,
                    waitId = waitId,
                    expectedSourceSha256 = HASH_A,
                    embeddedSourceSha256 = HASH_A,
                    expectedAppApkSha256 = HASH_B,
                    observedAppApkSha256 = HASH_B,
                    expectedAndroidTestApkSha256 = HASH_C,
                    observedAndroidTestApkSha256 = HASH_C,
                    bindingValidated = true,
                    installedSinkIdentity = "sink-1",
                    runtimeSinkIdentity = "sink-1",
                    activitySinkIdentity = "sink-1",
                    activityInstanceIdentity = "activity-1",
                    navHostInstanceIdentity = "nav-host-1",
                    freshSink = true,
                    initialSequence = 0L,
                    initialCurrentsEmpty = true,
                    initialListenerCount = 0,
                    priorActivityCount = 0,
                    priorOverridePresent = false,
                    overrideInstalledAtCapture = true,
                    activityCreateCount = 1,
                    activityDestroyCount = 0,
                    activityActiveCount = 1,
                    previousTeardownComplete = true,
                    captureFinalized = true,
                    detached = true,
                    drained = true,
                    finalListenerCount = 0,
                    diagnosticFailures = emptyList(),
                ),
            pipeline =
                kinds.mapIndexed { index, kind ->
                    Todo18PipelineEvent(
                        ordinal = index + 1L,
                        kind = kind,
                        sourceSequence =
                            if (
                                kind == Todo18PipelineEventKind.TASK1_PUBLICATION ||
                                    kind == Todo18PipelineEventKind.PRIMARY_DISPATCH_BEGIN ||
                                    kind == Todo18PipelineEventKind.PRIMARY_DISPATCH_RETURN ||
                                    kind == Todo18PipelineEventKind.DISPLAYED_SINK_RETURN ||
                                    kind == Todo18PipelineEventKind.SUBSCRIPTION_RECEIVE
                            ) {
                                1L
                            } else {
                                null
                            },
                        controllerIdentity =
                            when (kind) {
                                Todo18PipelineEventKind.CONTROLLER_ENTRY,
                                Todo18PipelineEventKind.CONTROLLER_TARGET_STATE,
                                Todo18PipelineEventKind.ROUTE_STATE_OBSERVED,
                                Todo18PipelineEventKind.DISPLAYED_CALLBACK_ENTRY,
                                Todo18PipelineEventKind.DISPLAYED_SINK_ENTRY,
                                Todo18PipelineEventKind.DISPLAYED_SINK_RETURN,
                                Todo18PipelineEventKind.DISPLAYED_CALLBACK_RETURN -> 41
                                else -> null
                            },
                        runtimeBinding =
                            OFFLINE_RUNTIME_BINDING.takeIf {
                                waitId == Todo18WaitId.OFFLINE_BEGIN_EDIT &&
                                    kind in OFFLINE_RUNTIME_EVENTS
                            },
                    )
                },
        )
    }

    fun Todo18DiagnosticReceipt.withKinds(
        vararg kinds: Todo18PipelineEventKind
    ): Todo18DiagnosticReceipt =
        copy(
            pipeline =
                kinds.mapIndexed { index, kind ->
                    Todo18PipelineEvent(
                        ordinal = index + 1L,
                        kind = kind,
                        controllerIdentity =
                            when (kind) {
                                Todo18PipelineEventKind.CONTROLLER_ENTRY,
                                Todo18PipelineEventKind.CONTROLLER_TARGET_STATE,
                                Todo18PipelineEventKind.ROUTE_STATE_OBSERVED,
                                Todo18PipelineEventKind.DISPLAYED_CALLBACK_ENTRY,
                                Todo18PipelineEventKind.DISPLAYED_SINK_ENTRY,
                                Todo18PipelineEventKind.DISPLAYED_SINK_RETURN,
                                Todo18PipelineEventKind.DISPLAYED_CALLBACK_RETURN -> 41
                                else -> null
                            },
                        runtimeBinding =
                            OFFLINE_RUNTIME_BINDING.takeIf {
                                envelope.waitId == Todo18WaitId.OFFLINE_BEGIN_EDIT &&
                                    kind in OFFLINE_RUNTIME_EVENTS
                            },
                    )
                }
        )

    private val OFFLINE_RUNTIME_BINDING =
        Todo18RuntimeBinding(
            controllerIdentity = "41",
            controllerEpoch = 3L,
            controllerGeneration = 7L,
            collectorGeneration = 11L,
            callbackGeneration = 12L,
            attachGeneration = 10L,
            disposeGeneration = 9L,
            lifecycleOwnerIdentity = "lifecycle-1",
            lifecycleState = "RESUMED",
            activityIdentity = "activity-1",
            navHostIdentity = "nav-host-1",
            callbackSinkIdentity = "sink-1",
        )
    private val OFFLINE_RUNTIME_EVENTS =
        setOf(
            Todo18PipelineEventKind.ROUTE_STATE_OBSERVED,
            Todo18PipelineEventKind.DISPLAYED_CALLBACK_ENTRY,
            Todo18PipelineEventKind.DISPLAYED_SINK_ENTRY,
            Todo18PipelineEventKind.DISPLAYED_SINK_RETURN,
            Todo18PipelineEventKind.DISPLAYED_CALLBACK_RETURN,
        )

    const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    const val HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
}
