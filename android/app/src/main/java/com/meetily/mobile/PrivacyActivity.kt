package com.meetily.mobile

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class PrivacyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_privacy)
        findViewById<MaterialToolbar>(R.id.privacyToolbar).setNavigationOnClickListener {
            finish()
        }
        // The contact address and effective date live in their own resources,
        // shared with the rest of the app and set by the owner before release.
        findViewById<TextView>(R.id.privacyPolicyBody).text = getString(
            R.string.privacy_policy_body,
            getString(R.string.support_email),
            getString(R.string.privacy_policy_effective_date)
        )
    }
}
