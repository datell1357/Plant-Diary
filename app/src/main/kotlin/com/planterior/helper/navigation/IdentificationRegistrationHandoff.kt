package com.planterior.helper.navigation

import com.planterior.helper.feature.identify.ConfirmedIdentification

internal class IdentificationRegistrationHandoff {
    var confirmed: ConfirmedIdentification? = null
        private set

    fun accept(value: ConfirmedIdentification) {
        confirmed = value
    }

    fun clear() {
        confirmed = null
    }
}
