plugins {
    alias(libs.plugins.android.application)
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

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
}

apply(from = "proot.gradle.kts")
