import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The agency brain: pure Kotlin/JVM. No android.* import may appear here (CoreHasNoAndroidImportsTest), so it runs on a computer too.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // The AgencyStore contract tests and MutableClock live in testFixtures so :app shares them.
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
    // JSON trees are part of :core's public API: LegacyCoachJson.decode/decodeMessage take a JsonElement (UsageParser's
    // JsonObject functions are internal).
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testFixturesApi(libs.junit)
    // runBlocking for the suspend AgencyStore API.
    testFixturesApi(libs.kotlinx.coroutines.core)
}
