package com.planterior.helper.feature.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.planterior.helper.core.designsystem.component.PlanteriorCard
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme

object CollectionTestTags {
    const val LOADING = "collection:loading"
    const val CONTENT = "collection:content"
    const val ITEM = "collection:item"
    const val EMPTY = "collection:empty"
    const val ERROR = "collection:error"
    const val STALE = "collection:stale"
    const val LAST_REFRESH = "collection:last-refresh"
    const val STALE_RETRY = "collection:stale-retry"
    const val IDENTIFY = "collection:identify"
    const val REGISTER_DIRECT = "collection:register-direct"
    const val RETRY = "collection:retry"
    const val THUMBNAIL_LOADING = "collection:thumbnail-loading"
    const val THUMBNAIL_IMAGE = "collection:thumbnail-image"
    const val THUMBNAIL_FAILURE = "collection:thumbnail-failure"
    const val THUMBNAIL_PLACEHOLDER = "collection:thumbnail-placeholder"
}

object PlantDetailTestTags {
    const val LOADING = "plant-detail:loading"
    const val PARTIAL = "plant-detail:partial"
    const val STALE = "plant-detail:stale"
    const val NO_STANDARD = "plant-detail:no-standard"
    const val FORBIDDEN = "plant-detail:forbidden"
    const val NOT_FOUND = "plant-detail:not-found"
    const val ERROR = "plant-detail:error"
    const val RETRY = "plant-detail:retry"
    const val WATER = "plant-detail:water"
    const val LIGHT = "plant-detail:light"
    const val TEMPERATURE = "plant-detail:temperature"
    const val HUMIDITY = "plant-detail:humidity"
    const val HUMIDITY_MISSING = "plant-detail:humidity-missing"
    const val SYMPTOM = "plant-detail:symptom"
    const val SYMPTOM_CAUSE = "plant-detail:symptom-cause"
    const val SYMPTOM_ACTION = "plant-detail:symptom-action"
    const val SYMPTOMS_MISSING = "plant-detail:symptoms-missing"
    const val SYMPTOMS_EMPTY = "plant-detail:symptoms-empty"
    const val PRIVATE_NOTE = "plant-detail:private-note"
    const val EDIT = "plant-detail:edit"
    const val LAST_WATERED_INPUT = "plant-detail:last-watered-input"
    const val LOCATION_INPUT = "plant-detail:location-input"
    const val NOTE_INPUT = "plant-detail:note-input"
    const val DATE_INVALID_ERROR = "plant-detail:date-invalid-error"
    const val DATE_FUTURE_ERROR = "plant-detail:date-future-error"
    const val LOCATION_ERROR = "plant-detail:location-error"
    const val NOTE_ERROR = "plant-detail:note-error"
    const val SAVE = "plant-detail:save"
    const val EDIT_RETRY = "plant-detail:edit-retry"
    const val EDIT_RELOAD = "plant-detail:edit-reload"
    const val EDIT_FAILURE = "plant-detail:edit-failure"
}

@Composable
internal fun ColumnScope.LoadingState(tag: String) {
    Box(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
internal fun ColumnScope.CollectionStateBody(
    tag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .then(if (tag == null) Modifier else Modifier.testTag(tag)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
internal fun StatusCard(tag: String, title: String, body: String) {
    PlanteriorCard(
        modifier = Modifier.testTag(tag),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = PlanteriorTheme.spacing.medium),
        )
    }
}

@Composable
internal fun ColumnScope.SafeDetailState(tag: String, title: String, body: String) {
    CollectionStateBody { StatusCard(tag, title, body) }
}

@Composable
internal fun ColumnScope.ErrorState(
    tag: String,
    retryTag: String,
    title: String,
    body: String,
    onRetry: () -> Unit,
) {
    CollectionStateBody {
        StatusCard(tag, title, body)
        Button(
            onClick = onRetry,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(top = PlanteriorTheme.spacing.huge)
                    .sizeIn(minHeight = PlanteriorTheme.spacing.huge * 2)
                    .testTag(retryTag),
        ) {
            Text("다시 시도")
        }
    }
}
