plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release inputs, all optional so a plain build (unit tests, CI's android job) is unchanged:
//  -Pdshm.versionName / -Pdshm.versionCode  override the defaults (release tags are v<versionName>+<versionCode>)
//  -Pdshm.abi=arm64-v8a  package only that ABI's native library (SQLCipher); the release is arm64 only (arch 1.12)
val abiProp = providers.gradleProperty("dshm.abi").orNull

// Signing comes from the environment only (the release workflow sets it); without it the release APK stays unsigned.
val keystorePath: String? = System.getenv("DSHM_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "app.dshmobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.dshmobile"
        minSdk = 34
        targetSdk = 37
        versionCode = providers.gradleProperty("dshm.versionCode").map(String::toInt).getOrElse(20)
        versionName = providers.gradleProperty("dshm.versionName").getOrElse("1.0.0")
        // Starts the fake model server and points the app at it before the app starts (src/androidTest/.../E2eRunner.kt).
        testInstrumentationRunner = "app.dshmobile.E2eRunner"
        // The public releases repository (owner/name) the daily update check reads; the same gradle.properties value the release
        // workflow publishes to. Only api.github.com is ever contacted for it.
        val releasesRepo = providers.gradleProperty("dshm.releasesRepo").getOrElse("UTAMYLOVE/dsh-mobile-releases")
        buildConfigField("String", "RELEASES_REPO", "\"$releasesRepo\"")

        if (abiProp != null) ndk { abiFilters += abiProp }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = requireNotNull(System.getenv("ANDROID_KEYSTORE_PASSWORD")) { "ANDROID_KEYSTORE_PASSWORD missing" }
                keyAlias = requireNotNull(System.getenv("ANDROID_KEY_ALIAS")) { "ANDROID_KEY_ALIAS missing" }
                keyPassword = requireNotNull(System.getenv("ANDROID_KEY_PASSWORD")) { "ANDROID_KEY_PASSWORD missing" }
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
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
            it.maxHeapSize = "2g"
            // Robolectric needs JDK-internal access on JDK 17+.
            it.jvmArgs(
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
            )
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showStackTraces = true
                showCauses = true
                stackTraceFilters(
                    org.gradle.api.tasks.testing.logging.TestStackTraceFilter.ENTRY_POINT,
                    org.gradle.api.tasks.testing.logging.TestStackTraceFilter.GROOVY,
                )
            }
            // -Pdshm.shots=<dir>: the tests that draw a screen also save a picture of it there (for the owner to look at).
            (project.findProperty("dshm.shots") as String?)?.let { dir -> it.systemProperty("dshm.shots", dir) }
        }
    }
}

dependencies {
    // The agency brain: pure Kotlin/JVM, no Android (android/core).
    implementation(project(":core"))
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // The store: one SQLCipher file (main/data/SqlStore). The native library cannot load on the JVM, so Robolectric tests open
    // the same tables through the framework helper (sqlite-framework, test only).
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    testImplementation(libs.androidx.sqlite.framework)

    // The in-app camera (T15b): the app's own viewfinder; a shot goes from memory into the media vault, never to a file or the
    // gallery. auto-value's annotations (compile-time only, not from Google Maven) are left out.
    listOf(libs.androidx.camera.core, libs.androidx.camera.camera2, libs.androidx.camera.lifecycle, libs.androidx.camera.view).forEach {
        implementation(it) { exclude(group = "com.google.auto.value") }
    }

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
    // :core's test fixtures (android/core/src/testFixtures): the Store contract, invented rules and the moving clock.
    testImplementation(testFixtures(project(":core")))

    // Instrumentation tests (src/androidTest, AgencyE2eTest): they run on an emulator in CI, locally they only have to compile.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    // The end-to-end suite: an in-process fake model (DeepSeek and Claude shapes) on 127.0.0.1.
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.work.testing)
    // The shared store contract, the invented rules and the fourteen-day simulation driver run on the phone too.
    androidTestImplementation(testFixtures(project(":core")))
    // The company is driven through Compose's test APIs (test tags); espresso 3.7.0 for the same reason as above.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.core)
    // The jank gate (deleted with dsh at the cutover, back for the conversation in T21) drives screens without Compose's test rule.
    androidTestImplementation(libs.androidx.test.uiautomator)
}

// No telemetry (arch 1.11): no analytics, crash-report or tracking library may reach the app, and no source may import one.
// CI runs it next to the unit tests; it reads the resolved runtime classpaths and the main sources of :app and :core.
val forbiddenTelemetry = listOf(
    "com.google.firebase", "com.google.android.gms:play-services-analytics", "com.google.android.gms:play-services-measurement",
    "com.crashlytics", "io.sentry", "com.bugsnag", "com.microsoft.appcenter", "com.amplitude", "com.mixpanel",
    "com.segment.analytics", "io.opentelemetry", "com.datadoghq", "com.newrelic", "com.instabug", "io.embrace", "ly.count",
)
tasks.register("verifyNoTelemetry") {
    group = "verification"
    description = "Fails when an analytics, crash-report or tracking library is on a runtime classpath or imported."
    val classpaths = listOf("debugRuntimeClasspath", "releaseRuntimeClasspath").map { configurations.getByName(it) }
    val sources = listOf(file("src/main"), rootProject.file("core/src/main"))
    inputs.files(classpaths)
    inputs.files(sources)
    doLast {
        val modules = classpaths.flatMap { c ->
            c.incoming.resolutionResult.allComponents.mapNotNull { (it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.let { m -> "${m.group}:${m.module}" } }
        }.distinct()
        val badModules = modules.filter { m -> forbiddenTelemetry.any { m.startsWith(it) } }
        val importPrefixes = forbiddenTelemetry.map { it.substringBefore(':') }
        val badImports = sources.flatMap { root ->
            root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.flatMap { f ->
                f.readLines().withIndex().filter { (_, l) -> importPrefixes.any { p -> l.contains("import $p") || l.contains("\"$p") } }
                    .map { "${f.relativeTo(rootProject.projectDir)}:${it.index + 1}" }
            }.toList()
        }
        check(badModules.isEmpty() && badImports.isEmpty()) { "telemetry found: modules=$badModules imports=$badImports" }
        logger.lifecycle("verifyNoTelemetry: ${modules.size} modules, no telemetry")
    }
}
