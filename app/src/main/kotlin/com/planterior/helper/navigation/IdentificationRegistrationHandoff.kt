package com.planterior.helper.navigation

import android.os.Bundle
import androidx.compose.runtime.saveable.Saver
import com.planterior.helper.core.model.IdentificationRequestId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.feature.identify.ConfirmedIdentification
import com.planterior.helper.feature.identify.IdentificationCandidate

internal class IdentificationRegistrationHandoff {
    var confirmed: ConfirmedIdentification? = null
        private set

    fun accept(value: ConfirmedIdentification) {
        confirmed = value
    }

    fun clear() {
        confirmed = null
    }

    companion object {
        val Saver: Saver<IdentificationRegistrationHandoff, Bundle> =
            Saver(
                save = { handoff ->
                    Bundle().apply {
                        handoff.confirmed?.let { confirmed ->
                            putString("requestId", confirmed.requestId.value)
                            putString("contentId", confirmed.candidate.publicContentId.value)
                            putString("koreanName", confirmed.candidate.koreanName)
                            putString("commonName", confirmed.candidate.commonName)
                            putString("scientificName", confirmed.candidate.scientificName)
                            putDouble("confidence", confirmed.candidate.confidence)
                            putString("thumbnailUrl", confirmed.candidate.thumbnailUrl)
                        }
                    }
                },
                restore = { saved ->
                    IdentificationRegistrationHandoff().apply {
                        saved.getString("requestId")?.let { requestId ->
                            accept(
                                ConfirmedIdentification(
                                    requestId = IdentificationRequestId(requestId),
                                    candidate =
                                        IdentificationCandidate(
                                            publicContentId =
                                                PlantContentId(
                                                    requireNotNull(saved.getString("contentId"))
                                                ),
                                            koreanName = saved.getString("koreanName"),
                                            commonName = saved.getString("commonName"),
                                            scientificName =
                                                requireNotNull(saved.getString("scientificName")),
                                            confidence = saved.getDouble("confidence"),
                                            thumbnailUrl = saved.getString("thumbnailUrl"),
                                        ),
                                )
                            )
                        }
                    }
                },
            )
    }
}
