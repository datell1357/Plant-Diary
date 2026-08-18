package com.planterior.helper.feature.collection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.core.graphics.scale
import com.google.android.gms.tasks.Task
import com.google.firebase.storage.FirebaseStorage
import com.planterior.helper.core.designsystem.icon.PlanteriorIcons
import com.planterior.helper.core.designsystem.theme.PlanteriorRadius
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal const val MAX_THUMBNAIL_SOURCE_BYTES = 8L * 1024L * 1024L
internal const val THUMBNAIL_TARGET_PIXELS = 144
private const val THUMBNAIL_CACHE_BYTES = 8 * 1024 * 1024

fun interface PlantThumbnailLoader {
    suspend fun load(path: String): Bitmap
}

fun interface ThumbnailByteSource {
    suspend fun load(path: String, maximumBytes: Long): ByteArray
}

object PlaceholderPlantThumbnailLoader : PlantThumbnailLoader {
    override suspend fun load(path: String): Bitmap = error("No thumbnail source is configured")
}

class FirebasePlantThumbnailLoader(storage: FirebaseStorage = FirebaseStorage.getInstance()) :
    PlantThumbnailLoader by CachedPlantThumbnailLoader(
        ThumbnailByteSource { path, maximumBytes ->
            storage.reference.child(path).getBytes(maximumBytes).await()
        }
    )

class CachedPlantThumbnailLoader(
    private val source: ThumbnailByteSource,
    cacheBytes: Int = THUMBNAIL_CACHE_BYTES,
) : PlantThumbnailLoader {
    private val cache =
        object : LruCache<String, Bitmap>(cacheBytes) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }

    override suspend fun load(path: String): Bitmap {
        cache.get(path)?.let {
            return it
        }
        val bytes = source.load(path, MAX_THUMBNAIL_SOURCE_BYTES)
        require(bytes.isNotEmpty() && bytes.size <= MAX_THUMBNAIL_SOURCE_BYTES)
        val bitmap = withContext(Dispatchers.Default) { decodeBounded(bytes) }
        cache.put(path, bitmap)
        return bitmap
    }

    private fun decodeBounded(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= THUMBNAIL_TARGET_PIXELS &&
                bounds.outHeight / (sample * 2) >= THUMBNAIL_TARGET_PIXELS
        ) {
            sample *= 2
        }
        val decoded =
            requireNotNull(
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            )
        if (decoded.width <= THUMBNAIL_TARGET_PIXELS && decoded.height <= THUMBNAIL_TARGET_PIXELS) {
            return decoded
        }
        val scale =
            minOf(
                THUMBNAIL_TARGET_PIXELS.toFloat() / decoded.width,
                THUMBNAIL_TARGET_PIXELS.toFloat() / decoded.height,
            )
        val scaled =
            decoded.scale(
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
            )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }
}

private sealed interface ThumbnailState {
    data object NoPhoto : ThumbnailState

    data object Loading : ThumbnailState

    data class Loaded(val bitmap: Bitmap) : ThumbnailState

    data object Failed : ThumbnailState
}

@Composable
internal fun PlantThumbnail(path: String?, name: String, loader: PlantThumbnailLoader) {
    val thumbnail by
        produceState<ThumbnailState>(
            initialValue = if (path == null) ThumbnailState.NoPhoto else ThumbnailState.Loading,
            key1 = path,
            key2 = loader,
        ) {
            if (path == null) {
                value = ThumbnailState.NoPhoto
                return@produceState
            }
            value = ThumbnailState.Loading
            value =
                try {
                    ThumbnailState.Loaded(loader.load(path))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    ThumbnailState.Failed
                }
        }
    val size = PlanteriorTheme.spacing.huge * 3
    val shape = RoundedCornerShape(PlanteriorRadius.Card)
    when (val current = thumbnail) {
        ThumbnailState.Loading ->
            Box(
                modifier =
                    Modifier.size(size)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .testTag(CollectionTestTags.THUMBNAIL_LOADING),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(PlanteriorTheme.spacing.huge))
            }
        is ThumbnailState.Loaded ->
            Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = "$name 대표 이미지",
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier.size(size).clip(shape).testTag(CollectionTestTags.THUMBNAIL_IMAGE),
            )
        ThumbnailState.Failed ->
            ThumbnailPlaceholder(
                name = name,
                modifier = Modifier.size(size).testTag(CollectionTestTags.THUMBNAIL_FAILURE),
                failure = true,
            )
        ThumbnailState.NoPhoto ->
            ThumbnailPlaceholder(name = name, modifier = Modifier.size(size), failure = false)
    }
}

@Composable
private fun ThumbnailPlaceholder(name: String, modifier: Modifier, failure: Boolean) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(PlanteriorRadius.Card))
                .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = PlanteriorIcons.Collection,
            contentDescription = if (failure) "$name 대표 이미지를 불러오지 못함" else "$name 대표 이미지 없음",
            tint = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier.size(PlanteriorTheme.spacing.huge)
                    .testTag(CollectionTestTags.THUMBNAIL_PLACEHOLDER),
        )
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel(CancellationException("Firebase task cancelled")) }
}
