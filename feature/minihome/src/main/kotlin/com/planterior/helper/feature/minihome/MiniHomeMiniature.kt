package com.planterior.helper.feature.minihome

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.planterior.helper.core.designsystem.theme.PlanteriorRadius
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.CatalogMediaIdentity
import kotlin.math.absoluteValue
import kotlinx.coroutines.CancellationException

sealed interface MiniHomePhotoRequest {
    data class PersonalPlant(val path: String) : MiniHomePhotoRequest

    data class Catalog(val identity: CatalogMediaIdentity) : MiniHomePhotoRequest
}

fun interface MiniHomePhotoLoader {
    suspend fun load(request: MiniHomePhotoRequest): Bitmap
}

object PlaceholderMiniHomePhotoLoader : MiniHomePhotoLoader {
    override suspend fun load(request: MiniHomePhotoRequest): Bitmap =
        error("No mini-home photo source is configured")
}

internal sealed interface MiniHomePhotoState {
    data object None : MiniHomePhotoState

    data object Loading : MiniHomePhotoState

    data class Loaded(val bitmap: Bitmap) : MiniHomePhotoState

    data object Failed : MiniHomePhotoState
}

@Composable
internal fun rememberMiniHomePhoto(
    request: MiniHomePhotoRequest?,
    loader: MiniHomePhotoLoader,
): MiniHomePhotoState {
    val state by
        produceState<MiniHomePhotoState>(
            initialValue =
                if (request == null) MiniHomePhotoState.None else MiniHomePhotoState.Loading,
            key1 = request,
            key2 = loader,
        ) {
            if (request == null) {
                value = MiniHomePhotoState.None
                return@produceState
            }
            value = MiniHomePhotoState.Loading
            value =
                try {
                    MiniHomePhotoState.Loaded(loader.load(request))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    MiniHomePhotoState.Failed
                }
        }
    return state
}

@Composable
internal fun PlantMiniature(
    identity: String,
    name: String,
    representativePhotoPath: String?,
    photoLoader: MiniHomePhotoLoader,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val photo =
        rememberMiniHomePhoto(
            representativePhotoPath?.let(MiniHomePhotoRequest::PersonalPlant),
            photoLoader,
        )
    val leaf = MaterialTheme.colorScheme.primary
    val leafHighlight = MaterialTheme.colorScheme.primaryContainer
    val pot = MaterialTheme.colorScheme.onSurfaceVariant
    val potHighlight = MaterialTheme.colorScheme.surface
    val shadow = MaterialTheme.colorScheme.onSurface
    val stableVariant = (identity + name).hashCode().absoluteValue % 3
    Box(
        modifier = modifier.size(width, height).semantics { hideFromAccessibility() },
        contentAlignment = Alignment.TopCenter,
    ) {
        if (photo is MiniHomePhotoState.Loaded) {
            Image(
                bitmap = photo.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width * 0.78f).clip(CircleShape).background(leafHighlight),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            if (photo !is MiniHomePhotoState.Loaded) {
                drawPlantLeaves(stableVariant, leaf, leafHighlight)
            } else {
                drawLine(
                    leaf,
                    Offset(size.width * 0.5f, size.height * 0.48f),
                    Offset(size.width * 0.5f, size.height * 0.72f),
                    strokeWidth = size.width * 0.07f,
                )
            }
            drawPot(pot, potHighlight, shadow)
        }
    }
}

private fun DrawScope.drawPlantLeaves(variant: Int, leaf: Color, highlight: Color) {
    val stemTop = if (variant == 1) 0.08f else 0.22f
    drawLine(
        leaf,
        Offset(size.width * 0.5f, size.height * stemTop),
        Offset(size.width * 0.5f, size.height * 0.74f),
        strokeWidth = size.width * 0.07f,
    )
    when (variant) {
        0 -> {
            listOf(-34f to 0.20f, 30f to 0.31f, -28f to 0.43f, 25f to 0.52f).forEachIndexed {
                index,
                (angle, y) ->
                rotate(angle, Offset(size.width * 0.5f, size.height * y)) {
                    drawOval(
                        if (index % 2 == 0) leaf else highlight,
                        topLeft = Offset(size.width * 0.17f, size.height * (y - 0.10f)),
                        size = Size(size.width * 0.5f, size.height * 0.20f),
                    )
                }
            }
        }
        1 -> {
            listOf(0.17f, 0.34f, 0.51f, 0.68f).forEachIndexed { index, x ->
                rotate((index - 1.5f) * 10f, Offset(size.width * x, size.height * 0.46f)) {
                    drawOval(
                        if (index % 2 == 0) leaf else highlight,
                        topLeft = Offset(size.width * (x - 0.11f), size.height * 0.08f),
                        size = Size(size.width * 0.22f, size.height * 0.54f),
                    )
                }
            }
        }
        else -> {
            drawOval(
                leaf,
                topLeft = Offset(size.width * 0.18f, size.height * 0.16f),
                size = Size(size.width * 0.38f, size.height * 0.25f),
            )
            drawOval(
                highlight,
                topLeft = Offset(size.width * 0.46f, size.height * 0.22f),
                size = Size(size.width * 0.36f, size.height * 0.24f),
            )
            drawLine(
                leaf,
                Offset(size.width * 0.34f, size.height * 0.35f),
                Offset(size.width * 0.23f, size.height * 0.69f),
                strokeWidth = size.width * 0.06f,
            )
            drawLine(
                leaf,
                Offset(size.width * 0.65f, size.height * 0.40f),
                Offset(size.width * 0.77f, size.height * 0.69f),
                strokeWidth = size.width * 0.06f,
            )
        }
    }
}

private fun DrawScope.drawPot(pot: Color, highlight: Color, shadow: Color) {
    drawOval(
        color = shadow.copy(alpha = 0.13f),
        topLeft = Offset(size.width * 0.22f, size.height * 0.94f),
        size = Size(size.width * 0.56f, size.height * 0.06f),
    )
    drawRoundRect(
        pot,
        topLeft = Offset(size.width * 0.29f, size.height * 0.73f),
        size = Size(size.width * 0.42f, size.height * 0.23f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.08f),
    )
    drawRect(
        highlight.copy(alpha = 0.45f),
        topLeft = Offset(size.width * 0.34f, size.height * 0.76f),
        size = Size(size.width * 0.08f, size.height * 0.16f),
    )
}

@Composable
internal fun DecorationMiniature(
    identity: String,
    name: String,
    mediaIdentity: CatalogMediaIdentity?,
    photoLoader: MiniHomePhotoLoader,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val photo =
        rememberMiniHomePhoto(mediaIdentity?.let(MiniHomePhotoRequest::Catalog), photoLoader)
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val detail = MaterialTheme.colorScheme.onSurfaceVariant
    val variant =
        when {
            name.contains("스탠드", ignoreCase = true) ||
                name.contains("조명", ignoreCase = true) ||
                name.contains("lamp", ignoreCase = true) -> 0
            name.contains("테이블", ignoreCase = true) ||
                name.contains("탁자", ignoreCase = true) ||
                name.contains("table", ignoreCase = true) -> 1
            name.contains("러그", ignoreCase = true) ||
                name.contains("매트", ignoreCase = true) ||
                name.contains("rug", ignoreCase = true) -> 2
            else -> identity.hashCode().absoluteValue % 3
        }
    Box(
        modifier = modifier.size(width, height).semantics { hideFromAccessibility() },
        contentAlignment = Alignment.Center,
    ) {
        if (photo is MiniHomePhotoState.Loaded) {
            Image(
                bitmap = photo.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier.fillMaxSize()
                        .clip(RoundedCornerShape(PlanteriorRadius.Small))
                        .testTag("mini-home:item-media:$identity"),
            )
        }
        if (photo !is MiniHomePhotoState.Loaded)
            Canvas(Modifier.fillMaxSize().testTag("mini-home:item-fallback:$identity")) {
                drawOval(
                    detail.copy(alpha = 0.13f),
                    topLeft = Offset(size.width * 0.12f, size.height * 0.93f),
                    size = Size(size.width * 0.76f, size.height * 0.07f),
                )
                when (variant) {
                    0 -> {
                        drawLine(
                            detail,
                            Offset(size.width * 0.5f, size.height * 0.28f),
                            Offset(size.width * 0.5f, size.height * 0.88f),
                            strokeWidth = size.width * 0.08f,
                        )
                        drawOval(
                            detail,
                            topLeft = Offset(size.width * 0.26f, size.height * 0.82f),
                            size = Size(size.width * 0.48f, size.height * 0.12f),
                        )
                        drawRoundRect(
                            primary,
                            topLeft = Offset(size.width * 0.18f, size.height * 0.13f),
                            size = Size(size.width * 0.64f, size.height * 0.30f),
                            cornerRadius =
                                androidx.compose.ui.geometry.CornerRadius(size.width * 0.18f),
                        )
                    }
                    1 -> {
                        drawRoundRect(
                            primary,
                            topLeft = Offset(size.width * 0.08f, size.height * 0.46f),
                            size = Size(size.width * 0.84f, size.height * 0.20f),
                            cornerRadius =
                                androidx.compose.ui.geometry.CornerRadius(size.width * 0.08f),
                        )
                        listOf(0.20f, 0.80f).forEach { x ->
                            drawLine(
                                detail,
                                Offset(size.width * x, size.height * 0.62f),
                                Offset(size.width * x, size.height * 0.91f),
                                strokeWidth = size.width * 0.08f,
                            )
                        }
                    }
                    else -> {
                        rotate(7f, center) {
                            drawRoundRect(
                                primary,
                                topLeft = Offset(size.width * 0.08f, size.height * 0.60f),
                                size = Size(size.width * 0.84f, size.height * 0.28f),
                                cornerRadius =
                                    androidx.compose.ui.geometry.CornerRadius(size.width * 0.10f),
                            )
                            drawRoundRect(
                                surface,
                                topLeft = Offset(size.width * 0.25f, size.height * 0.67f),
                                size = Size(size.width * 0.50f, size.height * 0.10f),
                                cornerRadius =
                                    androidx.compose.ui.geometry.CornerRadius(size.width * 0.05f),
                            )
                        }
                    }
                }
            }
    }
}

@Composable
internal fun MiniHomeBackground(
    choice: MiniHomeDecorationChoice?,
    photoLoader: MiniHomePhotoLoader,
    modifier: Modifier = Modifier,
) {
    if (choice == null) return
    val photo =
        rememberMiniHomePhoto(
            choice.mediaIdentity?.let(MiniHomePhotoRequest::Catalog),
            photoLoader,
        )
    val base = MaterialTheme.colorScheme.primaryContainer
    val detail = MaterialTheme.colorScheme.primary
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(PlanteriorRadius.Card))
                .background(base)
                .testTag(
                    if (photo is MiniHomePhotoState.Loaded) {
                        "mini-home:background-media:${choice.id.value}"
                    } else {
                        "mini-home:background-fallback:${choice.id.value}"
                    }
                )
                .semantics { hideFromAccessibility() }
    ) {
        if (photo is MiniHomePhotoState.Loaded) {
            Image(
                bitmap = photo.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val offset = (choice.id.value.hashCode().absoluteValue % 5 + 2).toFloat()
                drawRect(base)
                var x = -size.height
                while (x < size.width) {
                    drawLine(
                        detail.copy(alpha = 0.10f),
                        Offset(x, size.height),
                        Offset(x + size.height, 0f),
                        strokeWidth = offset,
                    )
                    x += size.width / 6f
                }
            }
        }
    }
}

@Composable
internal fun PickerIdentity(
    plant: MiniHomePlantChoice?,
    decoration: MiniHomeDecorationChoice?,
    photoLoader: MiniHomePhotoLoader,
) {
    val width = PlanteriorTheme.spacing.huge * 2
    val height = PlanteriorTheme.spacing.huge * 2
    Box(
        Modifier.size(width, height)
            .clip(RoundedCornerShape(PlanteriorRadius.Small))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (plant != null) {
            PlantMiniature(
                identity = plant.id.value,
                name = plant.displayName,
                representativePhotoPath = plant.representativePhotoPath,
                photoLoader = photoLoader,
                width = width * 0.78f,
                height = height * 0.92f,
            )
        } else if (decoration != null) {
            DecorationMiniature(
                identity = decoration.id.value,
                name = decoration.displayName,
                mediaIdentity = decoration.mediaIdentity,
                photoLoader = photoLoader,
                width = width * 0.78f,
                height = height * 0.82f,
            )
        }
    }
}
