package com.planterior.helper

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

internal fun todo18IntegratedActionReceiptFile(scenario: String): File {
    val directory =
        requireNotNull(
                InstrumentationRegistry.getInstrumentation()
                    .targetContext
                    .getExternalFilesDir("todo18-e2e-journeys")
            )
            .also { check(it.exists() || it.mkdirs()) }
    return File(directory, "$scenario-action-diagnostic.json")
}
