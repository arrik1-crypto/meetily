package com.meetily.mobile

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract

/**
 * ActivityResultContracts.TakePicture with the camera app's access to the
 * photo file granted explicitly. Same intent, same result.
 *
 * The camera writes into a FileProvider URI passed as EXTRA_OUTPUT. A grant
 * only covers URIs in an intent's data or ClipData, never its extras, so
 * the platform copies EXTRA_OUTPUT into ClipData and adds the read and
 * write grant flags itself when the intent is started
 * (Intent.migrateExtraStreamToClipData). Android 17's StrictMode
 * detectImplicitUriPermissionGrant reports an intent that relies on that,
 * and from Android 18 the platform no longer adds the grant: a camera handed
 * such an intent could not write the photo. Whether a given
 * androidx.activity release sets the flags is not relied on here.
 *
 * Here both are set before the intent leaves: ClipData holding the output
 * URI, and FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION.
 * With ClipData already present the platform's migration steps aside, so
 * the grant is exactly what this states on every Android version from 26.
 */
class TakePictureWithGrant : ActivityResultContract<Uri, Boolean>() {

    override fun createIntent(context: Context, input: Uri): Intent {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, input)
        intent.clipData = ClipData.newRawUri("", input)
        intent.addFlags(CameraGrant.FLAGS)
        return intent
    }

    /** As TakePicture: whether the camera reports the photo saved. */
    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        resultCode == Activity.RESULT_OK
}

/** The grant an image capture intent carries (TakePictureWithGrant). */
object CameraGrant {
    /** The camera writes the photo; read lets it show or check what it wrote. */
    const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
}
