package com.planterior.helper.feature.camera

internal fun todo18DebugCameraPermission(actual: CameraPermission): CameraPermission = actual

internal fun todo18DebugPhotoPickerUri(): String? = null

internal suspend fun todo18DebugObservePhotoPreparation(
    uri: String,
    prepareAndApply: suspend () -> Boolean,
): Boolean = prepareAndApply()
