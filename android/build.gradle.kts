// The Android plugin goes on the root classpath (so the Kotlin plugin can see it), but only when
// the app is part of the build: `-PjvmOnly=true` builds never need Google Maven or the Android SDK.
buildscript {
    if (findProperty("jvmOnly") != "true") {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools.build:gradle:8.13.0") // keep in sync with agp in libs.versions.toml
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
