plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

// Staff-side models, API and repositories for the admin app.
dependencies {
    api(project(":core:data"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // Contract tests against a running backend: DEJAVU_API_URL=http://localhost:3000/api/v1/
    environment("DEJAVU_API_URL", System.getenv("DEJAVU_API_URL") ?: "")
    testLogging { events("passed", "skipped", "failed") }
}
