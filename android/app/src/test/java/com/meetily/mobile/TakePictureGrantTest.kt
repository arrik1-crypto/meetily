package com.meetily.mobile

import android.content.Intent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The camera's grant is explicit (TakePictureWithGrant): Android 18 stops
 * adding it for an image capture intent that leaves it out.
 */
class TakePictureGrantTest {

    @Test
    fun theCameraMayReadAndWriteThePhoto() {
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            CameraGrant.FLAGS
        )
        assertTrue(CameraGrant.FLAGS and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertTrue(CameraGrant.FLAGS and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        // Nothing beyond those two: no persistable or prefix grant.
        assertEquals(0, CameraGrant.FLAGS and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        assertEquals(0, CameraGrant.FLAGS and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }

    /** Unit tests run from the module directory; the others are fallbacks. */
    private fun sources(): File {
        val candidates = listOf("src/main/java", "app/src/main/java", "android/app/src/main/java")
        return candidates.map(::File).firstOrNull { it.isDirectory }
            ?: run {
                fail("sources not found from ${File(".").absolutePath}")
                throw IllegalStateException()
            }
    }

    @Test
    fun noScreenLaunchesTheCameraWithTheImplicitGrant() {
        val offenders = sources().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { "ActivityResultContracts.TakePicture(" in it.readText() }
            .map { it.name }
            .toList()
        assertEquals("use TakePictureWithGrant instead", emptyList<String>(), offenders)
    }
}
