package com.planterior.helper.feature.camera

import androidx.exifinterface.media.ExifInterface

/** 식별 입력으로 허용하는 이미지 형식이다. */
enum class PhotoMime {
    Jpeg,
    Png,
    Webp,
    Heif,
}

/** 사진이 앱에 들어온 경로이다. */
enum class PhotoSource {
    Camera,
    Picker,
}

/** URI 경계에서 적용하는 제품 제한이다. */
object PhotoLimits {
    const val MaxBytes: Long = 20L * 1024L * 1024L
    const val MinDimension: Int = 256
    const val MaxDimension: Int = 8192
}

/** 사용자에게 대체 경로와 함께 표시할 수 있는 형식화된 사진 오류이다. */
sealed interface PhotoError {
    data object MissingUri : PhotoError

    data object Unreadable : PhotoError

    data object Corrupt : PhotoError

    data object UnsupportedMime : PhotoError

    data object TooLarge : PhotoError

    data object DimensionsOutOfRange : PhotoError

    data object CaptureFailed : PhotoError

    data object SubmissionFailed : PhotoError
}

data class PhotoMetadata(
    val mime: String?,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val exifOrientation: Int,
)

/** ContentResolver 구현과 테스트 reader가 공유하는 URI 검사 결과이다. */
sealed interface PhotoProbe {
    data class Readable(val metadata: PhotoMetadata) : PhotoProbe

    data object Missing : PhotoProbe

    data object Unreadable : PhotoProbe

    data object Corrupt : PhotoProbe
}

fun interface PhotoUriReader {
    fun probe(uri: String): PhotoProbe
}

data class ValidatedPhoto(
    val uri: String,
    val mime: PhotoMime,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val mirroredHorizontally: Boolean,
)

sealed interface PhotoValidation {
    data class Valid(val photo: ValidatedPhoto) : PhotoValidation

    data class Invalid(val error: PhotoError) : PhotoValidation
}

/** URI의 실제 바이트를 읽은 결과만 신뢰해 제품 제한을 적용한다. */
class PhotoValidator(private val reader: PhotoUriReader) {
    fun validate(uri: String?): PhotoValidation {
        if (uri.isNullOrBlank()) return PhotoValidation.Invalid(PhotoError.MissingUri)
        return when (val probe = reader.probe(uri)) {
            PhotoProbe.Missing -> PhotoValidation.Invalid(PhotoError.MissingUri)
            PhotoProbe.Unreadable -> PhotoValidation.Invalid(PhotoError.Unreadable)
            PhotoProbe.Corrupt -> PhotoValidation.Invalid(PhotoError.Corrupt)
            is PhotoProbe.Readable -> validateMetadata(uri, probe.metadata)
        }
    }

    private fun validateMetadata(uri: String, metadata: PhotoMetadata): PhotoValidation {
        if (metadata.byteSize > PhotoLimits.MaxBytes) {
            return PhotoValidation.Invalid(PhotoError.TooLarge)
        }
        val mime =
            metadata.mime.toPhotoMime()
                ?: return PhotoValidation.Invalid(PhotoError.UnsupportedMime)
        if (
            metadata.width !in PhotoLimits.MinDimension..PhotoLimits.MaxDimension ||
                metadata.height !in PhotoLimits.MinDimension..PhotoLimits.MaxDimension
        ) {
            return PhotoValidation.Invalid(PhotoError.DimensionsOutOfRange)
        }
        return PhotoValidation.Valid(
            ValidatedPhoto(
                uri = uri,
                mime = mime,
                byteSize = metadata.byteSize,
                width = metadata.width,
                height = metadata.height,
                rotationDegrees = metadata.exifOrientation.transform().rotationDegrees,
                mirroredHorizontally = metadata.exifOrientation.transform().mirroredHorizontally,
            )
        )
    }
}

private fun String?.toPhotoMime(): PhotoMime? =
    when (this?.lowercase()?.substringBefore(';')?.trim()) {
        "image/jpeg",
        "image/jpg" -> PhotoMime.Jpeg
        "image/png" -> PhotoMime.Png
        "image/webp" -> PhotoMime.Webp
        "image/heif",
        "image/heic" -> PhotoMime.Heif
        else -> null
    }

private data class ExifTransform(
    val rotationDegrees: Int,
    val mirroredHorizontally: Boolean,
)

private fun Int.transform(): ExifTransform =
    when (this) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransform(0, true)
        ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransform(180, false)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransform(180, true)
        ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransform(90, true)
        ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransform(90, false)
        ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransform(270, true)
        ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransform(270, false)
        else -> ExifTransform(0, false)
    }

data class PreparedPhoto(
    val privateUri: String,
    val mime: PhotoMime,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val source: PhotoSource,
    val mirroredHorizontally: Boolean = false,
)
