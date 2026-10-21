plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dejavu.admin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dejavu.admin"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        val apiBaseUrl = providers.gradleProperty("dejavu.apiBaseUrl").get()
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        // Push notifications: values of this app in the Firebase console (empty = push off).
        fun prop(name: String) = providers.gradleProperty(name).getOrElse("")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${prop("dejavu.firebase.adminAppId")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${prop("dejavu.firebase.apiKey")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${prop("dejavu.firebase.projectId")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${prop("dejavu.firebase.senderId")}\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["cleartext"] = "true"
        }
        release {
            isMinifyEnabled = false
            manifestPlaceholders["cleartext"] = "false"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:admin"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
