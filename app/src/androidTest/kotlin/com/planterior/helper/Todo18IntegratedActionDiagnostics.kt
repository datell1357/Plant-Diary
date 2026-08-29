package com.planterior.helper

import com.planterior.helper.feature.minihome.MiniHomeSaveActionDiagnostics
import com.planterior.helper.feature.watering.WateringConfirmActionDiagnostics
import java.io.Closeable

internal class Todo18IntegratedActionDiagnostics {
    val recorder = Todo18IntegratedActionRecorder()
    private var miniHomeInstallation: Closeable? = null
    private var wateringInstallation: Closeable? = null

    fun install() {
        check(recorder.isFresh()) { "Todo18 action recorder was not fresh" }
        check(detached) { "Todo18 action diagnostics leaked from a prior test" }
        miniHomeInstallation = MiniHomeSaveActionDiagnostics.install(recorder::record)
        try {
            wateringInstallation = WateringConfirmActionDiagnostics.install(recorder::record)
        } catch (failure: Throwable) {
            miniHomeInstallation?.close()
            miniHomeInstallation = null
            throw failure
        }
    }

    fun close() {
        try {
            wateringInstallation?.close()
        } finally {
            wateringInstallation = null
            miniHomeInstallation?.close()
            miniHomeInstallation = null
        }
    }

    fun listenerCount(): Int =
        MiniHomeSaveActionDiagnostics.listenerCount() +
            WateringConfirmActionDiagnostics.listenerCount()

    val detached: Boolean
        get() =
            MiniHomeSaveActionDiagnostics.listenerCount() == 0 &&
                WateringConfirmActionDiagnostics.listenerCount() == 0
}
