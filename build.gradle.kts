plugins {
    // Latest AGP compatible with Gradle 8.13
    id("com.android.application") version "8.13.0" apply false
    id("com.android.library") version "8.13.0" apply false

    // Kotlin Android plugin version recommended with AGP 8.13
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
}

repositories {
    google()
    mavenCentral()
    maven("https://jitpack.io")
}
