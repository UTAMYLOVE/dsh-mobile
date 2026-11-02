plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.dshmobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.dshmobile"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "ROOTFS_VERSION", "\"1\"")
        buildConfigField("String", "DSH_PINNED_VERSION", "\"0.2.1-rc.5\"")
        val releasesRepo = providers.gradleProperty("dshm.releasesRepo").get()
        buildConfigField("String", "RELEASES_REPO", "\"$releasesRepo\"")
    }

    packaging {
        // Android only execs files from nativeLibraryDir; proot ships as lib*.so and must be extracted to disk.
        jniLibs.useLegacyPackaging = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric needs JDK-internal access on JDK 17+.
            it.jvmArgs(
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // Ktor logs through SLF4J; the no-op binding keeps request URLs (which carry the session token) out of any log.
    implementation(libs.slf4j.nop)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Compose UI tests idle through Espresso; 3.5.0 (what ui-test pulls in) reflects on InputManager.getInstance(), which Android 17 no longer has.
    testImplementation(libs.androidx.test.espresso.core)
    // Declares the ComponentActivity that createComposeRule launches; debug only, so it never reaches release.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)

    // Instrumentation tests (src/androidTest) need a real dsh: they run on an emulator in CI, locally they only have to compile.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
}

apply(from = "proot.gradle.kts")
