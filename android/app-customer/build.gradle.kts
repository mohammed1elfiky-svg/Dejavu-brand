plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dejavu.customer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dejavu.customer"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("dejavu.versionCode").getOrElse("1").toInt()
        versionName = providers.gradleProperty("dejavu.versionName").getOrElse("0.1.0")
        val apiBaseUrl = providers.gradleProperty("dejavu.apiBaseUrl").get()
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        // Google AdMob app id (Google's sample id until the store's own is set).
        manifestPlaceholders["admobAppId"] = providers.gradleProperty("dejavu.admobAppId").getOrElse("").ifBlank { "ca-app-pub-3940256099942544~3347511713" }
        // Push notifications: values of this app in the Firebase console (empty = push off).
        fun prop(name: String) = providers.gradleProperty(name).getOrElse("")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${prop("dejavu.firebase.customerAppId")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${prop("dejavu.firebase.apiKey")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${prop("dejavu.firebase.projectId")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${prop("dejavu.firebase.senderId")}\"")
    }

    // Release signing from environment variables (set by the release workflow); unsigned otherwise.
    signingConfigs {
        val keystore = System.getenv("DEJAVU_KEYSTORE_FILE")
        if (!keystore.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("DEJAVU_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DEJAVU_KEY_ALIAS")
                keyPassword = System.getenv("DEJAVU_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // Plain http is allowed only in debug builds (local/emulator servers).
            manifestPlaceholders["cleartext"] = "true"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coil.compose)
    implementation(libs.play.services.ads)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
}
