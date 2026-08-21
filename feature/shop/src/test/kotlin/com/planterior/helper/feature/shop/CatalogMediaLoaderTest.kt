package com.planterior.helper.feature.shop

import android.graphics.Bitmap
import android.graphics.Color
import com.planterior.helper.core.model.CatalogMediaIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogMediaLoaderTest {
    @Test
    fun `32768 by 1535 compressed PNG is sampled before allocation under the media heap budget`() =
        runTest {
            val bytes = solidPng(width = 32_768, height = 1_535)
            assertTrue(bytes.size <= MAX_CATALOG_MEDIA_BYTES)
            var requestedSample = 0
            val loader =
                BoundedCatalogMediaLoader(
                    source = CatalogMediaPayloadSource { _, _ -> CatalogMediaPayload(bytes) },
                    boundsReader = testBoundsReader,
                    decoder =
                        CatalogBitmapDecoder { _, sampleSize ->
                            requestedSample = sampleSize
                            Bitmap.createBitmap(
                                ceilDivision(32_768, sampleSize),
                                ceilDivision(1_535, sampleSize),
                                Bitmap.Config.ARGB_8888,
                            )
                        },
                )

            val decoded = loaded(loader.load("catalog-assets/extreme-landscape/room.png"))

            assertEquals(64, requestedSample)
            assertEquals(512, decoded.width)
            assertEquals(24, decoded.height)
            assertTrue(decoded.allocationByteCount <= MAX_CATALOG_MEDIA_DECODED_BYTES)
        }

    @Test
    fun `sampling doubles while either orientation remains outside the output contract`() {
        assertEquals(2, catalogMediaSampleSize(769, 768))
        assertEquals(2, catalogMediaSampleSize(768, 769))
        assertEquals(64, catalogMediaSampleSize(32_768, 1_535))
        assertEquals(64, catalogMediaSampleSize(1_535, 32_768))
    }

    @Test
    fun `tiny compressed huge PNG JPEG and WebP bounds are rejected before pixel allocation`() =
        runTest {
            val fixtures =
                listOf(
                    Triple(
                        "catalog-assets/png-bomb/room.png",
                        pngWithDeclaredBounds(32_768, 32_768),
                        CatalogMediaFallbackReason.PIXEL_BUDGET_EXCEEDED,
                    ),
                    Triple(
                        "catalog-assets/jpeg-bomb/room.jpg",
                        jpegWithDeclaredBounds(65_535, 1),
                        CatalogMediaFallbackReason.DIMENSIONS_EXCEEDED,
                    ),
                    Triple(
                        "catalog-assets/webp-bomb/room.webp",
                        webpWithDeclaredBounds(16_384, 16_384),
                        CatalogMediaFallbackReason.PIXEL_BUDGET_EXCEEDED,
                    ),
                )
            fixtures.forEach { (path, bytes, expected) ->
                var decodeCalls = 0
                val loader =
                    BoundedCatalogMediaLoader(
                        source = CatalogMediaPayloadSource { _, _ -> CatalogMediaPayload(bytes) },
                        boundsReader = testBoundsReader,
                        decoder =
                            CatalogBitmapDecoder { _, _ ->
                                decodeCalls += 1
                                null
                            },
                    )

                assertEquals(expected, fallback(loader.load(path)).reason)
                assertEquals(0, decodeCalls)
            }
        }

    @Test
    fun `one dimension extremes aspect ratios zero bounds and malformed headers use typed fallback`() =
        runTest {
            val cases =
                listOf(
                    "catalog-assets/too-wide/room.png" to pngWithDeclaredBounds(32_769, 1),
                    "catalog-assets/aspect/room.png" to pngWithDeclaredBounds(32_768, 1),
                    "catalog-assets/zero/room.png" to pngWithDeclaredBounds(0, 64),
                    "catalog-assets/malformed/room.png" to byteArrayOf(1, 2, 3, 4),
                )
            val reasons = cases.map { (path, bytes) ->
                fallback(
                        BoundedCatalogMediaLoader(
                                source =
                                    CatalogMediaPayloadSource { _, _ ->
                                        CatalogMediaPayload(bytes)
                                    },
                                boundsReader = testBoundsReader,
                            )
                            .load(path)
                    )
                    .reason
            }

            assertEquals(CatalogMediaFallbackReason.DIMENSIONS_EXCEEDED, reasons[0])
            assertEquals(CatalogMediaFallbackReason.ASPECT_RATIO_EXCEEDED, reasons[1])
            assertEquals(CatalogMediaFallbackReason.DIMENSIONS_EXCEEDED, reasons[2])
            assertEquals(CatalogMediaFallbackReason.INVALID_HEADER, reasons[3])
        }

    @Test
    fun `normal PNG JPEG WebP and alpha assets decode within explicit output budgets`() = runTest {
        val fixtures =
            listOf(
                "catalog-assets/normal-png/room.png" to
                    bitmapBytes(Bitmap.CompressFormat.PNG, alpha = true),
                "catalog-assets/normal-jpeg/room.jpg" to bitmapBytes(Bitmap.CompressFormat.JPEG),
            )
        fixtures.forEach { (path, bytes) ->
            val bitmap =
                loaded(
                    BoundedCatalogMediaLoader(CatalogMediaByteSource { _, _ -> bytes }).load(path)
                )
            assertTrue(bitmap.width <= CATALOG_MEDIA_TARGET_PIXELS)
            assertTrue(bitmap.height <= CATALOG_MEDIA_TARGET_PIXELS)
            assertTrue(bitmap.allocationByteCount <= MAX_CATALOG_MEDIA_DECODED_BYTES)
        }
        val webp =
            loaded(
                BoundedCatalogMediaLoader(
                        source =
                            CatalogMediaPayloadSource { _, _ ->
                                CatalogMediaPayload(webpWithDeclaredBounds(64, 64))
                            },
                        boundsReader = testBoundsReader,
                        decoder =
                            CatalogBitmapDecoder { _, _ ->
                                Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                            },
                    )
                    .load("catalog-assets/normal-webp/room.webp")
            )
        assertEquals(64, webp.width)
        val alpha =
            loaded(
                BoundedCatalogMediaLoader(CatalogMediaByteSource { _, _ -> solidPng(64, 64) })
                    .load("catalog-assets/alpha/room.png")
            )
        assertEquals(0, Color.alpha(alpha.getPixel(0, 0)))
    }

    @Test
    fun `unsupported decoded content and declared metadata mismatches return typed fallback`() =
        runTest {
            val jpeg = bitmapBytes(Bitmap.CompressFormat.JPEG)
            val wrongExtension =
                BoundedCatalogMediaLoader(CatalogMediaByteSource { _, _ -> jpeg })
                    .load("catalog-assets/wrong-type/room.png")
            assertEquals(
                CatalogMediaFallbackReason.UNSUPPORTED_CONTENT_TYPE,
                fallback(wrongExtension).reason,
            )

            val png = bitmapBytes(Bitmap.CompressFormat.PNG)
            val metadata =
                CatalogMediaObjectMetadata(
                    contentType = "image/png",
                    sizeBytes = png.size.toLong(),
                    width = 65,
                    height = 64,
                )
            val mismatch =
                BoundedCatalogMediaLoader(
                        source =
                            CatalogMediaPayloadSource { _, _ ->
                                CatalogMediaPayload(png, metadata)
                            }
                    )
                    .load("catalog-assets/metadata/room.png")
            assertEquals(CatalogMediaFallbackReason.METADATA_MISMATCH, fallback(mismatch).reason)
        }

    @Test
    fun `decode null oversized output and narrow OOM catch are typed cached fallbacks`() = runTest {
        val bytes = bitmapBytes(Bitmap.CompressFormat.PNG)
        val nullDecode =
            BoundedCatalogMediaLoader(
                    source = CatalogMediaPayloadSource { _, _ -> CatalogMediaPayload(bytes) },
                    decoder = CatalogBitmapDecoder { _, _ -> null },
                )
                .load("catalog-assets/null/room.png")
        assertEquals(CatalogMediaFallbackReason.DECODE_FAILED, fallback(nullDecode).reason)

        val oversized = Bitmap.createBitmap(769, 1, Bitmap.Config.ARGB_8888)
        val oversizedResult =
            BoundedCatalogMediaLoader(
                    source = CatalogMediaPayloadSource { _, _ -> CatalogMediaPayload(bytes) },
                    decoder = CatalogBitmapDecoder { _, _ -> oversized },
                )
                .load("catalog-assets/post-bounds/room.png")
        assertEquals(
            CatalogMediaFallbackReason.DECODED_BOUNDS_EXCEEDED,
            fallback(oversizedResult).reason,
        )
        assertTrue(oversized.isRecycled)

        var sourceCalls = 0
        var decodeCalls = 0
        val oomLoader =
            BoundedCatalogMediaLoader(
                source =
                    CatalogMediaPayloadSource { _, _ ->
                        sourceCalls += 1
                        CatalogMediaPayload(bytes)
                    },
                decoder =
                    CatalogBitmapDecoder { _, _ ->
                        decodeCalls += 1
                        throw OutOfMemoryError("bounded test allocation")
                    },
            )
        repeat(2) {
            assertEquals(
                CatalogMediaFallbackReason.OUT_OF_MEMORY,
                fallback(oomLoader.load("catalog-assets/oom/room.png")).reason,
            )
        }
        assertEquals(1, sourceCalls)
        assertEquals(1, decodeCalls)
    }

    @Test
    fun `valid Storage metadata is checked before bytes and against authoritative decoded bounds`() =
        runTest {
            val bytes = solidPng(64, 64)
            val metadata =
                CatalogMediaObjectMetadata(
                    contentType = "image/png",
                    sizeBytes = bytes.size.toLong(),
                    width = 64,
                    height = 64,
                )
            assertEquals(
                null,
                validateCatalogMediaMetadata("catalog-assets/metadata/room.png", metadata),
            )
            assertEquals(
                CatalogMediaFallbackReason.STORAGE_METADATA_INVALID,
                validateCatalogMediaMetadata(
                    "catalog-assets/metadata/room.png",
                    metadata.copy(contentType = "image/jpeg"),
                ),
            )
            assertEquals(
                CatalogMediaFallbackReason.STORAGE_METADATA_INVALID,
                validateCatalogMediaMetadata(
                    "catalog-assets/metadata/room.png",
                    metadata.copy(width = 32_769),
                ),
            )
            val result =
                BoundedCatalogMediaLoader(
                        source =
                            CatalogMediaPayloadSource { _, _ ->
                                CatalogMediaPayload(bytes, metadata)
                            },
                        boundsReader = testBoundsReader,
                    )
                    .load("catalog-assets/metadata/room.png")
            assertEquals(64, loaded(result).width)
        }

    @Test
    fun `source cancellation is a transient typed failure and is never cached`() = runTest {
        var sourceCalls = 0
        val loader =
            BoundedCatalogMediaLoader(
                CatalogMediaByteSource { _, _ ->
                    sourceCalls += 1
                    throw CancellationException("storage transfer cancelled")
                }
            )

        repeat(2) {
            assertEquals(
                CatalogMediaFallbackReason.DOWNLOAD_FAILED,
                fallback(loader.load("catalog-assets/cancel/room.png")).reason,
            )
        }
        assertEquals(2, sourceCalls)
        loader.close()
    }

    @Test
    fun `identical metadata with substituted bytes is rejected before decode or cache`() = runTest {
        val expectedBytes = bitmapBytes(Bitmap.CompressFormat.PNG)
        val substitutedBytes = solidPng(32, 32)
        val identity = mediaIdentity("metadata-substitution", expectedBytes, "image/png", 64, 64)
        val decodeCalls = AtomicInteger()
        val loader =
            BoundedCatalogMediaLoader(
                source =
                    CatalogMediaPayloadSource { _, _ ->
                        CatalogMediaPayload(
                            substitutedBytes,
                            CatalogMediaObjectMetadata(
                                identity.mimeType,
                                identity.byteSize,
                                identity.width,
                                identity.height,
                                identity.sha256,
                                identity.mediaRevision.value,
                            ),
                        )
                    },
                decoder =
                    CatalogBitmapDecoder { _, _ ->
                        decodeCalls.incrementAndGet()
                        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                    },
            )

        assertEquals(
            CatalogMediaFallbackReason.INTEGRITY_MISMATCH,
            fallback(loader.load(identity)).reason,
        )
        assertEquals(0, decodeCalls.get())
        loader.close()
        assertZeroResources(loader)
    }

    @Test
    fun `replacement released while an immutable identity stream is in flight is rejected`() =
        runTest {
            val expectedBytes = bitmapBytes(Bitmap.CompressFormat.PNG)
            val replacementBytes = solidPng(48, 48)
            val identity = mediaIdentity("stream-replacement", expectedBytes, "image/png", 64, 64)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val loader =
                BoundedCatalogMediaLoader(
                    source =
                        CatalogMediaPayloadSource { _, _ ->
                            entered.complete(Unit)
                            release.await()
                            CatalogMediaPayload(replacementBytes, identity.objectMetadata())
                        }
                )
            val result = async { loader.load(identity) }
            entered.await()
            release.complete(Unit)

            assertEquals(
                CatalogMediaFallbackReason.INTEGRITY_MISMATCH,
                fallback(result.await()).reason,
            )
            loader.close()
            assertZeroResources(loader)
        }

    @Test
    fun `path digest mismatch is rejected at the typed identity boundary`() {
        val digest = "a".repeat(64)
        val failure = runCatching {
            CatalogMediaIdentity(
                path = "catalog-assets/path-mismatch/${"b".repeat(64)}.webp",
                sha256 = digest,
                byteSize = 1,
                mimeType = "image/webp",
                width = 1,
                height = 1,
                mediaRevision = com.planterior.helper.core.model.Revision(1),
            )
        }
            .exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `old digest cache is not reused and a new immutable revision succeeds`() = runTest {
        val oldBytes = bitmapBytes(Bitmap.CompressFormat.PNG)
        val newBytes = solidPng(64, 64)
        val oldIdentity = mediaIdentity("revision-cache", oldBytes, "image/png", 64, 64, 1)
        val newIdentity = mediaIdentity("revision-cache", newBytes, "image/png", 64, 64, 2)
        val calls = AtomicInteger()
        val loader =
            BoundedCatalogMediaLoader(
                source =
                    CatalogMediaPayloadSource { path, _ ->
                        calls.incrementAndGet()
                        val selected = if (path == oldIdentity.path) oldBytes else newBytes
                        CatalogMediaPayload(
                            selected,
                            if (path == oldIdentity.path) oldIdentity.objectMetadata()
                            else newIdentity.objectMetadata(),
                        )
                    }
            )

        val oldBitmap = loaded(loader.load(oldIdentity))
        val newBitmap = loaded(loader.load(newIdentity))
        assertTrue(oldBitmap !== newBitmap)
        assertEquals(2, calls.get())
        assertSame(newBitmap, loaded(loader.load(newIdentity)))
        assertEquals(2, calls.get())
        loader.close()
        assertZeroResources(loader)
    }

    @Test
    fun `sixteen mismatch subscribers share one transfer and terminate with integrity error`() =
        runTest {
            val expectedBytes = bitmapBytes(Bitmap.CompressFormat.PNG)
            val replacementBytes = solidPng(32, 32)
            val identity = mediaIdentity("concurrent-integrity", expectedBytes, "image/png", 64, 64)
            val calls = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val loader =
                BoundedCatalogMediaLoader(
                    source =
                        CatalogMediaPayloadSource { _, _ ->
                            calls.incrementAndGet()
                            entered.complete(Unit)
                            release.await()
                            CatalogMediaPayload(replacementBytes, identity.objectMetadata())
                        }
                )
            val subscribers = List(16) { async { loader.load(identity) } }
            entered.await()
            runCurrent()
            release.complete(Unit)

            subscribers.awaitAll().forEach {
                assertEquals(CatalogMediaFallbackReason.INTEGRITY_MISMATCH, fallback(it).reason)
            }
            assertEquals(1, calls.get())
            loader.close()
            assertZeroResources(loader)
        }

    @Test
    fun `admin overwrite reproduction cannot render bytes outside published identity`() = runTest {
        val published = bitmapBytes(Bitmap.CompressFormat.PNG)
        val overwritten = solidPng(16, 16)
        val identity = mediaIdentity("admin-overwrite", published, "image/png", 64, 64)
        val loader =
            BoundedCatalogMediaLoader(
                source =
                    CatalogMediaPayloadSource { _, _ ->
                        CatalogMediaPayload(overwritten, identity.objectMetadata())
                    }
            )

        assertEquals(
            CatalogMediaFallbackReason.INTEGRITY_MISMATCH,
            fallback(loader.load(identity)).reason,
        )
        loader.close()
        assertZeroResources(loader)
    }

    @Test
    fun `concurrent same path loads share one source decode and subsequent loads hit cache`() =
        runTest {
            val bytes = bitmapBytes(Bitmap.CompressFormat.PNG)
            val sourceCalls = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val loader =
                BoundedCatalogMediaLoader(
                    CatalogMediaByteSource { _, _ ->
                        sourceCalls.incrementAndGet()
                        entered.complete(Unit)
                        release.await()
                        bytes
                    }
                )
            val first = async { loader.load("catalog-assets/concurrent/room.png") }
            entered.await()
            val second = async { loader.load("catalog-assets/concurrent/room.png") }
            runCurrent()
            assertEquals(1, sourceCalls.get())
            release.complete(Unit)

            val firstBitmap = loaded(first.await())
            val secondBitmap = loaded(second.await())
            val cachedBitmap = loaded(loader.load("catalog-assets/concurrent/room.png"))
            assertSame(firstBitmap, secondBitmap)
            assertSame(firstBitmap, cachedBitmap)
            assertEquals(1, sourceCalls.get())
            loader.close()
        }

    @Test
    fun `last detach after decode prevents cancelled generation cache publication`() = runTest {
        val bytes = bitmapBytes(Bitmap.CompressFormat.PNG)
        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val leaseDetached = CountDownLatch(1)
        val decodedBitmaps = LinkedBlockingQueue<Bitmap>()
        val sourceCalls = AtomicInteger()
        val blockFirstPublish = AtomicBoolean(true)
        val loader =
            BoundedCatalogMediaLoader(
                source =
                    CatalogMediaPayloadSource { _, _ ->
                        sourceCalls.incrementAndGet()
                        CatalogMediaPayload(bytes)
                    },
                decoder =
                    CatalogBitmapDecoder { _, _ ->
                        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
                            decodedBitmaps.put(it)
                        }
                    },
                lifecycleObserver =
                    object : CatalogMediaFlightLifecycleObserver {
                        override fun beforeCachePublish(path: String, generation: Long) {
                            if (blockFirstPublish.compareAndSet(true, false)) {
                                publishEntered.countDown()
                                check(releasePublish.await(5, TimeUnit.SECONDS))
                            }
                        }

                        override fun subscriberDetached(
                            path: String,
                            generation: Long,
                            leaseId: Long,
                        ) {
                            leaseDetached.countDown()
                        }
                    },
            )
        val path = "catalog-assets/cancelled-publish/room.png"
        val cancelledSubscriber = async(Dispatchers.Default) { loader.load(path) }
        val cancelledBitmap = checkNotNull(decodedBitmaps.poll(5, TimeUnit.SECONDS))
        assertTrue(publishEntered.await(5, TimeUnit.SECONDS))

        cancelledSubscriber.cancel(CancellationException("last lease detached after decode"))
        assertTrue(leaseDetached.await(5, TimeUnit.SECONDS))
        releasePublish.countDown()
        cancelledSubscriber.join()
        val replacementBitmap = loaded(loader.load(path))

        assertEquals(2, sourceCalls.get())
        assertTrue(cancelledBitmap.isRecycled)
        assertTrue(cancelledBitmap !== replacementBitmap)
        loader.close()
        assertZeroResources(loader)
    }

    @Test
    fun `transfer attach between removal and job cancel invokes underlying cancel once`() =
        runTest {
            val beforeTransferAttach = CountDownLatch(1)
            val releaseTransferAttach = CountDownLatch(1)
            val removedBeforeJobCancel = CountDownLatch(1)
            val releaseJobCancel = CountDownLatch(1)
            val firstUnderlyingCancel = CountDownLatch(1)
            val readExited = CompletableDeferred<Unit>()
            val cancelCalls = AtomicInteger()
            val loader =
                BoundedCatalogMediaLoader(
                    transferSource =
                        CatalogMediaTransferSource {
                            object : CatalogMediaTransfer {
                                override suspend fun read(maximumBytes: Long): CatalogMediaPayload {
                                    try {
                                        awaitCancellation()
                                    } finally {
                                        readExited.complete(Unit)
                                    }
                                }

                                override fun cancel() {
                                    cancelCalls.incrementAndGet()
                                    firstUnderlyingCancel.countDown()
                                }
                            }
                        },
                    lifecycleObserver =
                        object : CatalogMediaFlightLifecycleObserver {
                            override fun beforeTransferAttach(path: String, generation: Long) {
                                beforeTransferAttach.countDown()
                                check(releaseTransferAttach.await(5, TimeUnit.SECONDS))
                            }

                            override fun lastLeaseRemovedBeforeCancel(
                                path: String,
                                generation: Long,
                            ) {
                                removedBeforeJobCancel.countDown()
                                check(releaseJobCancel.await(5, TimeUnit.SECONDS))
                            }
                        },
                )
            val subscriber =
                async(Dispatchers.Default) {
                    loader.load("catalog-assets/attach-cancel-race/room.png")
                }
            assertTrue(beforeTransferAttach.await(5, TimeUnit.SECONDS))

            subscriber.cancel(CancellationException("last lease detached before attach"))
            assertTrue(removedBeforeJobCancel.await(5, TimeUnit.SECONDS))
            releaseTransferAttach.countDown()
            assertTrue(firstUnderlyingCancel.await(5, TimeUnit.SECONDS))
            releaseJobCancel.countDown()
            subscriber.join()
            readExited.await()

            assertEquals(1, cancelCalls.get())
            loader.close()
            assertEquals(1, cancelCalls.get())
            assertZeroResources(loader)
        }

    @Test
    fun `close linearized before cache commit discards and recycles completed decode`() = runTest {
        val bytes = bitmapBytes(Bitmap.CompressFormat.PNG)
        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val closeLinearized = CountDownLatch(1)
        val decodedBitmaps = LinkedBlockingQueue<Bitmap>()
        val sourceCalls = AtomicInteger()
        val loader =
            BoundedCatalogMediaLoader(
                source =
                    CatalogMediaPayloadSource { _, _ ->
                        sourceCalls.incrementAndGet()
                        CatalogMediaPayload(bytes)
                    },
                decoder =
                    CatalogBitmapDecoder { _, _ ->
                        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
                            decodedBitmaps.put(it)
                        }
                    },
                lifecycleObserver =
                    object : CatalogMediaFlightLifecycleObserver {
                        override fun beforeCachePublish(path: String, generation: Long) {
                            publishEntered.countDown()
                            check(releasePublish.await(5, TimeUnit.SECONDS))
                        }

                        override fun closeLinearizedBeforeCancellation() {
                            closeLinearized.countDown()
                        }
                    },
            )
        val subscriber =
            async(Dispatchers.Default) { loader.load("catalog-assets/close-publish/room.png") }
        val decoded = checkNotNull(decodedBitmaps.poll(5, TimeUnit.SECONDS))
        assertTrue(publishEntered.await(5, TimeUnit.SECONDS))

        val close = async(Dispatchers.Default) { loader.close() }
        assertTrue(closeLinearized.await(5, TimeUnit.SECONDS))
        releasePublish.countDown()
        close.await()

        assertTrue(runCatching { subscriber.await() }.exceptionOrNull() is CancellationException)
        assertEquals(1, sourceCalls.get())
        assertTrue(decoded.isRecycled)
        assertZeroResources(loader)
    }

    @Test
    fun `cache commit before last detach is terminal and never cancels completed transfer`() =
        runTest {
            val bytes = bitmapBytes(Bitmap.CompressFormat.PNG)
            val published = CountDownLatch(1)
            val releaseCompletion = CountDownLatch(1)
            val completionCleanup = CountDownLatch(1)
            val sourceCalls = AtomicInteger()
            val cancelCalls = AtomicInteger()
            val staleCancelDecisions = AtomicInteger()
            val loader =
                BoundedCatalogMediaLoader(
                    transferSource =
                        CatalogMediaTransferSource {
                            sourceCalls.incrementAndGet()
                            object : CatalogMediaTransfer {
                                override suspend fun read(maximumBytes: Long): CatalogMediaPayload =
                                    CatalogMediaPayload(bytes)

                                override fun cancel() {
                                    cancelCalls.incrementAndGet()
                                }
                            }
                        },
                    lifecycleObserver =
                        object : CatalogMediaFlightLifecycleObserver {
                            override fun cachePublishedBeforeCompletion(
                                path: String,
                                generation: Long,
                            ) {
                                published.countDown()
                                check(releaseCompletion.await(5, TimeUnit.SECONDS))
                            }

                            override fun lastLeaseRemovedBeforeCancel(
                                path: String,
                                generation: Long,
                            ) {
                                staleCancelDecisions.incrementAndGet()
                            }

                            override fun beforeCompletionCleanup(
                                path: String,
                                generation: Long,
                            ) {
                                completionCleanup.countDown()
                            }
                        },
                )
            val path = "catalog-assets/publish-wins/room.png"
            val departing = async(Dispatchers.Default) { loader.load(path) }
            assertTrue(published.await(5, TimeUnit.SECONDS))

            departing.cancel(CancellationException("detach after terminal cache commit"))
            departing.join()
            releaseCompletion.countDown()
            assertTrue(completionCleanup.await(5, TimeUnit.SECONDS))
            val cached = loaded(loader.load(path))

            assertEquals(1, sourceCalls.get())
            assertEquals(0, staleCancelDecisions.get())
            assertEquals(0, cancelCalls.get())
            assertTrue(!cached.isRecycled)
            loader.close()
            assertEquals(0, cancelCalls.get())
            assertZeroResources(loader)
        }

    @Test
    fun `one hundred twenty eight concurrent cancels and close cancel one transfer once`() =
        runTest {
            val readEntered = CompletableDeferred<Unit>()
            val readExited = CompletableDeferred<Unit>()
            val firstCancel = CompletableDeferred<Unit>()
            val cancelCalls = AtomicInteger()
            val loader =
                BoundedCatalogMediaLoader(
                    transferSource =
                        CatalogMediaTransferSource {
                            object : CatalogMediaTransfer {
                                override suspend fun read(maximumBytes: Long): CatalogMediaPayload {
                                    readEntered.complete(Unit)
                                    try {
                                        awaitCancellation()
                                    } finally {
                                        readExited.complete(Unit)
                                    }
                                }

                                override fun cancel() {
                                    cancelCalls.incrementAndGet()
                                    firstCancel.complete(Unit)
                                }
                            }
                        }
                )
            val subscriber =
                async(Dispatchers.Default) {
                    loader.load("catalog-assets/concurrent-cancel/room.png")
                }
            readEntered.await()
            val ready = CompletableDeferred<Unit>()
            val trigger = CompletableDeferred<Unit>()
            val readyCount = AtomicInteger()
            val cancellers =
                List(128) { index ->
                    async(Dispatchers.Default) {
                        if (readyCount.incrementAndGet() == 128) ready.complete(Unit)
                        trigger.await()
                        subscriber.cancel(CancellationException("concurrent cancel $index"))
                    }
                }
            ready.await()
            val close =
                async(Dispatchers.Default) {
                    trigger.await()
                    loader.close()
                }

            trigger.complete(Unit)
            cancellers.awaitAll()
            subscriber.join()
            firstCancel.await()
            readExited.await()
            close.await()

            assertEquals(1, cancelCalls.get())
            loader.close()
            assertEquals(1, cancelCalls.get())
            assertZeroResources(loader)
        }

    @Test
    fun `first screen cancellation before destination subscribes cancels orphan and starts fresh`() =
        runTest {
            val bytes = bitmapBytes(Bitmap.CompressFormat.PNG)
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val sourceCalls = AtomicInteger()
            val loader =
                BoundedCatalogMediaLoader(
                    CatalogMediaByteSource { _, _ ->
                        if (sourceCalls.incrementAndGet() == 1) {
                            entered.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                cancelled.complete(Unit)
                            }
                        }
                        bytes
                    }
                )

            val departingScreen = async { loader.load("catalog-assets/route-switch/room.png") }
            entered.await()
            departingScreen.cancel(CancellationException("detail screen left"))
            departingScreen.join()
            cancelled.await()

            assertTrue(
                loader.load("catalog-assets/route-switch/room.png") is CatalogMediaLoadResult.Loaded
            )
            assertEquals(2, sourceCalls.get())
            loader.close()
        }

    @Test
    fun `replacement attaching in the last detach zero removal window remains loaded`() = runTest {
        val bytes = bitmapBytes(Bitmap.CompressFormat.PNG)
        val sourceEntered = CompletableDeferred<Unit>()
        val releaseSource = CompletableDeferred<Unit>()
        val zeroWindowEntered = CountDownLatch(1)
        val releaseZeroWindow = CountDownLatch(1)
        val replacementAttached = CountDownLatch(1)
        val attachmentCount = AtomicInteger()
        val blockFirstDetach = AtomicBoolean(true)
        val loader =
            BoundedCatalogMediaLoader(
                source =
                    CatalogMediaPayloadSource { _, _ ->
                        sourceEntered.complete(Unit)
                        releaseSource.await()
                        CatalogMediaPayload(bytes)
                    },
                lifecycleObserver =
                    object : CatalogMediaFlightLifecycleObserver {
                        override fun subscriberAttached(
                            path: String,
                            generation: Long,
                            leaseId: Long,
                        ) {
                            if (attachmentCount.incrementAndGet() == 2) {
                                replacementAttached.countDown()
                            }
                        }

                        override fun beforeSubscriberDetach(
                            path: String,
                            generation: Long,
                            leaseId: Long,
                        ) {
                            if (blockFirstDetach.compareAndSet(true, false)) {
                                zeroWindowEntered.countDown()
                                check(releaseZeroWindow.await(5, TimeUnit.SECONDS))
                            }
                        }
                    },
            )
        val path = "catalog-assets/last-detach-race/room.png"
        val departing = async(Dispatchers.Default) { loader.load(path) }
        sourceEntered.await()

        departing.cancel(CancellationException("old destination detached"))
        assertTrue(zeroWindowEntered.await(5, TimeUnit.SECONDS))
        val replacement = async(Dispatchers.Default) { loader.load(path) }
        assertTrue(replacementAttached.await(5, TimeUnit.SECONDS))
        releaseZeroWindow.countDown()
        departing.join()
        releaseSource.complete(Unit)

        assertTrue(replacement.await() is CatalogMediaLoadResult.Loaded)
        loader.close()
        assertZeroResources(loader)
    }

    @Test
    fun `old flight cancellation cannot target a fresh replacement generation`() = runTest {
        val bytes = bitmapBytes(Bitmap.CompressFormat.PNG)
        val firstReadEntered = CompletableDeferred<Unit>()
        val firstReadCancelled = CompletableDeferred<Unit>()
        val removedBeforeCancel = CountDownLatch(1)
        val releaseOldCancel = CountDownLatch(1)
        val replacementAttached = CountDownLatch(1)
        val sourceCalls = AtomicInteger()
        val oldCancelCalls = AtomicInteger()
        val generations = LinkedBlockingQueue<Long>()
        val loader =
            BoundedCatalogMediaLoader(
                transferSource =
                    CatalogMediaTransferSource {
                        val call = sourceCalls.incrementAndGet()
                        object : CatalogMediaTransfer {
                            override suspend fun read(maximumBytes: Long): CatalogMediaPayload {
                                if (call == 1) {
                                    firstReadEntered.complete(Unit)
                                    try {
                                        awaitCancellation()
                                    } finally {
                                        firstReadCancelled.complete(Unit)
                                    }
                                }
                                return CatalogMediaPayload(bytes)
                            }

                            override fun cancel() {
                                if (call == 1) oldCancelCalls.incrementAndGet()
                            }
                        }
                    },
                lifecycleObserver =
                    object : CatalogMediaFlightLifecycleObserver {
                        override fun subscriberAttached(
                            path: String,
                            generation: Long,
                            leaseId: Long,
                        ) {
                            generations.put(generation)
                            if (generations.size == 2) replacementAttached.countDown()
                        }

                        override fun lastLeaseRemovedBeforeCancel(
                            path: String,
                            generation: Long,
                        ) {
                            removedBeforeCancel.countDown()
                            check(releaseOldCancel.await(5, TimeUnit.SECONDS))
                        }
                    },
            )
        val path = "catalog-assets/fresh-generation-race/room.png"
        val departing = async(Dispatchers.Default) { loader.load(path) }
        firstReadEntered.await()

        departing.cancel(CancellationException("old generation detached"))
        assertTrue(removedBeforeCancel.await(5, TimeUnit.SECONDS))
        val replacement = async(Dispatchers.Default) { loader.load(path) }
        assertTrue(replacementAttached.await(5, TimeUnit.SECONDS))
        releaseOldCancel.countDown()
        firstReadCancelled.await()

        assertTrue(replacement.await() is CatalogMediaLoadResult.Loaded)
        departing.join()
        assertEquals(2, generations.toSet().size)
        assertEquals(2, sourceCalls.get())
        assertEquals(1, oldCancelCalls.get())
        loader.close()
        assertZeroResources(loader)
    }

    @Test
    fun `completion cleanup racing last detach cannot remove a replacement generation`() = runTest {
        val bytes = bitmapBytes(Bitmap.CompressFormat.PNG)
        val firstSourceEntered = CompletableDeferred<Unit>()
        val releaseFirstSource = CompletableDeferred<Unit>()
        val completionCleanupEntered = CountDownLatch(1)
        val releaseCompletionCleanup = CountDownLatch(1)
        val firstLeaseDetached = CountDownLatch(1)
        val replacementAttached = CountDownLatch(1)
        val sourceCalls = AtomicInteger()
        val firstGeneration = AtomicInteger()
        val loader =
            BoundedCatalogMediaLoader(
                source =
                    CatalogMediaPayloadSource { _, _ ->
                        if (sourceCalls.incrementAndGet() == 1) {
                            firstSourceEntered.complete(Unit)
                            releaseFirstSource.await()
                            error("transient first generation failure")
                        }
                        CatalogMediaPayload(bytes)
                    },
                lifecycleObserver =
                    object : CatalogMediaFlightLifecycleObserver {
                        override fun subscriberAttached(
                            path: String,
                            generation: Long,
                            leaseId: Long,
                        ) {
                            if (firstGeneration.compareAndSet(0, generation.toInt())) return
                            if (generation != firstGeneration.get().toLong()) {
                                replacementAttached.countDown()
                            }
                        }

                        override fun subscriberDetached(
                            path: String,
                            generation: Long,
                            leaseId: Long,
                        ) {
                            if (generation == firstGeneration.get().toLong()) {
                                firstLeaseDetached.countDown()
                            }
                        }

                        override fun beforeCompletionCleanup(
                            path: String,
                            generation: Long,
                        ) {
                            if (generation == firstGeneration.get().toLong()) {
                                completionCleanupEntered.countDown()
                                check(releaseCompletionCleanup.await(5, TimeUnit.SECONDS))
                            }
                        }
                    },
            )
        val path = "catalog-assets/completion-detach-race/room.png"
        val departing = async(Dispatchers.Default) { loader.load(path) }
        firstSourceEntered.await()
        releaseFirstSource.complete(Unit)
        assertTrue(completionCleanupEntered.await(5, TimeUnit.SECONDS))

        departing.cancel(CancellationException("last old lease detached during completion"))
        assertTrue(firstLeaseDetached.await(5, TimeUnit.SECONDS))
        val replacement = async(Dispatchers.Default) { loader.load(path) }
        assertTrue(replacementAttached.await(5, TimeUnit.SECONDS))
        releaseCompletionCleanup.countDown()

        assertTrue(replacement.await() is CatalogMediaLoadResult.Loaded)
        departing.join()
        assertEquals(2, sourceCalls.get())
        loader.close()
        assertZeroResources(loader)
    }

    @Test
    fun `duplicate cancellation detaches one lease and cancels its transfer exactly once`() =
        runTest {
            val readEntered = CompletableDeferred<Unit>()
            val readCancelled = CompletableDeferred<Unit>()
            val transferCancelCalls = AtomicInteger()
            val detachCalls = AtomicInteger()
            val loader =
                BoundedCatalogMediaLoader(
                    transferSource =
                        CatalogMediaTransferSource {
                            object : CatalogMediaTransfer {
                                override suspend fun read(maximumBytes: Long): CatalogMediaPayload {
                                    readEntered.complete(Unit)
                                    try {
                                        awaitCancellation()
                                    } finally {
                                        readCancelled.complete(Unit)
                                    }
                                }

                                override fun cancel() {
                                    transferCancelCalls.incrementAndGet()
                                }
                            }
                        },
                    lifecycleObserver =
                        object : CatalogMediaFlightLifecycleObserver {
                            override fun subscriberDetached(
                                path: String,
                                generation: Long,
                                leaseId: Long,
                            ) {
                                detachCalls.incrementAndGet()
                            }
                        },
                )
            val subscriber =
                async(Dispatchers.Default) {
                    loader.load("catalog-assets/double-detach/room.png")
                }
            readEntered.await()

            subscriber.cancel(CancellationException("first detach request"))
            subscriber.cancel(CancellationException("duplicate detach request"))
            subscriber.join()
            readCancelled.await()

            assertEquals(1, detachCalls.get())
            assertEquals(1, transferCancelCalls.get())
            loader.close()
            assertZeroResources(loader)
        }

    @Test
    fun `one hundred twenty eight exact handoffs retain one flight for the active lease`() =
        runTest {
            val bytes = bitmapBytes(Bitmap.CompressFormat.PNG)
            val sourceEntered = CompletableDeferred<Unit>()
            val releaseSource = CompletableDeferred<Unit>()
            val sourceCalls = AtomicInteger()
            val gateDetaches = AtomicBoolean(true)
            val attachments = LinkedBlockingQueue<Long>()
            val detachGates = LinkedBlockingQueue<CountDownLatch>()
            val loader =
                BoundedCatalogMediaLoader(
                    source =
                        CatalogMediaPayloadSource { _, _ ->
                            sourceCalls.incrementAndGet()
                            sourceEntered.complete(Unit)
                            releaseSource.await()
                            CatalogMediaPayload(bytes)
                        },
                    lifecycleObserver =
                        object : CatalogMediaFlightLifecycleObserver {
                            override fun subscriberAttached(
                                path: String,
                                generation: Long,
                                leaseId: Long,
                            ) {
                                attachments.put(leaseId)
                            }

                            override fun beforeSubscriberDetach(
                                path: String,
                                generation: Long,
                                leaseId: Long,
                            ) {
                                if (!gateDetaches.get()) return
                                val release = CountDownLatch(1)
                                detachGates.put(release)
                                check(release.await(5, TimeUnit.SECONDS))
                            }
                        },
                )
            val path = "catalog-assets/repeated-handoffs/room.png"
            var active = async(Dispatchers.Default) { loader.load(path) }
            assertTrue(attachments.poll(5, TimeUnit.SECONDS) != null)
            sourceEntered.await()

            repeat(128) { handoff ->
                val departing = active
                departing.cancel(CancellationException("handoff $handoff"))
                val detachGate = checkNotNull(detachGates.poll(5, TimeUnit.SECONDS))
                active = async(Dispatchers.Default) { loader.load(path) }
                assertTrue(attachments.poll(5, TimeUnit.SECONDS) != null)
                detachGate.countDown()
                departing.join()
            }
            gateDetaches.set(false)
            releaseSource.complete(Unit)

            assertTrue(active.await() is CatalogMediaLoadResult.Loaded)
            assertEquals(1, sourceCalls.get())
            loader.close()
            assertZeroResources(loader)
        }

    @Test
    fun `close linearizes before a blocked attach and leaves no lease or flight`() = runTest {
        val attachEntered = CountDownLatch(1)
        val releaseAttach = CountDownLatch(1)
        val sourceCalls = AtomicInteger()
        val loader =
            BoundedCatalogMediaLoader(
                source =
                    CatalogMediaPayloadSource { _, _ ->
                        sourceCalls.incrementAndGet()
                        CatalogMediaPayload(bitmapBytes(Bitmap.CompressFormat.PNG))
                    },
                lifecycleObserver =
                    object : CatalogMediaFlightLifecycleObserver {
                        override fun beforeSubscriberAttach(path: String) {
                            attachEntered.countDown()
                            check(releaseAttach.await(5, TimeUnit.SECONDS))
                        }
                    },
            )
        val subscriber =
            async(Dispatchers.Default) { loader.load("catalog-assets/close-attach/room.png") }
        assertTrue(attachEntered.await(5, TimeUnit.SECONDS))

        loader.close()
        releaseAttach.countDown()

        assertTrue(runCatching { subscriber.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0, sourceCalls.get())
        assertZeroResources(loader)
    }

    @Test
    fun `first subscriber cancellation after destination joins cannot cancel destination`() =
        runTest {
            val bytes = bitmapBytes(Bitmap.CompressFormat.PNG)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sourceCalls = AtomicInteger()
            val loader =
                BoundedCatalogMediaLoader(
                    CatalogMediaByteSource { _, _ ->
                        sourceCalls.incrementAndGet()
                        entered.complete(Unit)
                        release.await()
                        bytes
                    }
                )
            val firstSubscriber = async {
                loader.load("catalog-assets/joined-destination/room.png")
            }
            entered.await()
            val activeDestination = async {
                loader.load("catalog-assets/joined-destination/room.png")
            }
            runCurrent()

            firstSubscriber.cancel(CancellationException("owning screen left"))
            firstSubscriber.join()
            release.complete(Unit)

            assertTrue(activeDestination.await() is CatalogMediaLoadResult.Loaded)
            assertEquals(1, sourceCalls.get())
            loader.close()
        }

    @Test
    fun `a cancelled concurrent waiter does not cancel the shared owner decode`() = runTest {
        val bytes = bitmapBytes(Bitmap.CompressFormat.PNG)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var sourceCalls = 0
        val loader =
            BoundedCatalogMediaLoader(
                CatalogMediaByteSource { _, _ ->
                    sourceCalls += 1
                    entered.complete(Unit)
                    release.await()
                    bytes
                }
            )
        val owner = async { loader.load("catalog-assets/waiter/room.png") }
        entered.await()
        val waiter = async { loader.load("catalog-assets/waiter/room.png") }
        runCurrent()
        waiter.cancel(CancellationException("screen left"))
        release.complete(Unit)

        assertTrue(owner.await() is CatalogMediaLoadResult.Loaded)
        assertTrue(waiter.isCancelled)
        assertEquals(1, sourceCalls)
        loader.close()
    }

    @Test
    fun `all detached subscribers cancel the orphaned flight and a new destination starts fresh`() =
        runTest {
            val bytes = bitmapBytes(Bitmap.CompressFormat.PNG)
            val firstEntered = CompletableDeferred<Unit>()
            val firstCancelled = CompletableDeferred<Unit>()
            val sourceCalls = AtomicInteger()
            val loader =
                BoundedCatalogMediaLoader(
                    CatalogMediaByteSource { _, _ ->
                        if (sourceCalls.incrementAndGet() == 1) {
                            firstEntered.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                firstCancelled.complete(Unit)
                            }
                        }
                        bytes
                    }
                )
            val first = async { loader.load("catalog-assets/all-detached/room.png") }
            val second = async { loader.load("catalog-assets/all-detached/room.png") }
            runCurrent()
            firstEntered.await()
            first.cancel(CancellationException("detail left"))
            second.cancel(CancellationException("shop owner changed"))
            first.join()
            second.join()
            runCurrent()

            firstCancelled.await()
            assertTrue(
                loader.load("catalog-assets/all-detached/room.png") is CatalogMediaLoadResult.Loaded
            )
            assertEquals(2, sourceCalls.get())
            loader.close()
        }

    @Test
    fun `sixteen same path subscribers share one flight and one cached bitmap`() = runTest {
        val bytes = bitmapBytes(Bitmap.CompressFormat.PNG)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sourceCalls = AtomicInteger()
        val loader =
            BoundedCatalogMediaLoader(
                CatalogMediaByteSource { _, _ ->
                    sourceCalls.incrementAndGet()
                    entered.complete(Unit)
                    release.await()
                    bytes
                }
            )
        val subscribers = List(16) { async { loader.load("catalog-assets/sixteen/room.png") } }
        runCurrent()
        entered.await()
        assertEquals(1, sourceCalls.get())
        release.complete(Unit)

        val bitmaps = subscribers.awaitAll().map(::loaded)
        bitmaps.drop(1).forEach { assertSame(bitmaps.first(), it) }
        assertSame(
            bitmaps.first(),
            loaded(loader.load("catalog-assets/sixteen/room.png")),
        )
        assertEquals(1, sourceCalls.get())
        loader.close()
    }

    @Test
    fun `account route switch isolates different paths while same path remains single flight`() =
        runTest {
            val bytes = bitmapBytes(Bitmap.CompressFormat.PNG)
            val bothEntered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val totalCalls = AtomicInteger()
            val callsByPath = ConcurrentHashMap<String, AtomicInteger>()
            val loader =
                BoundedCatalogMediaLoader(
                    CatalogMediaByteSource { path, _ ->
                        callsByPath.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                        if (totalCalls.incrementAndGet() == 2) bothEntered.complete(Unit)
                        release.await()
                        bytes
                    }
                )
            val departing = async { loader.load("catalog-assets/account-a/room.png") }
            val samePathDestination = async { loader.load("catalog-assets/account-a/room.png") }
            val switchedAccount = async { loader.load("catalog-assets/account-b/room.png") }
            runCurrent()
            bothEntered.await()
            departing.cancel(CancellationException("route replaced"))
            departing.join()
            release.complete(Unit)

            assertTrue(samePathDestination.await() is CatalogMediaLoadResult.Loaded)
            assertTrue(switchedAccount.await() is CatalogMediaLoadResult.Loaded)
            assertEquals(1, callsByPath.getValue("catalog-assets/account-a/room.png").get())
            assertEquals(1, callsByPath.getValue("catalog-assets/account-b/room.png").get())
            loader.close()
        }

    @Test
    fun `shared loader failure reaches remaining subscribers and transient retry starts fresh`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sourceCalls = AtomicInteger()
            val loader =
                BoundedCatalogMediaLoader(
                    CatalogMediaByteSource { _, _ ->
                        sourceCalls.incrementAndGet()
                        entered.complete(Unit)
                        release.await()
                        error("storage unavailable")
                    }
                )
            val departing = async { loader.load("catalog-assets/failure/room.png") }
            val destination = async { loader.load("catalog-assets/failure/room.png") }
            runCurrent()
            entered.await()
            departing.cancel(CancellationException("owner left"))
            departing.join()
            release.complete(Unit)

            assertEquals(
                CatalogMediaFallbackReason.DOWNLOAD_FAILED,
                fallback(destination.await()).reason,
            )
            assertEquals(
                CatalogMediaFallbackReason.DOWNLOAD_FAILED,
                fallback(loader.load("catalog-assets/failure/room.png")).reason,
            )
            assertEquals(2, sourceCalls.get())
            loader.close()
        }

    @Test
    fun `runtime close cancels loader flights and every active subscriber without leaking work`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val sourceCancelled = CompletableDeferred<Unit>()
            val sourceCalls = AtomicInteger()
            val loader =
                BoundedCatalogMediaLoader(
                    CatalogMediaByteSource { _, _ ->
                        sourceCalls.incrementAndGet()
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            sourceCancelled.complete(Unit)
                        }
                    }
                )
            val first = async { loader.load("catalog-assets/runtime-close/room.png") }
            val second = async { loader.load("catalog-assets/runtime-close/room.png") }
            runCurrent()
            entered.await()

            loader.close()
            sourceCancelled.await()

            assertTrue(runCatching { first.await() }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching { second.await() }.exceptionOrNull() is CancellationException)
            assertTrue(
                runCatching { loader.load("catalog-assets/runtime-close/room.png") }
                    .exceptionOrNull() is CancellationException
            )
            assertEquals(1, sourceCalls.get())
            loader.close()
        }

    @Test
    fun `last subscriber detach cancels transfer exactly once and releases every resource`() =
        runTest {
            val readEntered = CompletableDeferred<Unit>()
            val readCancelled = CompletableDeferred<Unit>()
            val cancelCalled = CompletableDeferred<Unit>()
            val cancelCalls = AtomicInteger()
            val loader =
                BoundedCatalogMediaLoader(
                    transferSource =
                        CatalogMediaTransferSource {
                            object : CatalogMediaTransfer {
                                override suspend fun read(maximumBytes: Long): CatalogMediaPayload {
                                    readEntered.complete(Unit)
                                    try {
                                        awaitCancellation()
                                    } finally {
                                        readCancelled.complete(Unit)
                                    }
                                }

                                override fun cancel() {
                                    cancelCalls.incrementAndGet()
                                    cancelCalled.complete(Unit)
                                }
                            }
                        }
                )
            val first = async { loader.load("catalog-assets/transfer-cancel/room.png") }
            val second = async { loader.load("catalog-assets/transfer-cancel/room.png") }
            runCurrent()
            readEntered.await()

            first.cancel(CancellationException("detail left"))
            first.join()
            assertEquals(0, cancelCalls.get())
            second.cancel(CancellationException("warehouse left"))
            second.join()

            cancelCalled.await()
            readCancelled.await()
            assertEquals(1, cancelCalls.get())
            loader.close()
            assertEquals(
                CatalogMediaResourceSnapshot(0, 0, 0, 0, 0, 1, MAX_CATALOG_MEDIA_BYTES, 0),
                loader.resourceSnapshot(),
            )
        }

    @Test
    fun `sixteen path route churn stays inside global transfer byte and decode budgets`() =
        runTest {
            val active = AtomicInteger()
            val started = AtomicInteger()
            val peak = AtomicInteger()
            val saturated = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val loader =
                BoundedCatalogMediaLoader(
                    source =
                        CatalogMediaPayloadSource { _, _ ->
                            val current = active.incrementAndGet()
                            started.incrementAndGet()
                            peak.updateAndGet { maxOf(it, current) }
                            if (current == MAX_CATALOG_MEDIA_CONCURRENT_FLIGHTS) {
                                saturated.complete(Unit)
                            }
                            try {
                                release.await()
                                CatalogMediaPayload(bitmapBytes(Bitmap.CompressFormat.PNG))
                            } finally {
                                active.decrementAndGet()
                            }
                        },
                    boundsReader = testBoundsReader,
                )
            val subscribers =
                List(16) { index ->
                    async { loader.load("catalog-assets/churn-$index/room.png") }
                }
            saturated.await()

            val saturatedSnapshot = loader.resourceSnapshot()
            assertEquals(MAX_CATALOG_MEDIA_CONCURRENT_FLIGHTS, active.get())
            assertEquals(MAX_CATALOG_MEDIA_CONCURRENT_FLIGHTS, peak.get())
            assertEquals(MAX_CATALOG_MEDIA_CONCURRENT_FLIGHTS, started.get())
            assertEquals(
                MAX_CATALOG_MEDIA_IN_FLIGHT_BYTES,
                saturatedSnapshot.reservedInFlightBytes,
            )
            assertTrue(saturatedSnapshot.activeTransfers <= MAX_CATALOG_MEDIA_CONCURRENT_FLIGHTS)

            subscribers.forEach { it.cancel(CancellationException("rapid route churn")) }
            subscribers.joinAll()
            loader.close()
            val finalSnapshot = loader.resourceSnapshot()
            assertEquals(0, finalSnapshot.flights)
            assertEquals(0, finalSnapshot.subscribers)
            assertEquals(0, finalSnapshot.activeTransfers)
            assertEquals(0, finalSnapshot.reservedInFlightBytes)
            assertEquals(0, finalSnapshot.activeDecodes)
            assertTrue(finalSnapshot.peakReservedInFlightBytes <= MAX_CATALOG_MEDIA_IN_FLIGHT_BYTES)
            assertTrue(finalSnapshot.peakActiveDecodes <= MAX_CATALOG_MEDIA_CONCURRENT_DECODES)
        }

    @Test
    fun `bounded stream rejects over limit without buffering or reading beyond one sentinel byte`() {
        val maximum = 32L
        val input = CountingInputStream(ByteArrayInputStream(ByteArray(128)))

        val failure = runCatching { readCatalogMediaStream(input, maximum) }.exceptionOrNull()

        assertTrue(failure is java.io.IOException)
        assertTrue(input.bytesRead <= maximum + 1)
    }

    @Test
    fun `last detach interrupts decode and releases decode and byte budgets`() = runTest {
        val bytes = bitmapBytes(Bitmap.CompressFormat.PNG)
        val decodeEntered = CompletableDeferred<Unit>()
        val decodeInterrupted = CompletableDeferred<Unit>()
        val holdDecode = CountDownLatch(1)
        val loader =
            BoundedCatalogMediaLoader(
                source = CatalogMediaPayloadSource { _, _ -> CatalogMediaPayload(bytes) },
                decoder =
                    CatalogBitmapDecoder { _, _ ->
                        decodeEntered.complete(Unit)
                        try {
                            holdDecode.await()
                            null
                        } catch (error: InterruptedException) {
                            decodeInterrupted.complete(Unit)
                            throw error
                        }
                    },
            )
        val subscriber = async { loader.load("catalog-assets/decode-cancel/room.png") }
        decodeEntered.await()

        subscriber.cancel(CancellationException("route left during decode"))
        subscriber.join()
        decodeInterrupted.await()
        loader.close()

        val snapshot = loader.resourceSnapshot()
        assertEquals(0, snapshot.flights)
        assertEquals(0, snapshot.activeTransfers)
        assertEquals(0, snapshot.reservedInFlightBytes)
        assertEquals(0, snapshot.activeDecodes)
        assertEquals(1, snapshot.peakActiveDecodes)
    }

    @Test
    fun `loader shutdown cancels transfer joins work and leaves zero resource counters`() =
        runTest {
            val readEntered = CompletableDeferred<Unit>()
            val readExited = CompletableDeferred<Unit>()
            val transferCancelled = AtomicBoolean(false)
            val loader =
                BoundedCatalogMediaLoader(
                    transferSource =
                        CatalogMediaTransferSource {
                            object : CatalogMediaTransfer {
                                override suspend fun read(maximumBytes: Long): CatalogMediaPayload {
                                    readEntered.complete(Unit)
                                    try {
                                        awaitCancellation()
                                    } finally {
                                        readExited.complete(Unit)
                                    }
                                }

                                override fun cancel() {
                                    transferCancelled.set(true)
                                }
                            }
                        }
                )
            val first = async { loader.load("catalog-assets/shutdown/room.png") }
            val second = async { loader.load("catalog-assets/shutdown/room.png") }
            runCurrent()
            readEntered.await()

            loader.close()

            readExited.await()
            assertTrue(transferCancelled.get())
            assertTrue(runCatching { first.await() }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching { second.await() }.exceptionOrNull() is CancellationException)
            val snapshot = loader.resourceSnapshot()
            assertEquals(0, snapshot.flights)
            assertEquals(0, snapshot.subscribers)
            assertEquals(0, snapshot.activeTransfers)
            assertEquals(0, snapshot.reservedInFlightBytes)
            assertEquals(0, snapshot.activeDecodes)
        }

    @Test
    fun `invalid paths empty and oversized payloads fail before unsafe media decode`() = runTest {
        var sourceCalls = 0
        val loader =
            BoundedCatalogMediaLoader(
                CatalogMediaByteSource { path, _ ->
                    sourceCalls += 1
                    if (path.contains("empty")) byteArrayOf()
                    else ByteArray((MAX_CATALOG_MEDIA_BYTES + 1).toInt())
                }
            )

        assertEquals(
            CatalogMediaFallbackReason.INVALID_PATH,
            fallback(loader.load("plant-photos/user-a/private.jpg")).reason,
        )
        assertEquals(0, sourceCalls)
        assertEquals(
            CatalogMediaFallbackReason.EMPTY_PAYLOAD,
            fallback(loader.load("catalog-assets/empty/preview.jpg")).reason,
        )
        assertEquals(
            CatalogMediaFallbackReason.ENCODED_SIZE_EXCEEDED,
            fallback(loader.load("catalog-assets/oversized/preview.jpg")).reason,
        )
        assertEquals(2, sourceCalls)
    }

    private class CountingInputStream(input: ByteArrayInputStream) : FilterInputStream(input) {
        var bytesRead: Long = 0
            private set

        override fun read(): Int = super.read().also { if (it >= 0) bytesRead += 1 }

        override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int = super.read(bytes, offset, length).also { if (it > 0) bytesRead += it }
    }

    private val testBoundsReader = CatalogImageBoundsReader(::readTestBounds)

    private fun readTestBounds(bytes: ByteArray): CatalogMediaBounds? {
        if (
            bytes.size >= 24 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte()
        ) {
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            return CatalogMediaBounds(header.getInt(16), header.getInt(20), "image/png")
        }
        if (
            bytes.size >= 12 &&
                bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
                bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
        ) {
            val marker = "VP8L".toByteArray()
            val index = bytes.indexOf(marker)
            if (index < 0 || index + 13 > bytes.size) return null
            val packed = ByteBuffer.wrap(bytes, index + 9, 4).order(ByteOrder.LITTLE_ENDIAN).int
            return CatalogMediaBounds(
                width = (packed and 0x3FFF) + 1,
                height = ((packed ushr 14) and 0x3FFF) + 1,
                contentType = "image/webp",
            )
        }
        if (
            bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte()
        ) {
            var index = 2
            while (index + 8 < bytes.size) {
                if (bytes[index].toInt() and 0xFF != 0xFF) {
                    index += 1
                    continue
                }
                val marker = bytes[index + 1].toInt() and 0xFF
                if (
                    marker in
                        setOf(
                            0xC0,
                            0xC1,
                            0xC2,
                            0xC3,
                            0xC5,
                            0xC6,
                            0xC7,
                            0xC9,
                            0xCA,
                            0xCB,
                            0xCD,
                            0xCE,
                            0xCF,
                        )
                ) {
                    val height =
                        ((bytes[index + 5].toInt() and 0xFF) shl 8) or
                            (bytes[index + 6].toInt() and 0xFF)
                    val width =
                        ((bytes[index + 7].toInt() and 0xFF) shl 8) or
                            (bytes[index + 8].toInt() and 0xFF)
                    return CatalogMediaBounds(width, height, "image/jpeg")
                }
                if (marker == 0xD8 || marker == 0xD9) {
                    index += 2
                } else {
                    val length =
                        ((bytes[index + 2].toInt() and 0xFF) shl 8) or
                            (bytes[index + 3].toInt() and 0xFF)
                    if (length < 2) return null
                    index += 2 + length
                }
            }
        }
        return null
    }

    private fun mediaIdentity(
        itemId: String,
        bytes: ByteArray,
        mimeType: String,
        width: Int,
        height: Int,
        mediaRevision: Long = 1,
    ): CatalogMediaIdentity {
        val digest =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
        val extension =
            when (mimeType) {
                "image/png" -> "png"
                "image/jpeg" -> "jpg"
                else -> "webp"
            }
        return CatalogMediaIdentity(
            path = "catalog-assets/$itemId/$digest.$extension",
            sha256 = digest,
            byteSize = bytes.size.toLong(),
            mimeType = mimeType,
            width = width,
            height = height,
            mediaRevision = com.planterior.helper.core.model.Revision(mediaRevision),
        )
    }

    private fun CatalogMediaIdentity.objectMetadata() =
        CatalogMediaObjectMetadata(
            contentType = mimeType,
            sizeBytes = byteSize,
            width = width,
            height = height,
            sha256 = sha256,
            mediaRevision = mediaRevision.value,
        )

    private fun assertZeroResources(loader: BoundedCatalogMediaLoader) {
        val snapshot = loader.resourceSnapshot()
        assertEquals(0, snapshot.flights)
        assertEquals(0, snapshot.subscribers)
        assertEquals(0, snapshot.activeTransfers)
        assertEquals(0, snapshot.reservedInFlightBytes)
        assertEquals(0, snapshot.activeDecodes)
    }

    private fun loaded(result: CatalogMediaLoadResult): Bitmap {
        assertTrue("Expected loaded media but was $result", result is CatalogMediaLoadResult.Loaded)
        return (result as CatalogMediaLoadResult.Loaded).bitmap
    }

    private fun fallback(result: CatalogMediaLoadResult): CatalogMediaLoadResult.Fallback {
        assertTrue(
            "Expected fallback media but was $result",
            result is CatalogMediaLoadResult.Fallback,
        )
        return result as CatalogMediaLoadResult.Fallback
    }

    private fun bitmapBytes(
        format: Bitmap.CompressFormat,
        alpha: Boolean = false,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(61, 102, 66))
        if (alpha) bitmap.setPixel(0, 0, Color.TRANSPARENT)
        return ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(format, 100, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun solidPng(width: Int, height: Int): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { png ->
            png.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            png.writePngChunk("IHDR", pngHeader(width, height))
            val compressed =
                ByteArrayOutputStream().use { bytes ->
                    DeflaterOutputStream(bytes).use { deflater ->
                        val row = ByteArray(width * 4 + 1)
                        repeat(height) { deflater.write(row) }
                    }
                    bytes.toByteArray()
                }
            png.writePngChunk("IDAT", compressed)
            png.writePngChunk("IEND", byteArrayOf())
        }
        return output.toByteArray()
    }

    private fun pngWithDeclaredBounds(width: Int, height: Int): ByteArray {
        val bytes = solidPng(1, 1)
        val header = pngHeader(width, height)
        header.copyInto(bytes, destinationOffset = 16)
        val crc =
            CRC32().apply {
                update("IHDR".toByteArray(Charsets.US_ASCII))
                update(header)
            }
        ByteBuffer.wrap(bytes, 29, 4).order(ByteOrder.BIG_ENDIAN).putInt(crc.value.toInt())
        return bytes
    }

    private fun pngHeader(width: Int, height: Int): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeInt(width)
                data.writeInt(height)
                data.writeByte(8)
                data.writeByte(6)
                data.writeByte(0)
                data.writeByte(0)
                data.writeByte(0)
            }
            bytes.toByteArray()
        }

    private fun jpegWithDeclaredBounds(width: Int, height: Int): ByteArray {
        val bytes = bitmapBytes(Bitmap.CompressFormat.JPEG)
        var index = 2
        while (index + 8 < bytes.size) {
            if (bytes[index].toInt() and 0xFF != 0xFF) {
                index += 1
                continue
            }
            val marker = bytes[index + 1].toInt() and 0xFF
            if (
                marker in
                    setOf(
                        0xC0,
                        0xC1,
                        0xC2,
                        0xC3,
                        0xC5,
                        0xC6,
                        0xC7,
                        0xC9,
                        0xCA,
                        0xCB,
                        0xCD,
                        0xCE,
                        0xCF,
                    )
            ) {
                bytes[index + 5] = (height ushr 8).toByte()
                bytes[index + 6] = height.toByte()
                bytes[index + 7] = (width ushr 8).toByte()
                bytes[index + 8] = width.toByte()
                return bytes
            }
            if (marker == 0xD8 || marker == 0xD9) {
                index += 2
            } else {
                val length =
                    ((bytes[index + 2].toInt() and 0xFF) shl 8) or
                        (bytes[index + 3].toInt() and 0xFF)
                index += 2 + length
            }
        }
        error("JPEG fixture has no SOF marker")
    }

    private fun webpWithDeclaredBounds(width: Int, height: Int): ByteArray {
        require(width in 1..16_384 && height in 1..16_384)
        val bytes = ByteArray(26)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(bytes, destinationOffset = 0)
        ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(18)
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(bytes, destinationOffset = 8)
        "VP8L".toByteArray(Charsets.US_ASCII).copyInto(bytes, destinationOffset = 12)
        ByteBuffer.wrap(bytes, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(5)
        bytes[20] = 0x2F
        val packed = (width - 1) or ((height - 1) shl 14)
        ByteBuffer.wrap(bytes, 21, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(packed)
        return bytes
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        for (index in 0..size - needle.size) {
            if (needle.indices.all { this[index + it] == needle[it] }) return index
        }
        return -1
    }

    private fun DataOutputStream.writePngChunk(type: String, bytes: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        writeInt(bytes.size)
        write(typeBytes)
        write(bytes)
        val crc =
            CRC32().apply {
                update(typeBytes)
                update(bytes)
            }
        writeInt(crc.value.toInt())
    }

    private fun ceilDivision(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
}
