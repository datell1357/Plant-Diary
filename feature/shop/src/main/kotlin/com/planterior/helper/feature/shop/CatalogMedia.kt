package com.planterior.helper.feature.shop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.google.android.gms.tasks.Task
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.StreamDownloadTask
import com.planterior.helper.core.designsystem.icon.PlanteriorIcons
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.CatalogMediaIdentity
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

internal const val MAX_CATALOG_MEDIA_BYTES = 8L * 1024L * 1024L
internal const val CATALOG_MEDIA_TARGET_PIXELS = 768
internal const val MAX_CATALOG_MEDIA_DIMENSION = 32_768
internal const val MAX_CATALOG_MEDIA_SOURCE_PIXELS = 64L * 1024L * 1024L
internal const val MAX_CATALOG_MEDIA_SOURCE_DECODED_BYTES = 256L * 1024L * 1024L
internal const val MAX_CATALOG_MEDIA_DECODED_PIXELS =
    CATALOG_MEDIA_TARGET_PIXELS * CATALOG_MEDIA_TARGET_PIXELS
internal const val MAX_CATALOG_MEDIA_DECODED_BYTES = MAX_CATALOG_MEDIA_DECODED_PIXELS * 4L
internal const val MAX_CATALOG_MEDIA_ASPECT_RATIO = 32
private const val CATALOG_MEDIA_CACHE_BYTES = 16 * 1024 * 1024
private const val CATALOG_MEDIA_FALLBACK_CACHE_ENTRIES = 64
internal const val MAX_CATALOG_MEDIA_CONCURRENT_FLIGHTS = 4
internal const val MAX_CATALOG_MEDIA_IN_FLIGHT_BYTES =
    MAX_CATALOG_MEDIA_CONCURRENT_FLIGHTS * MAX_CATALOG_MEDIA_BYTES
internal const val MAX_CATALOG_MEDIA_CONCURRENT_DECODES = 2
private const val CATALOG_MEDIA_CLOSE_TIMEOUT_MILLIS = 2_000L
private const val CATALOG_MEDIA_LOADER_CLOSED_MESSAGE = "Catalog media loader closed"
private val CATALOG_MEDIA_PATH =
    Regex(
        "^catalog-assets/[A-Za-z0-9_-]{1,128}/[A-Za-z0-9][A-Za-z0-9_.-]{0,159}\\.(png|jpg|jpeg|webp)$"
    )

sealed interface CatalogMediaLoadResult {
    data class Loaded(val bitmap: Bitmap) : CatalogMediaLoadResult

    data class Fallback(val reason: CatalogMediaFallbackReason) : CatalogMediaLoadResult
}

enum class CatalogMediaFallbackReason {
    INVALID_PATH,
    DOWNLOAD_FAILED,
    EMPTY_PAYLOAD,
    ENCODED_SIZE_EXCEEDED,
    STORAGE_METADATA_INVALID,
    INVALID_HEADER,
    UNSUPPORTED_CONTENT_TYPE,
    DIMENSIONS_EXCEEDED,
    PIXEL_BUDGET_EXCEEDED,
    ASPECT_RATIO_EXCEEDED,
    METADATA_MISMATCH,
    INTEGRITY_MISMATCH,
    DECODE_FAILED,
    DECODED_BOUNDS_EXCEEDED,
    OUT_OF_MEMORY,
    NOT_CONFIGURED,
}

fun interface CatalogMediaLoader {
    suspend fun load(identity: CatalogMediaIdentity): CatalogMediaLoadResult
}

internal fun interface CatalogMediaByteSource {
    suspend fun load(path: String, maximumBytes: Long): ByteArray
}

data class CatalogMediaObjectMetadata(
    val contentType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val sha256: String? = null,
    val mediaRevision: Long? = null,
)

data class CatalogMediaPayload(
    val bytes: ByteArray,
    val metadata: CatalogMediaObjectMetadata? = null,
    val sha256: String = bytes.catalogSha256(),
)

fun interface CatalogMediaVerifiedSource {
    suspend fun load(path: String, maximumBytes: Long): CatalogMediaPayload
}

internal fun interface CatalogMediaPayloadSource {
    suspend fun load(path: String, maximumBytes: Long): CatalogMediaPayload
}

internal interface CatalogMediaTransfer {
    suspend fun read(maximumBytes: Long): CatalogMediaPayload

    fun cancel()
}

internal fun interface CatalogMediaTransferSource {
    fun open(path: String): CatalogMediaTransfer
}

internal data class CatalogMediaResourceLimits(
    val maxConcurrentFlights: Int = MAX_CATALOG_MEDIA_CONCURRENT_FLIGHTS,
    val maxInFlightBytes: Long = MAX_CATALOG_MEDIA_IN_FLIGHT_BYTES,
    val maxConcurrentDecodes: Int = MAX_CATALOG_MEDIA_CONCURRENT_DECODES,
) {
    init {
        require(maxConcurrentFlights > 0)
        require(maxInFlightBytes >= MAX_CATALOG_MEDIA_BYTES)
        require(maxInFlightBytes % MAX_CATALOG_MEDIA_BYTES == 0L)
        require(maxConcurrentDecodes > 0)
    }
}

internal interface CatalogMediaFlightLifecycleObserver {
    fun beforeSubscriberAttach(path: String) = Unit

    fun subscriberAttached(path: String, generation: Long, leaseId: Long) = Unit

    fun beforeSubscriberDetach(path: String, generation: Long, leaseId: Long) = Unit

    fun subscriberDetached(path: String, generation: Long, leaseId: Long) = Unit

    fun lastLeaseRemovedBeforeCancel(path: String, generation: Long) = Unit

    fun beforeCachePublish(path: String, generation: Long) = Unit

    fun cachePublishedBeforeCompletion(path: String, generation: Long) = Unit

    fun beforeTransferAttach(path: String, generation: Long) = Unit

    fun closeLinearizedBeforeCancellation() = Unit

    fun beforeCompletionCleanup(path: String, generation: Long) = Unit
}

private object NoOpCatalogMediaFlightLifecycleObserver : CatalogMediaFlightLifecycleObserver

internal data class CatalogMediaResourceSnapshot(
    val flights: Int,
    val subscribers: Int,
    val activeTransfers: Int,
    val reservedInFlightBytes: Long,
    val activeDecodes: Int,
    val peakActiveTransfers: Int,
    val peakReservedInFlightBytes: Long,
    val peakActiveDecodes: Int,
)

internal data class CatalogMediaBounds(
    val width: Int,
    val height: Int,
    val contentType: String,
)

internal fun interface CatalogImageBoundsReader {
    fun read(bytes: ByteArray): CatalogMediaBounds?
}

private object AndroidCatalogImageBoundsReader : CatalogImageBoundsReader {
    override fun read(bytes: ByteArray): CatalogMediaBounds? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return CatalogMediaBounds(
            width = options.outWidth,
            height = options.outHeight,
            contentType = detectCatalogMediaContentType(bytes) ?: return null,
        )
    }
}

internal fun interface CatalogBitmapDecoder {
    fun decode(bytes: ByteArray, sampleSize: Int): Bitmap?
}

private object AndroidCatalogBitmapDecoder : CatalogBitmapDecoder {
    override fun decode(bytes: ByteArray, sampleSize: Int): Bitmap? =
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = false
                inScaled = false
            },
        )
}

class FirebaseCatalogMediaLoader(storage: FirebaseStorage = FirebaseStorage.getInstance()) :
    CatalogMediaLoader, AutoCloseable {
    private val delegate =
        BoundedCatalogMediaLoader(
            transferSource =
                CatalogMediaTransferSource { path ->
                    FirebaseCatalogMediaTransfer(storage.reference.child(path), path)
                }
        )

    override suspend fun load(identity: CatalogMediaIdentity): CatalogMediaLoadResult =
        delegate.load(identity)

    override fun close() = delegate.close()
}

private class FirebaseCatalogMediaTransfer(
    private val reference: StorageReference,
    private val path: String,
) : CatalogMediaTransfer {
    private val cancelled = AtomicBoolean(false)
    private val activeTask = AtomicReference<StreamDownloadTask?>()
    private val activeStream = AtomicReference<InputStream?>()

    override suspend fun read(maximumBytes: Long): CatalogMediaPayload {
        currentCoroutineContext().ensureActive()
        ensureNotCancelled()
        val remote = reference.metadata.await()
        val metadata =
            CatalogMediaObjectMetadata(
                contentType = remote.contentType.orEmpty(),
                sizeBytes = remote.sizeBytes,
                width = remote.getCustomMetadata("width")?.toIntOrNull() ?: 0,
                height = remote.getCustomMetadata("height")?.toIntOrNull() ?: 0,
                sha256 = remote.getCustomMetadata("sha256"),
                mediaRevision = remote.getCustomMetadata("mediaRevision")?.toLongOrNull(),
            )
        val metadataFailure = validateCatalogMediaMetadata(path, metadata)
        if (metadataFailure != null) throw CatalogMediaSourceException(metadataFailure)
        ensureNotCancelled()

        val downloaded = AtomicReference<CatalogMediaDownloadedBytes?>()
        val processorFailure = AtomicReference<IOException?>()
        val task = reference.getStream { _, stream ->
            activeStream.set(stream)
            try {
                downloaded.set(
                    stream.use {
                        readCatalogMediaStreamWithDigest(
                            input = it,
                            maximumBytes = maximumBytes,
                            cancelled = cancelled,
                        )
                    }
                )
            } catch (error: IOException) {
                processorFailure.set(error)
                throw error
            } finally {
                activeStream.compareAndSet(stream, null)
            }
        }
        check(activeTask.compareAndSet(null, task))
        if (cancelled.get()) task.cancel()
        try {
            task.await(::cancel)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (processorFailure.get() is CatalogMediaStreamLimitException) {
                throw CatalogMediaSourceException(CatalogMediaFallbackReason.ENCODED_SIZE_EXCEEDED)
            }
            throw error
        } finally {
            activeTask.compareAndSet(task, null)
            activeStream.getAndSet(null)?.closeQuietly()
        }
        ensureNotCancelled()
        val content = checkNotNull(downloaded.get())
        return CatalogMediaPayload(content.bytes, metadata, content.sha256)
    }

    override fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        activeStream.getAndSet(null)?.closeQuietly()
        activeTask.get()?.cancel()
    }

    private fun ensureNotCancelled() {
        if (cancelled.get())
            throw CancellationException("Firebase catalog media transfer cancelled")
    }
}

private data class CatalogMediaRequest(
    val path: String,
    val cacheKey: String,
    val identity: CatalogMediaIdentity?,
)

class BoundedCatalogMediaLoader
internal constructor(
    private val transferSource: CatalogMediaTransferSource,
    cacheBytes: Int = CATALOG_MEDIA_CACHE_BYTES,
    private val boundsReader: CatalogImageBoundsReader = AndroidCatalogImageBoundsReader,
    private val decoder: CatalogBitmapDecoder = AndroidCatalogBitmapDecoder,
    private val resourceLimits: CatalogMediaResourceLimits = CatalogMediaResourceLimits(),
    private val lifecycleObserver: CatalogMediaFlightLifecycleObserver =
        NoOpCatalogMediaFlightLifecycleObserver,
) : CatalogMediaLoader, AutoCloseable {
    internal constructor(
        source: CatalogMediaPayloadSource,
        cacheBytes: Int = CATALOG_MEDIA_CACHE_BYTES,
        boundsReader: CatalogImageBoundsReader = AndroidCatalogImageBoundsReader,
        decoder: CatalogBitmapDecoder = AndroidCatalogBitmapDecoder,
        resourceLimits: CatalogMediaResourceLimits = CatalogMediaResourceLimits(),
        lifecycleObserver: CatalogMediaFlightLifecycleObserver =
            NoOpCatalogMediaFlightLifecycleObserver,
    ) : this(
        transferSource = source.asTransferSource(),
        cacheBytes = cacheBytes,
        boundsReader = boundsReader,
        decoder = decoder,
        resourceLimits = resourceLimits,
        lifecycleObserver = lifecycleObserver,
    )

    internal constructor(
        source: CatalogMediaByteSource,
        cacheBytes: Int = CATALOG_MEDIA_CACHE_BYTES,
    ) : this(
        source =
            CatalogMediaPayloadSource { path, maximumBytes ->
                CatalogMediaPayload(source.load(path, maximumBytes))
            },
        cacheBytes = cacheBytes,
    )

    constructor(
        source: CatalogMediaVerifiedSource,
        cacheBytes: Int = CATALOG_MEDIA_CACHE_BYTES,
    ) : this(
        source =
            CatalogMediaPayloadSource { path, maximumBytes ->
                source.load(path, maximumBytes)
            },
        cacheBytes = cacheBytes,
    )

    private val cache =
        object : LruCache<String, Bitmap>(cacheBytes) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }
    private val fallbackCache =
        object :
            LruCache<String, CatalogMediaLoadResult.Fallback>(
                CATALOG_MEDIA_FALLBACK_CACHE_ENTRIES
            ) {
            override fun sizeOf(key: String, value: CatalogMediaLoadResult.Fallback): Int = 1
        }
    private val closed = AtomicBoolean(false)
    private val loaderJob = SupervisorJob()
    private val loaderScope = CoroutineScope(loaderJob + Dispatchers.IO)
    private val flightLock = Any()
    private val inFlight = mutableMapOf<String, Flight>()
    private val activeFlights = mutableSetOf<Flight>()
    private val flightBudget = Semaphore(resourceLimits.maxConcurrentFlights)
    private val byteBudget =
        Semaphore((resourceLimits.maxInFlightBytes / MAX_CATALOG_MEDIA_BYTES).toInt())
    private val decodeBudget = Semaphore(resourceLimits.maxConcurrentDecodes)
    private val activeTransfers = AtomicInteger()
    private val reservedByteSlots = AtomicInteger()
    private val activeDecodes = AtomicInteger()
    private val peakActiveTransfers = AtomicInteger()
    private val peakReservedByteSlots = AtomicInteger()
    private val peakActiveDecodes = AtomicInteger()
    private var nextFlightGeneration = 0L
    private var nextLeaseId = 0L

    override suspend fun load(identity: CatalogMediaIdentity): CatalogMediaLoadResult =
        load(
            CatalogMediaRequest(
                path = identity.path,
                cacheKey = "identity:${identity.cacheKey}",
                identity = identity,
            )
        )

    /** Legacy test-only path entry point. Production callers can only supply a typed identity. */
    internal suspend fun load(path: String): CatalogMediaLoadResult =
        load(CatalogMediaRequest(path, "legacy:$path", null))

    private suspend fun load(request: CatalogMediaRequest): CatalogMediaLoadResult {
        ensureOpen()
        if (!CATALOG_MEDIA_PATH.matches(request.path)) {
            return CatalogMediaLoadResult.Fallback(CatalogMediaFallbackReason.INVALID_PATH)
        }
        cached(request.cacheKey)?.let {
            return it
        }
        lifecycleObserver.beforeSubscriberAttach(request.path)
        val lease =
            synchronized(flightLock) {
                ensureOpen()
                cached(request.cacheKey)?.let {
                    return it
                }
                val current = inFlight[request.cacheKey]
                val selected =
                    if (current == null || current.cancelling.get() || current.result.isCompleted) {
                        createFlight(request)
                    } else {
                        current
                    }
                val createdLease = SubscriberLease(selected, ++nextLeaseId)
                check(selected.leaseIds.add(createdLease.id))
                selected.result.start()
                createdLease
            }
        return try {
            lifecycleObserver.subscriberAttached(
                request.path,
                lease.flight.generation,
                lease.id,
            )
            lease.flight.result.await()
        } finally {
            detachSubscriber(lease)
        }
    }

    internal fun resourceSnapshot(): CatalogMediaResourceSnapshot =
        synchronized(flightLock) {
            CatalogMediaResourceSnapshot(
                flights = inFlight.size,
                subscribers = inFlight.values.sumOf { it.leaseIds.size },
                activeTransfers = activeTransfers.get(),
                reservedInFlightBytes = reservedByteSlots.get().toLong() * MAX_CATALOG_MEDIA_BYTES,
                activeDecodes = activeDecodes.get(),
                peakActiveTransfers = peakActiveTransfers.get(),
                peakReservedInFlightBytes =
                    peakReservedByteSlots.get().toLong() * MAX_CATALOG_MEDIA_BYTES,
                peakActiveDecodes = peakActiveDecodes.get(),
            )
        }

    private fun createFlight(request: CatalogMediaRequest): Flight {
        val flight = Flight(request, ++nextFlightGeneration)
        flight.result = loaderScope.async(start = CoroutineStart.LAZY) { runFlight(flight) }
        inFlight[request.cacheKey] = flight
        check(activeFlights.add(flight))
        flight.result.invokeOnCompletion { cause ->
            if (cause is CancellationException) flight.cancelTransferOnce()
            lifecycleObserver.beforeCompletionCleanup(request.path, flight.generation)
            synchronized(flightLock) {
                inFlight.remove(request.cacheKey, flight)
                check(activeFlights.remove(flight))
            }
        }
        return flight
    }

    private suspend fun runFlight(flight: Flight): CatalogMediaLoadResult =
        flightBudget.withPermit {
            byteBudget.withPermit {
                val byteSlots = reservedByteSlots.incrementAndGet()
                peakReservedByteSlots.updateMaximum(byteSlots)
                try {
                    val result = loadUncached(flight)
                    currentCoroutineContext().ensureActiveOrRecycle(result)
                    if (result.isCacheable()) {
                        lifecycleObserver.beforeCachePublish(flight.path, flight.generation)
                        if (!publishCache(flight, result)) {
                            result.recycleLoaded()
                            throw CancellationException(
                                "Catalog media flight is no longer current at cache commit"
                            )
                        }
                        lifecycleObserver.cachePublishedBeforeCompletion(
                            flight.path,
                            flight.generation,
                        )
                    }
                    result
                } finally {
                    reservedByteSlots.decrementAndGet()
                }
            }
        }

    private fun publishCache(
        flight: Flight,
        result: CatalogMediaLoadResult,
    ): Boolean =
        synchronized(flightLock) {
            if (
                inFlight[flight.cacheKey] !== flight ||
                    flight.cancelling.get() ||
                    flight.result.isCancelled ||
                    closed.get() ||
                    flight.leaseIds.isEmpty()
            ) {
                return@synchronized false
            }
            when (result) {
                is CatalogMediaLoadResult.Loaded -> cache.put(flight.cacheKey, result.bitmap)
                is CatalogMediaLoadResult.Fallback -> fallbackCache.put(flight.cacheKey, result)
            }
            flight.terminalPublished = true
            true
        }

    private fun detachSubscriber(lease: SubscriberLease) {
        if (!lease.detached.compareAndSet(false, true)) return
        val flight = lease.flight
        lifecycleObserver.beforeSubscriberDetach(flight.path, flight.generation, lease.id)
        val cancel =
            synchronized(flightLock) {
                check(flight.leaseIds.remove(lease.id))
                if (
                    flight.leaseIds.isEmpty() &&
                        inFlight[flight.cacheKey] === flight &&
                        !flight.terminalPublished &&
                        !flight.result.isCompleted
                ) {
                    check(inFlight.remove(flight.cacheKey, flight))
                    flight.cancelling.set(true)
                    flight
                } else {
                    null
                }
            }
        lifecycleObserver.subscriberDetached(flight.path, flight.generation, lease.id)
        if (cancel != null) {
            lifecycleObserver.lastLeaseRemovedBeforeCancel(flight.path, flight.generation)
            cancel.cancel(CancellationException("Catalog media flight has no subscribers"))
        }
    }

    override fun close() {
        val flights =
            synchronized(flightLock) {
                if (!closed.compareAndSet(false, true)) return
                activeFlights.toList().also { selected ->
                    selected.forEach { it.cancelling.set(true) }
                    inFlight.clear()
                }
            }
        lifecycleObserver.closeLinearizedBeforeCancellation()
        val jobs = loaderJob.children.toList()
        val cancellation = CancellationException(CATALOG_MEDIA_LOADER_CLOSED_MESSAGE)
        flights.forEach { it.cancel(cancellation) }
        loaderJob.cancel(cancellation)
        runBlocking {
            withTimeoutOrNull(CATALOG_MEDIA_CLOSE_TIMEOUT_MILLIS) { jobs.joinAll() }
        }
        cache.evictAll()
        fallbackCache.evictAll()
    }

    private fun ensureOpen() {
        if (closed.get()) throw CancellationException(CATALOG_MEDIA_LOADER_CLOSED_MESSAGE)
    }

    private fun cached(cacheKey: String): CatalogMediaLoadResult? {
        cache.get(cacheKey)?.takeUnless(Bitmap::isRecycled)?.let {
            return CatalogMediaLoadResult.Loaded(it)
        }
        return fallbackCache.get(cacheKey)
    }

    private suspend fun loadUncached(flight: Flight): CatalogMediaLoadResult {
        val payload =
            try {
                val transfer = transferSource.open(flight.path)
                lifecycleObserver.beforeTransferAttach(flight.path, flight.generation)
                flight.attachTransfer(transfer)
                try {
                    transfer.read(MAX_CATALOG_MEDIA_BYTES)
                } finally {
                    flight.clearTransfer(transfer)
                }
            } catch (_: CancellationException) {
                currentCoroutineContext().ensureActive()
                return CatalogMediaLoadResult.Fallback(CatalogMediaFallbackReason.DOWNLOAD_FAILED)
            } catch (error: CatalogMediaSourceException) {
                return CatalogMediaLoadResult.Fallback(error.reason)
            } catch (_: Exception) {
                return CatalogMediaLoadResult.Fallback(CatalogMediaFallbackReason.DOWNLOAD_FAILED)
            }
        if (payload.bytes.isEmpty()) {
            return CatalogMediaLoadResult.Fallback(CatalogMediaFallbackReason.EMPTY_PAYLOAD)
        }
        if (payload.bytes.size > MAX_CATALOG_MEDIA_BYTES) {
            return CatalogMediaLoadResult.Fallback(CatalogMediaFallbackReason.ENCODED_SIZE_EXCEEDED)
        }
        if (flight.identity != null && !payload.matches(flight.identity)) {
            return CatalogMediaLoadResult.Fallback(CatalogMediaFallbackReason.INTEGRITY_MISMATCH)
        }
        return decodeBudget.withPermit {
            val decodes = activeDecodes.incrementAndGet()
            peakActiveDecodes.updateMaximum(decodes)
            try {
                runInterruptible(Dispatchers.Default) { decodeBounded(flight.path, payload) }
            } finally {
                activeDecodes.decrementAndGet()
            }
        }
    }

    private inner class Flight(
        request: CatalogMediaRequest,
        val generation: Long,
    ) {
        val path = request.path
        val cacheKey = request.cacheKey
        val identity = request.identity
        val leaseIds = mutableSetOf<Long>()
        val transfer = AtomicReference<CatalogMediaTransfer?>()
        val cancelling = AtomicBoolean(false)
        val transferCancellationIssued = AtomicBoolean(false)
        var terminalPublished = false
        lateinit var result: Deferred<CatalogMediaLoadResult>

        fun attachTransfer(value: CatalogMediaTransfer) {
            check(transfer.compareAndSet(null, value))
            val count = activeTransfers.incrementAndGet()
            peakActiveTransfers.updateMaximum(count)
            if (cancelling.get() || closed.get() || result.isCancelled) {
                cancelTransferOnce()
            }
        }

        fun clearTransfer(value: CatalogMediaTransfer) {
            if (transfer.compareAndSet(value, null)) activeTransfers.decrementAndGet()
        }

        fun cancelTransferOnce() {
            val activeTransfer = transfer.get() ?: return
            if (transferCancellationIssued.compareAndSet(false, true)) {
                activeTransfer.cancel()
            }
        }

        fun cancel(cause: CancellationException) {
            cancelling.set(true)
            cancelTransferOnce()
            result.cancel(cause)
        }
    }

    private class SubscriberLease(
        val flight: BoundedCatalogMediaLoader.Flight,
        val id: Long,
    ) {
        val detached = AtomicBoolean(false)
    }

    private fun decodeBounded(
        path: String,
        payload: CatalogMediaPayload,
    ): CatalogMediaLoadResult {
        val bounds =
            try {
                boundsReader.read(payload.bytes)
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                return CatalogMediaLoadResult.Fallback(CatalogMediaFallbackReason.OUT_OF_MEMORY)
            } ?: return CatalogMediaLoadResult.Fallback(CatalogMediaFallbackReason.INVALID_HEADER)
        if (!supportedContentType(path, bounds.contentType)) {
            return CatalogMediaLoadResult.Fallback(
                CatalogMediaFallbackReason.UNSUPPORTED_CONTENT_TYPE
            )
        }
        val boundsFailure = validateCatalogMediaDimensions(bounds.width, bounds.height)
        if (boundsFailure != null) return CatalogMediaLoadResult.Fallback(boundsFailure)
        val metadata = payload.metadata
        if (
            metadata != null &&
                (metadata.contentType != bounds.contentType ||
                    metadata.sizeBytes != payload.bytes.size.toLong() ||
                    metadata.width != bounds.width ||
                    metadata.height != bounds.height)
        ) {
            return CatalogMediaLoadResult.Fallback(CatalogMediaFallbackReason.METADATA_MISMATCH)
        }
        val sampleSize = catalogMediaSampleSize(bounds.width, bounds.height)
        val decoded =
            try {
                decoder.decode(payload.bytes, sampleSize)
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                return CatalogMediaLoadResult.Fallback(CatalogMediaFallbackReason.OUT_OF_MEMORY)
            } ?: return CatalogMediaLoadResult.Fallback(CatalogMediaFallbackReason.DECODE_FAILED)
        val allocationBytes = decoded.allocationByteCount.toLong()
        if (
            decoded.width !in 1..CATALOG_MEDIA_TARGET_PIXELS ||
                decoded.height !in 1..CATALOG_MEDIA_TARGET_PIXELS ||
                decoded.width.toLong() * decoded.height > MAX_CATALOG_MEDIA_DECODED_PIXELS ||
                allocationBytes > MAX_CATALOG_MEDIA_DECODED_BYTES
        ) {
            decoded.recycle()
            return CatalogMediaLoadResult.Fallback(
                CatalogMediaFallbackReason.DECODED_BOUNDS_EXCEEDED
            )
        }
        return CatalogMediaLoadResult.Loaded(decoded)
    }
}

internal fun catalogMediaSampleSize(width: Int, height: Int): Int {
    var sampleSize = 1
    while (true) {
        val sampledWidth = ceilDivision(width, sampleSize)
        val sampledHeight = ceilDivision(height, sampleSize)
        val sampledPixels = sampledWidth.toLong() * sampledHeight
        if (
            sampledWidth <= CATALOG_MEDIA_TARGET_PIXELS &&
                sampledHeight <= CATALOG_MEDIA_TARGET_PIXELS &&
                sampledPixels <= MAX_CATALOG_MEDIA_DECODED_PIXELS
        ) {
            return sampleSize
        }
        sampleSize *= 2
    }
}

internal fun validateCatalogMediaDimensions(
    width: Int,
    height: Int,
): CatalogMediaFallbackReason? {
    if (width !in 1..MAX_CATALOG_MEDIA_DIMENSION || height !in 1..MAX_CATALOG_MEDIA_DIMENSION) {
        return CatalogMediaFallbackReason.DIMENSIONS_EXCEEDED
    }
    val pixels = width.toLong() * height
    if (
        pixels > MAX_CATALOG_MEDIA_SOURCE_PIXELS ||
            pixels * 4L > MAX_CATALOG_MEDIA_SOURCE_DECODED_BYTES
    ) {
        return CatalogMediaFallbackReason.PIXEL_BUDGET_EXCEEDED
    }
    val shorter = minOf(width, height).toLong()
    val longer = maxOf(width, height).toLong()
    if (longer > shorter * MAX_CATALOG_MEDIA_ASPECT_RATIO) {
        return CatalogMediaFallbackReason.ASPECT_RATIO_EXCEEDED
    }
    return null
}

internal fun validateCatalogMediaMetadata(
    path: String,
    metadata: CatalogMediaObjectMetadata,
): CatalogMediaFallbackReason? {
    if (!CATALOG_MEDIA_PATH.matches(path) || !supportedContentType(path, metadata.contentType)) {
        return CatalogMediaFallbackReason.STORAGE_METADATA_INVALID
    }
    if (metadata.sizeBytes !in 1..MAX_CATALOG_MEDIA_BYTES) {
        return CatalogMediaFallbackReason.STORAGE_METADATA_INVALID
    }
    return validateCatalogMediaDimensions(metadata.width, metadata.height)?.let {
        CatalogMediaFallbackReason.STORAGE_METADATA_INVALID
    }
}

private fun CatalogMediaPayload.matches(identity: CatalogMediaIdentity): Boolean {
    val objectMetadata = metadata ?: return false
    return sha256 == identity.sha256 &&
        bytes.size.toLong() == identity.byteSize &&
        objectMetadata.contentType == identity.mimeType &&
        objectMetadata.sizeBytes == identity.byteSize &&
        objectMetadata.width == identity.width &&
        objectMetadata.height == identity.height &&
        objectMetadata.sha256 == identity.sha256 &&
        objectMetadata.mediaRevision == identity.mediaRevision.value
}

private fun ByteArray.catalogSha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

internal fun detectCatalogMediaContentType(bytes: ByteArray): String? =
    when {
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte() -> "image/png"
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.US_ASCII)) ->
            "image/webp"
        else -> null
    }

private fun supportedContentType(path: String, contentType: String): Boolean =
    when (path.substringAfterLast('.')) {
        "png" -> contentType == "image/png"
        "jpg",
        "jpeg" -> contentType == "image/jpeg"
        "webp" -> contentType == "image/webp"
        else -> false
    }

private fun ceilDivision(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

private class CatalogMediaSourceException(val reason: CatalogMediaFallbackReason) : Exception()

private class CatalogMediaStreamLimitException : IOException("Catalog media stream exceeds limit")

private data class CatalogMediaDownloadedBytes(val bytes: ByteArray, val sha256: String)

internal fun readCatalogMediaStream(
    input: InputStream,
    maximumBytes: Long,
    cancelled: AtomicBoolean = AtomicBoolean(false),
): ByteArray = readCatalogMediaStreamWithDigest(input, maximumBytes, cancelled).bytes

private fun readCatalogMediaStreamWithDigest(
    input: InputStream,
    maximumBytes: Long,
    cancelled: AtomicBoolean,
): CatalogMediaDownloadedBytes {
    require(maximumBytes in 1..Int.MAX_VALUE.toLong())
    val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE.toLong()).toInt())
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(minOf(maximumBytes + 1, DEFAULT_BUFFER_SIZE.toLong()).toInt())
    var total = 0L
    while (true) {
        if (cancelled.get()) throw CancellationException("Catalog media stream cancelled")
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total += count
        if (total > maximumBytes) throw CatalogMediaStreamLimitException()
        digest.update(buffer, 0, count)
        output.write(buffer, 0, count)
    }
    return CatalogMediaDownloadedBytes(
        output.toByteArray(),
        digest.digest().joinToString("") { "%02x".format(it) },
    )
}

private fun InputStream.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // Cancellation has already made the stream terminal.
    }
}

private fun CatalogMediaPayloadSource.asTransferSource(): CatalogMediaTransferSource =
    CatalogMediaTransferSource { path ->
        object : CatalogMediaTransfer {
            override suspend fun read(maximumBytes: Long): CatalogMediaPayload =
                load(path, maximumBytes)

            override fun cancel() = Unit
        }
    }

private fun AtomicInteger.updateMaximum(candidate: Int) {
    while (true) {
        val current = get()
        if (candidate <= current || compareAndSet(current, candidate)) return
    }
}

private fun CatalogMediaLoadResult.isCacheable(): Boolean =
    this is CatalogMediaLoadResult.Loaded ||
        (this is CatalogMediaLoadResult.Fallback &&
            reason != CatalogMediaFallbackReason.DOWNLOAD_FAILED)

private fun CatalogMediaLoadResult.recycleLoaded() {
    if (this is CatalogMediaLoadResult.Loaded && !bitmap.isRecycled) bitmap.recycle()
}

private fun kotlin.coroutines.CoroutineContext.ensureActiveOrRecycle(
    result: CatalogMediaLoadResult
) {
    try {
        ensureActive()
    } catch (error: CancellationException) {
        result.recycleLoaded()
        throw error
    }
}

object PlaceholderCatalogMediaLoader : CatalogMediaLoader {
    override suspend fun load(identity: CatalogMediaIdentity): CatalogMediaLoadResult =
        CatalogMediaLoadResult.Fallback(CatalogMediaFallbackReason.NOT_CONFIGURED)
}

private suspend fun <T> Task<T>.await(onCancellation: (() -> Unit)? = null): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { onCancellation?.invoke() }
        addOnSuccessListener { result -> continuation.resume(result) }
        addOnFailureListener { error -> continuation.resumeWithException(error) }
        addOnCanceledListener {
            continuation.cancel(CancellationException("Firebase catalog media task cancelled"))
        }
    }

private sealed interface CatalogMediaState {
    data object Loading : CatalogMediaState

    data class Loaded(val bitmap: Bitmap) : CatalogMediaState

    data object Failed : CatalogMediaState
}

@Composable
internal fun CatalogMedia(
    identity: CatalogMediaIdentity?,
    name: String,
    size: Dp,
    loader: CatalogMediaLoader,
    modifier: Modifier = Modifier,
) {
    val state by
        produceState<CatalogMediaState>(CatalogMediaState.Loading, identity, loader) {
            value =
                if (identity == null) {
                    CatalogMediaState.Failed
                } else {
                    try {
                        when (val result = loader.load(identity)) {
                            is CatalogMediaLoadResult.Loaded ->
                                CatalogMediaState.Loaded(result.bitmap)
                            is CatalogMediaLoadResult.Fallback -> CatalogMediaState.Failed
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        CatalogMediaState.Failed
                    }
                }
        }
    val base =
        modifier
            .size(size)
            .clip(
                androidx.compose.foundation.shape.RoundedCornerShape(PlanteriorTheme.spacing.medium)
            )
    when (val current = state) {
        CatalogMediaState.Loading ->
            Box(
                base
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .testTag(InventoryTestTags.mediaLoading(name))
                    .semantics { contentDescription = "$name 이미지 불러오는 중" },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(PlanteriorTheme.spacing.huge))
            }
        is CatalogMediaState.Loaded ->
            Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = "$name 아이템 이미지",
                contentScale = ContentScale.Crop,
                modifier = base.testTag(InventoryTestTags.media(name)),
            )
        CatalogMediaState.Failed ->
            Box(
                base
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .testTag(InventoryTestTags.mediaFallback(name))
                    .semantics { contentDescription = "$name 이미지를 불러오지 못해 대체 이미지를 표시함" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = PlanteriorIcons.Collection,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(PlanteriorTheme.spacing.huge),
                )
            }
    }
}
