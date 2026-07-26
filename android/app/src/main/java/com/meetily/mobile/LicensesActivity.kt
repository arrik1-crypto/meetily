package com.meetily.mobile

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/**
 * Attribution and licence notices for the open-source software compiled into
 * the app, and for the models it downloads.
 *
 * Apache-2.0 §4 and the MIT licence both condition binary redistribution on
 * shipping their notices, and the app redistributes three such libraries
 * inside the APK — so this screen is an obligation, not a courtesy. The
 * model attributions discharge CC BY 4.0 §3(a) for the speech models.
 *
 * The text is a verbatim asset rather than a string resource: licence bodies
 * have to be reproduced exactly, and 23 KB of legal text has no business in
 * the translated resource table.
 */
class LicensesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_licenses)
        findViewById<MaterialToolbar>(R.id.licensesToolbar).setNavigationOnClickListener {
            finish()
        }

        val body = findViewById<TextView>(R.id.licensesBody)
        // Off the main thread: laying out 23 KB in one TextView is the slow
        // part, and doing the read inline just adds to a frame that is
        // already long enough to drop.
        Thread {
            val text = try {
                assets.open(ASSET).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                getString(R.string.licenses_unavailable, e.message ?: "read failed")
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) body.text = text
            }
        }.start()
    }

    private companion object {
        const val ASSET = "notices.txt"
    }
}
