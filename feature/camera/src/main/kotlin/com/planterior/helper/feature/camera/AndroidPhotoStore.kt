package com.planterior.helper.feature.camera

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException

/** 실제 URI 바이트를 제한 크기까지만 읽어 형식, 크기, EXIF를 검사한다. */
class ContentResolverPhotoUriReader(private val resolver: ContentResolver) : PhotoUriReader {
    override fun probe(uri: String): PhotoProbe {
        val parsed = runCatching { uri.toUri() }.getOrNull() ?: return PhotoProbe.Missing
        val bytes =
            try {
                resolver.openInputStream(parsed)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        output.write(buffer, 0, read)
                        if (total > PhotoLimits.MaxBytes) {
                            return PhotoProbe.Readable(
                                PhotoMetadata(
                                    resolver.getType(parsed),
                                    total,
                                    PhotoLimits.MinDimension,
                                    PhotoLimits.MinDimension,
                                    ExifInterface.ORIENTATION_UNDEFINED,
                                )
                            )
                        }
                    }
                    output.toByteArray()
                } ?: return PhotoProbe.Missing
            } catch (_: FileNotFoundException) {
                return PhotoProbe.Missing
            } catch (_: SecurityException) {
                return PhotoProbe.Unreadable
            } catch (_: IOException) {
                return PhotoProbe.Unreadable
            }
        if (bytes.isEmpty()) return PhotoProbe.Corrupt

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outMimeType == null) {
            return PhotoProbe.Corrupt
        }
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
        }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return PhotoProbe.Readable(
            PhotoMetadata(
                mime = options.outMimeType,
                byteSize = bytes.size.toLong(),
                width = options.outWidth,
                height = options.outHeight,
                exifOrientation = orientation,
            )
        )
    }
}

fun interface TemporaryUriFactory {
    fun create(): String
}

fun interface RequestIdFactory {
    fun create(): String
}

/** FileProvider가 노출하는 cache 하위의 앱 전용 사진 저장소이다. */
class PrivatePhotoStore(private val context: Context) {
    private val directory: File
        get() = File(context.cacheDir, "camera").apply { mkdirs() }

    fun allocate(): String {
        val file = File(directory, "photo-${UUID.randomUUID()}.jpg")
        check(file.createNewFile()) { "Temporary photo could not be allocated" }
        return uriFor(file).toString()
    }

    fun import(sourceUri: String): Result<String> = runCatching {
        val destination = File(directory, "photo-${UUID.randomUUID()}.img")
        val source = sourceUri.toUri()
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Source URI is missing" }
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        uriFor(destination).toString()
    }

    fun delete(uri: String) {
        runCatching { context.contentResolver.delete(uri.toUri(), null, null) }
    }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/** 검증을 통과한 picker 입력만 앱 전용 저장소로 복사한다. */
class PhotoPreparer(
    private val validator: PhotoValidator,
    private val store: PrivatePhotoStore,
) {
    fun prepare(uri: String?, source: PhotoSource): Result<PreparedPhoto> {
        val validated =
            try {
                validator.validate(uri)
            } catch (error: RuntimeException) {
                if (error is CancellationException) throw error
                return Result.failure(PhotoPreparationException(PhotoError.Unreadable))
            }
        if (validated is PhotoValidation.Invalid) {
            return Result.failure(PhotoPreparationException(validated.error))
        }
        val photo = (validated as PhotoValidation.Valid).photo
        val privateUri =
            if (source == PhotoSource.Picker) {
                store.import(photo.uri).getOrElse {
                    return Result.failure(PhotoPreparationException(PhotoError.Unreadable))
                }
            } else {
                photo.uri
            }
        return Result.success(
            PreparedPhoto(
                privateUri = privateUri,
                mime = photo.mime,
                byteSize = photo.byteSize,
                width = photo.width,
                height = photo.height,
                rotationDegrees = photo.rotationDegrees,
                source = source,
                mirroredHorizontally = photo.mirroredHorizontally,
            )
        )
    }
}

class PhotoPreparationException(val photoError: PhotoError) : Exception()
