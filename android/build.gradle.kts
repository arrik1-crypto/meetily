plugins {
    // 8.10 is the first AGP line whose own release notes list API 36 as
    // supported (8.9's say 35). It has the same minimums as 8.9 (Gradle
    // 8.11.1, JDK 17, default NDK 27.0.12077973), so the 8.14.3 wrapper and
    // the JDK 17 CI runners already qualify. AGP 9 is a separate migration.
    id("com.android.application") version "8.10.1" apply false
    id("com.android.library") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
