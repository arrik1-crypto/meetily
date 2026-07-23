package com.meetily.mobile

import com.meetily.mobile.diag.CrashLog
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportFormatTest {

    @Test
    fun renderContainsAllSections() {
        val report = CrashLog.render(
            "App: Recap 3.0.0-rc2 (49)\nAndroid: 15 (SDK 35)\nDevice: Test Phone\nABI: arm64-v8a\nTime: now",
            "Thread: main",
            "java.lang.IllegalStateException: boom\n\tat com.meetily.mobile.Foo.bar(Foo.kt:1)\n"
        )
        assertTrue(report.startsWith("Recap crash report"))
        assertTrue(report.contains("App: Recap 3.0.0-rc2 (49)"))
        assertTrue(report.contains("Thread: main"))
        assertTrue(report.contains("IllegalStateException: boom"))
        assertTrue(report.contains("Foo.kt:1"))
        assertTrue(report.endsWith("\n"))
    }
}
