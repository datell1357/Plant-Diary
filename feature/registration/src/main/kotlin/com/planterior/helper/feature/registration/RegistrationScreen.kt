package com.planterior.helper.feature.registration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.planterior.helper.core.designsystem.component.PlanteriorCard
import com.planterior.helper.core.designsystem.component.PlanteriorScreenScaffold
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.PersonalPlantId

object RegistrationTestTags {
    const val SCREEN = "registration.screen"
    const val NAME = "registration.name"
    const val LAST_WATERED = "registration.last-watered"
    const val SEARCH = "registration.search"
    const val SEARCH_ACTION = "registration.search-action"
    const val SUBMIT = "registration.submit"
    const val SAVED = "registration.saved"

    fun content(contentId: String) = "registration.content:$contentId"

    fun existing(plantId: String) = "registration.existing:$plantId"
}

@Composable
fun RegistrationScreen(
    state: RegistrationUiState,
    identifiedRequestId: String?,
    onName: (String) -> Unit,
    onDate: (String?) -> Unit,
    onSearch: (String) -> Unit,
    onSelectContent: (RegistrationContent) -> Unit,
    onUseIdentificationPhoto: (Boolean) -> Unit,
    onPickPhoto: () -> Unit,
    onSubmit: () -> Unit,
    onOpenExisting: (PersonalPlantId) -> Unit,
    onAddAnother: () -> Unit,
    onCancelDuplicate: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    PlanteriorScreenScaffold(
        title = stringResource(R.string.registration_title),
        modifier = Modifier.testTag(RegistrationTestTags.SCREEN),
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
        ) {
            when (state) {
                RegistrationUiState.LoadingSession -> CircularProgressIndicator()
                is RegistrationUiState.Editing ->
                    EditingContent(
                        state,
                        identifiedRequestId,
                        onName,
                        onDate,
                        onSearch,
                        onSelectContent,
                        onUseIdentificationPhoto,
                        onPickPhoto,
                        onSubmit,
                        onRetry,
                    )
                is RegistrationUiState.SessionFailed -> {
                    Text(
                        stringResource(R.string.registration_profile_failed),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.registration_retry))
                    }
                }
                is RegistrationUiState.CheckingDuplicates,
                is RegistrationUiState.Saving -> {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.registration_saving))
                }
                is RegistrationUiState.DuplicateFound -> {
                    Text(
                        stringResource(R.string.registration_duplicate_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.existing.forEach { existing ->
                        PlanteriorCard(
                            onClick = { onOpenExisting(existing.id) },
                            modifier =
                                Modifier.testTag(RegistrationTestTags.existing(existing.id.value)),
                        ) {
                            Text(existing.displayName)
                            Text(stringResource(R.string.registration_open_existing))
                        }
                    }
                    Button(onClick = onAddAnother, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.registration_add_another))
                    }
                    OutlinedButton(
                        onClick = onCancelDuplicate,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.registration_duplicate_cancel))
                    }
                }
                is RegistrationUiState.SaveFailed -> {
                    Text(
                        stringResource(R.string.registration_save_failed),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.registration_retry))
                    }
                }
                is RegistrationUiState.Completed ->
                    Text(
                        stringResource(R.string.registration_saved),
                        modifier = Modifier.testTag(RegistrationTestTags.SAVED),
                    )
            }
            if (
                state is RegistrationUiState.Editing ||
                    state is RegistrationUiState.SessionFailed ||
                    state is RegistrationUiState.SaveFailed ||
                    state is RegistrationUiState.LoadingSession
            ) {
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.registration_cancel))
                }
            }
        }
    }
}

@Composable
private fun EditingContent(
    state: RegistrationUiState.Editing,
    identifiedRequestId: String?,
    onName: (String) -> Unit,
    onDate: (String?) -> Unit,
    onSearch: (String) -> Unit,
    onSelectContent: (RegistrationContent) -> Unit,
    onUseIdentificationPhoto: (Boolean) -> Unit,
    onPickPhoto: () -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
) {
    var searchQuery by rememberSaveable { mutableStateOf(state.draft.name) }
    OutlinedTextField(
        value = state.draft.name,
        onValueChange = {
            searchQuery = it
            onName(it)
        },
        label = { Text(stringResource(R.string.registration_name)) },
        modifier = Modifier.fillMaxWidth().testTag(RegistrationTestTags.NAME),
        singleLine = true,
    )
    state.failure?.let {
        Text(
            stringResource(R.string.registration_duplicate_failed),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.registration_retry))
        }
    }
    state.errors.forEach { error ->
        Text(
            text =
                stringResource(
                    when (error) {
                        RegistrationValidationError.NAME_REQUIRED ->
                            R.string.registration_name_required
                        RegistrationValidationError.NAME_TOO_LONG -> R.string.registration_name_long
                        RegistrationValidationError.INVALID_LAST_WATERED_DATE ->
                            R.string.registration_date_invalid
                        RegistrationValidationError.FUTURE_LAST_WATERED_DATE ->
                            R.string.registration_date_future
                    }
                ),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text(stringResource(R.string.registration_search)) },
            modifier = Modifier.weight(1f).testTag(RegistrationTestTags.SEARCH),
            singleLine = true,
        )
        OutlinedButton(
            onClick = { onSearch(searchQuery) },
            modifier = Modifier.heightIn(min = 48.dp).testTag(RegistrationTestTags.SEARCH_ACTION),
        ) {
            Text(stringResource(R.string.registration_search_action))
        }
    }
    when (val search = state.search) {
        RegistrationSearchState.Empty -> Text(stringResource(R.string.registration_search_empty))
        RegistrationSearchState.Failed -> Text(stringResource(R.string.registration_search_failed))
        RegistrationSearchState.Loading -> CircularProgressIndicator()
        RegistrationSearchState.Idle -> Unit
        is RegistrationSearchState.Results ->
            search.items.forEach { item ->
                PlanteriorCard(
                    onClick = { onSelectContent(item) },
                    modifier = Modifier.testTag(RegistrationTestTags.content(item.id.value)),
                ) {
                    Text(item.name)
                }
            }
    }
    OutlinedTextField(
        value = state.draft.lastWateredDate.orEmpty(),
        onValueChange = { onDate(it) },
        label = { Text(stringResource(R.string.registration_last_watered)) },
        modifier = Modifier.fillMaxWidth().testTag(RegistrationTestTags.LAST_WATERED),
        singleLine = true,
    )
    if (identifiedRequestId != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.draft.photo is RepresentativePhoto.IdentificationOriginal,
                onCheckedChange = onUseIdentificationPhoto,
            )
            Text(stringResource(R.string.registration_identification_photo))
        }
    } else {
        val isPhotoSelected = state.draft.photo is RepresentativePhoto.Prepared
        Text(stringResource(R.string.registration_photo_disclosure))
        OutlinedButton(
            onClick = onPickPhoto,
            modifier = Modifier.fillMaxWidth().semantics { selected = isPhotoSelected },
        ) {
            Text(
                stringResource(
                    if (isPhotoSelected) R.string.registration_photo_selected
                    else R.string.registration_photo
                )
            )
        }
        state.draft.photoError?.let {
            Text(
                stringResource(R.string.registration_photo_invalid),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
    Button(
        onClick = onSubmit,
        modifier = Modifier.fillMaxWidth().testTag(RegistrationTestTags.SUBMIT),
    ) {
        Text(stringResource(R.string.registration_submit))
    }
}
