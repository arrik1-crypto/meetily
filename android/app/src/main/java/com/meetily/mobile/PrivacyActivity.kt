package com.meetily.mobile

import android.os.Bundle
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
    }
}
