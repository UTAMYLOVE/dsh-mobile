import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The agency brain: pure Kotlin/JVM. No android.* import may appear here (CoreHasNoAndroidImportsTest), so it runs on a computer too.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // The AgencyStore contract tests live in testFixtures so :app runs the same class against RoomAgencyStore.
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    // Usage bodies are read as JSON trees; the type is part of :core's API (UsageParser).
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testFixturesApi(libs.junit)
    // runBlocking for the suspend AgencyStore API.
    testFixturesApi(libs.kotlinx.coroutines.core)
}
