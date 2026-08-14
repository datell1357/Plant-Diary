package com.planterior.helper.feature.camera

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContentResolverPhotoUriReaderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val reader = ContentResolverPhotoUriReader(context.contentResolver)

    @Test
    fun `reader decodes actual JPEG bytes at the URI seam`() {
        val jpeg = encodedBitmapFile("real.jpg", Bitmap.CompressFormat.JPEG)

        val result = PhotoValidator(reader).validate(Uri.fromFile(jpeg).toString())

        assertTrue(result.toString(), result is PhotoValidation.Valid)
        val photo = (result as PhotoValidation.Valid).photo
        assertEquals(PhotoMime.Jpeg, photo.mime)
        assertEquals(320, photo.width)
        assertEquals(480, photo.height)
    }

    @Test
    fun `reader decodes actual PNG bytes at the URI seam`() {
        val png = encodedBitmapFile("real.png", Bitmap.CompressFormat.PNG)

        val result = PhotoValidator(reader).validate(Uri.fromFile(png).toString())

        assertTrue(result.toString(), result is PhotoValidation.Valid)
        val photo = (result as PhotoValidation.Valid).photo
        assertEquals(PhotoMime.Png, photo.mime)
        assertEquals(320, photo.width)
        assertEquals(480, photo.height)
    }

    @Test
    fun `reader decodes actual WebP bytes at the URI seam`() {
        val webp = encodedBitmapFile("real.webp", Bitmap.CompressFormat.valueOf("WEBP"))

        val result = PhotoValidator(reader).validate(Uri.fromFile(webp).toString())

        assertTrue(result.toString(), result is PhotoValidation.Valid)
        val photo = (result as PhotoValidation.Valid).photo
        assertEquals(PhotoMime.Webp, photo.mime)
        assertEquals(320, photo.width)
        assertEquals(480, photo.height)
    }

    @Test
    fun `missing and corrupt files remain distinct typed failures`() {
        assertEquals(
            PhotoProbe.Missing,
            reader.probe(Uri.fromFile(File(temporaryFolder.root, "missing.jpg")).toString()),
        )
        val corrupt =
            temporaryFolder.newFile("corrupt.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(PhotoProbe.Corrupt, reader.probe(Uri.fromFile(corrupt).toString()))
    }

    @Test
    fun `reader stops after the maximum instead of decoding oversized content`() {
        val oversized = temporaryFolder.newFile("oversized.jpg")
        oversized.outputStream().buffered().use { output ->
            val block = ByteArray(1024)
            repeat((PhotoLimits.MaxBytes / block.size).toInt() + 1) { output.write(block) }
        }

        val probe = reader.probe(Uri.fromFile(oversized).toString())

        assertTrue(probe is PhotoProbe.Readable)
        val result = PhotoValidator(reader).validate(Uri.fromFile(oversized).toString())
        assertEquals(PhotoError.TooLarge, (result as PhotoValidation.Invalid).error)
    }

    private fun encodedBitmapFile(name: String, format: Bitmap.CompressFormat): File =
        temporaryFolder.newFile(name).also { file ->
            val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
            file.outputStream().use { output -> check(bitmap.compress(format, 90, output)) }
            bitmap.recycle()
        }
}
