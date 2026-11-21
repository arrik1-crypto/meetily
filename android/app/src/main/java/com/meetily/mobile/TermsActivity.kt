package com.meetily.mobile

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar

/**
 * Recap's Terms of Use, including the model use restrictions that the
 * Gemma, Health AI Developer Foundations and Llama licences require to be
 * passed on to users (see licences.ModelTerms, which links here from the
 * acceptance asked before those models download).
 *
 * An asset rather than a string resource for the same reason as
 * notices.txt: it is legal text that should read the same in every build.
 * Unlike notices.txt it is not hard-wrapped, so it reflows to the screen.
 */
class TermsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_terms)
        // Pads for the system bars itself, so the screen reads correctly
        // whether or not the window is drawn edge to edge.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.termsRoot)) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<MaterialToolbar>(R.id.termsToolbar).setNavigationOnClickListener {
            finish()
        }

        val body = findViewById<TextView>(R.id.termsBody)
        Thread {
            val text = try {
                assets.open(ASSET).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                getString(R.string.licences_terms_unavailable, e.message ?: "read failed")
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) body.text = text
            }
        }.start()
    }

    private companion object {
        const val ASSET = "terms_of_use.txt"
    }
}
