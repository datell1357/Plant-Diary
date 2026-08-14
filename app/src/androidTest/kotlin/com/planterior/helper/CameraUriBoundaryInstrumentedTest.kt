package com.planterior.helper

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.planterior.helper.feature.camera.ContentResolverPhotoUriReader
import com.planterior.helper.feature.camera.PhotoError
import com.planterior.helper.feature.camera.PhotoMime
import com.planterior.helper.feature.camera.PhotoPreparer
import com.planterior.helper.feature.camera.PhotoSource
import com.planterior.helper.feature.camera.PhotoValidation
import com.planterior.helper.feature.camera.PhotoValidator
import com.planterior.helper.feature.camera.PrivatePhotoStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraUriBoundaryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun jpegSuffixedUriReportsActualPngBytes() {
        val store = PrivatePhotoStore(context)
        val uri = writeBitmap(store, Bitmap.CompressFormat.PNG)

        val validation =
            PhotoValidator(ContentResolverPhotoUriReader(context.contentResolver)).validate(uri)

        assertTrue(validation.toString(), validation is PhotoValidation.Valid)
        val photo = (validation as PhotoValidation.Valid).photo
        assertEquals(PhotoMime.Png, photo.mime)
        assertEquals(320, photo.width)
        assertEquals(480, photo.height)
        store.delete(uri)
    }

    @Test
    fun jpegSuffixedUriReportsActualWebpBytes() {
        val store = PrivatePhotoStore(context)
        val uri = writeBitmap(store, Bitmap.CompressFormat.valueOf("WEBP"))

        val validation =
            PhotoValidator(ContentResolverPhotoUriReader(context.contentResolver)).validate(uri)

        assertTrue(validation.toString(), validation is PhotoValidation.Valid)
        val photo = (validation as PhotoValidation.Valid).photo
        assertEquals(PhotoMime.Webp, photo.mime)
        assertEquals(320, photo.width)
        assertEquals(480, photo.height)
        store.delete(uri)
    }

    @Test
    fun jpegSuffixedUriReportsActualHeicBytes() {
        val store = PrivatePhotoStore(context)
        val uri = store.allocate()
        InstrumentationRegistry.getInstrumentation().context.assets.open("real-photo.heic").use {
            input ->
            context.contentResolver.openOutputStream(Uri.parse(uri), "w").use { output ->
                input.copyTo(requireNotNull(output))
            }
        }

        val validation =
            PhotoValidator(ContentResolverPhotoUriReader(context.contentResolver)).validate(uri)

        assertTrue(validation.toString(), validation is PhotoValidation.Valid)
        val photo = (validation as PhotoValidation.Valid).photo
        assertEquals(PhotoMime.Heif, photo.mime)
        assertEquals(320, photo.width)
        assertEquals(480, photo.height)
        store.delete(uri)
    }

    @Test
    fun jpegMimeMetadataCannotMakeCorruptBytesReadable() {
        val store = PrivatePhotoStore(context)
        val uri = store.allocate()
        val parsed = Uri.parse(uri)
        assertEquals("image/jpeg", context.contentResolver.getType(parsed))
        context.contentResolver.openOutputStream(parsed, "w").use { output ->
            requireNotNull(output).write(byteArrayOf(1, 2, 3))
        }

        val validation =
            PhotoValidator(ContentResolverPhotoUriReader(context.contentResolver)).validate(uri)

        assertEquals(PhotoError.Corrupt, (validation as PhotoValidation.Invalid).error)
        store.delete(uri)
    }

    @Test
    fun privateCameraJpegIsDecodedWithExifRotation() {
        val store = PrivatePhotoStore(context)
        val uri = store.allocate()
        val parsed = Uri.parse(uri)
        val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        context.contentResolver.openOutputStream(parsed, "w").use {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, requireNotNull(it)))
        }
        bitmap.recycle()
        context.contentResolver.openFileDescriptor(parsed, "rw").use { descriptor ->
            ExifInterface(requireNotNull(descriptor).fileDescriptor).apply {
                setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_ROTATE_90.toString(),
                )
                saveAttributes()
            }
        }

        val reader = ContentResolverPhotoUriReader(context.contentResolver)
        val validation = PhotoValidator(reader).validate(uri)
        assertTrue(validation is PhotoValidation.Valid)
        val photo = (validation as PhotoValidation.Valid).photo
        assertEquals(PhotoMime.Jpeg, photo.mime)
        assertEquals(320, photo.width)
        assertEquals(480, photo.height)
        assertEquals(90, photo.rotationDegrees)

        val prepared = PhotoPreparer(PhotoValidator(reader), store).prepare(uri, PhotoSource.Camera)
        assertEquals(uri, prepared.getOrThrow().privateUri)
        store.delete(uri)
    }

    private fun writeBitmap(store: PrivatePhotoStore, format: Bitmap.CompressFormat): String {
        val uri = store.allocate()
        val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        context.contentResolver.openOutputStream(Uri.parse(uri), "w").use { output ->
            check(bitmap.compress(format, 90, requireNotNull(output)))
        }
        bitmap.recycle()
        return uri
    }
}
