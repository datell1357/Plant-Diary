package com.planterior.helper.registration

import android.content.Context
import androidx.core.content.edit
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.feature.home.HomeSession
import com.planterior.helper.feature.registration.ExistingPersonalPlant
import com.planterior.helper.feature.registration.RegistrationContent
import com.planterior.helper.feature.registration.RegistrationRepository
import com.planterior.helper.feature.registration.RegistrationSession
import com.planterior.helper.home.debugHomeSessions
import kotlinx.coroutines.flow.first

fun debugRegistrationRepository(
    context: Context,
    fallback: RegistrationRepository,
): RegistrationRepository {
    val sessions = debugHomeSessions(context) ?: return fallback
    return object : RegistrationRepository by fallback {
        override suspend fun session(): RegistrationSession {
            val session = sessions.first() as? HomeSession.SignedIn ?: return fallback.session()
            return RegistrationSession(AccountId(session.accountUid), session.zoneId)
        }

        override suspend fun searchPublicContents(query: String): List<RegistrationContent> {
            val fixture = duplicateFixture(context) ?: return fallback.searchPublicContents(query)
            return listOf(
                RegistrationContent(PlantContentId(fixture.contentId), fixture.contentName)
            )
        }

        override suspend fun findDuplicates(
            accountId: AccountId,
            contentId: PlantContentId,
            excluding: PersonalPlantId,
        ): List<ExistingPersonalPlant> {
            val fixture =
                duplicateFixture(context)
                    ?: return fallback.findDuplicates(
                        accountId,
                        contentId,
                        excluding,
                    )
            if (contentId.value != fixture.contentId) return emptyList()
            return listOf(
                ExistingPersonalPlant(
                    PersonalPlantId(fixture.existingPlantId),
                    fixture.existingPlantName,
                )
            )
        }
    }
}

fun setDebugRegistrationDuplicateFixture(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
        putBoolean(DUPLICATE_ENABLED, enabled)
    }
}

private fun duplicateFixture(context: Context): DuplicateFixture? =
    context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(DUPLICATE_ENABLED, false)
        .takeIf { it }
        ?.let {
            DuplicateFixture(
                contentId = "species-registration-lifecycle",
                contentName = "회전 몬스테라",
                existingPlantId = "existing-registration-lifecycle",
                existingPlantName = "기존 회전 몬스테라",
            )
        }

private data class DuplicateFixture(
    val contentId: String,
    val contentName: String,
    val existingPlantId: String,
    val existingPlantName: String,
)

private const val PREFERENCES = "registration-qa"
private const val DUPLICATE_ENABLED = "duplicate-enabled"
