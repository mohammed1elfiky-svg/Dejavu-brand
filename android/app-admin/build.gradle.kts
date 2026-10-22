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
        versionCode = providers.gradleProperty("dejavu.versionCode").getOrElse("1").toInt()
        versionName = providers.gradleProperty("dejavu.versionName").getOrElse("0.1.0")
        val apiBaseUrl = providers.gradleProperty("dejavu.apiBaseUrl").get()
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        // Push notifications: values of this app in the Firebase console (empty = push off).
        fun prop(name: String) = providers.gradleProperty(name).getOrElse("")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${prop("dejavu.firebase.adminAppId")}\"")
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
    implementation(project(":core:admin"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    // Barcodes: Code 128 labels, camera scanning, label printing; drag-and-drop grid.
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded) { isTransitive = false }
    implementation(libs.reorderable)
    implementation(libs.androidx.print)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
