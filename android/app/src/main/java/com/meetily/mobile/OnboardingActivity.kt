package com.meetily.mobile

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.meetily.mobile.data.AppSettings

class OnboardingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_onboarding)
        findViewById<View>(R.id.getStartedButton).setOnClickListener {
            AppSettings(this).onboardingDone = true
            finish()
        }
    }
}
