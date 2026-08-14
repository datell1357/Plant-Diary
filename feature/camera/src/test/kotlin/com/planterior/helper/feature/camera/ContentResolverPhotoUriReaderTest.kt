package com.planterior.helper.feature.camera

import android.content.Context
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContentResolverPhotoUriReaderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val reader = ContentResolverPhotoUriReader(context.contentResolver)

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
}
