package com.planterior.helper

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.feature.camera.ContentResolverPhotoUriReader
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
}
