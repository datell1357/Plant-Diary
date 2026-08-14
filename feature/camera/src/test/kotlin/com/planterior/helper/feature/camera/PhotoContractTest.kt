package com.planterior.helper.feature.camera

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoContractTest {
    private val uri = "content://picker/plant"

    @Test
    fun `every documented MIME family is accepted`() {
        listOf(
                "image/jpeg" to PhotoMime.Jpeg,
                "image/png" to PhotoMime.Png,
                "image/webp" to PhotoMime.Webp,
                "image/heif" to PhotoMime.Heif,
                "image/heic" to PhotoMime.Heif,
            )
            .forEach { (mime, expected) ->
                val result = validator(metadata(mime = mime)).validate(uri)
                assertEquals(expected, (result as PhotoValidation.Valid).photo.mime)
            }
    }

    @Test
    fun `size boundary accepts 20 MiB and rejects one byte more`() {
        assertTrue(
            validator(metadata(size = PhotoLimits.MaxBytes)).validate(uri) is PhotoValidation.Valid
        )
        assertEquals(
            PhotoError.TooLarge,
            (validator(metadata(size = PhotoLimits.MaxBytes + 1)).validate(uri)
                    as PhotoValidation.Invalid)
                .error,
        )
    }

    @Test
    fun `dimension boundaries accept 256 through 8192 and reject outside`() {
        listOf(256 to 256, 8192 to 8192, 256 to 8192, 8192 to 256).forEach { (width, height) ->
            assertTrue(
                validator(metadata(width = width, height = height)).validate(uri)
                    is PhotoValidation.Valid
            )
        }
        listOf(255 to 256, 256 to 255, 8193 to 256, 256 to 8193).forEach { (width, height) ->
            assertEquals(
                PhotoError.DimensionsOutOfRange,
                (validator(metadata(width = width, height = height)).validate(uri)
                        as PhotoValidation.Invalid)
                    .error,
            )
        }
    }

    @Test
    fun `EXIF 90 180 and 270 are mapped to preview rotation`() {
        listOf(
                ExifInterface.ORIENTATION_ROTATE_90 to 90,
                ExifInterface.ORIENTATION_ROTATE_180 to 180,
                ExifInterface.ORIENTATION_ROTATE_270 to 270,
            )
            .forEach { (orientation, expected) ->
                val result = validator(metadata(orientation = orientation)).validate(uri)
                assertEquals(expected, (result as PhotoValidation.Valid).photo.rotationDegrees)
            }
    }

    @Test
    fun `missing unreadable corrupt and unsupported URI failures stay typed`() {
        assertEquals(
            PhotoError.MissingUri,
            (validator(PhotoProbe.Missing).validate(null) as PhotoValidation.Invalid).error,
        )
        listOf(
                PhotoProbe.Missing to PhotoError.MissingUri,
                PhotoProbe.Unreadable to PhotoError.Unreadable,
                PhotoProbe.Corrupt to PhotoError.Corrupt,
                PhotoProbe.Readable(metadata(mime = "image/gif")) to PhotoError.UnsupportedMime,
            )
            .forEach { (probe, expected) ->
                assertEquals(
                    expected,
                    (validator(probe).validate(uri) as PhotoValidation.Invalid).error,
                )
            }
    }

    private fun validator(metadata: PhotoMetadata) = validator(PhotoProbe.Readable(metadata))

    private fun validator(probe: PhotoProbe) = PhotoValidator(PhotoUriReader { probe })

    private fun metadata(
        mime: String = "image/jpeg",
        size: Long = 1_024,
        width: Int = 1024,
        height: Int = 768,
        orientation: Int = ExifInterface.ORIENTATION_NORMAL,
    ) = PhotoMetadata(mime, size, width, height, orientation)
}
