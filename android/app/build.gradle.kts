import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

abstract class StageRootfsAsset : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rootfs: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val dir = outputDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        rootfs.get().asFile.copyTo(File(dir, "rootfs.tar.xz"))
    }
}

// Release inputs, all optional so a plain build (unit tests, CI's android job) is unchanged:
//  -Pdshm.versionName / -Pdshm.versionCode  override the defaults (release tags are v<versionName>+<versionCode>)
//  -Pdshm.rootfsAbi=arm64|x86_64 and -Pdshm.rootfsFile=<tar.xz>  bundle that rootfs as assets/rootfs.tar.xz and package only that ABI
val rootfsAbiProp = providers.gradleProperty("dshm.rootfsAbi").orNull
val rootfsFileProp = providers.gradleProperty("dshm.rootfsFile").orNull
check((rootfsAbiProp == null) == (rootfsFileProp == null)) { "dshm.rootfsAbi and dshm.rootfsFile must be set together" }
val rootfsNdkAbi = rootfsAbiProp?.let {
    mapOf("arm64" to "arm64-v8a", "x86_64" to "x86_64")[it] ?: error("dshm.rootfsAbi must be arm64 or x86_64, not '$it'")
}
val rootfsSource = rootfsFileProp?.let { file(it).also { f -> check(f.isFile) { "dshm.rootfsFile is not a file: $it" } } }

// The rootfs is built from rootfs/ and plugins/ (versions in rootfs/versions.env, the single source). Its version is
// ROOTFS_VERSION-<8-hex content hash of rootfs/** and plugins/**>: any plugin or rootfs change makes installed apps reinstall
// the rootfs (the user's data lives outside it). rootfs/source-hash.sh computes the same hash for the rootfs marker; the
// definition (sha256 of the `sha256sum` lines of the sorted regular files, build products excluded) must stay identical.
val repoRoot: File = rootProject.projectDir.parentFile
val versionsEnv: Map<String, String> = File(repoRoot, "rootfs/versions.env").readLines()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
    .associate { it.substringBefore('=') to it.substringAfter('=') }
fun envValue(key: String): String = requireNotNull(versionsEnv[key]?.takeIf { it.isNotBlank() }) { "rootfs/versions.env has no $key" }
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun compareBytes(a: String, b: String): Int {
    val x = a.toByteArray(Charsets.UTF_8)
    val y = b.toByteArray(Charsets.UTF_8)
    for (i in 0 until minOf(x.size, y.size)) {
        val c = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
        if (c != 0) return c
    }
    return x.size - y.size
}
val rootfsSourceHash: String = run {
    val prunedDirs = setOf("node_modules", "dist", ".turbo", "coverage")
    val files = listOf("rootfs", "plugins").flatMap { top ->
        File(repoRoot, top).walkTopDown()
            .onEnter { (it.name !in prunedDirs || it == File(repoRoot, top)) && !Files.isSymbolicLink(it.toPath()) }
            .filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) && !it.name.endsWith(".tsbuildinfo") }
            .map { it.relativeTo(repoRoot).invariantSeparatorsPath to it }
            .toList()
    }.sortedWith { a, b -> compareBytes(a.first, b.first) }
    val lines = files.joinToString("") { (rel, f) -> "${sha256Hex(f.readBytes())}  $rel\n" }
    sha256Hex(lines.toByteArray(Charsets.UTF_8)).take(8)
}
val rootfsVersion = "${envValue("ROOTFS_VERSION")}-$rootfsSourceHash"

// Signing comes from the environment only (the release workflow sets it); without it the release APK stays unsigned.
val keystorePath: String? = System.getenv("DSHM_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "app.dshmobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.dshmobile"
        minSdk = 34
        targetSdk = 37
        versionCode = providers.gradleProperty("dshm.versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("dshm.versionName").getOrElse("0.1.0")
        // Starts the fake model and points the gateway at it before the app's onCreate (see src/androidTest/.../E2eRunner.kt).
        testInstrumentationRunner = "app.dshmobile.E2eRunner"

        buildConfigField("String", "ROOTFS_VERSION", "\"$rootfsVersion\"")
        // The dsh version the rootfs ships; DshVersions falls back to it when `/opt/dsh/current` points at nothing.
        buildConfigField("String", "DSH_PINNED_VERSION", "\"${envValue("DSH_PINNED_VERSION")}\"")
        val releasesRepo = providers.gradleProperty("dshm.releasesRepo").get()
        buildConfigField("String", "RELEASES_REPO", "\"$releasesRepo\"")

        if (rootfsNdkAbi != null) ndk { abiFilters += rootfsNdkAbi }
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

    // The bundled rootfs is already xz-compressed (130 MB): storing it uncompressed avoids a second pass and lets AssetFileDescriptor.length work.
    androidResources {
        noCompress += "xz"
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

    // Model replies are parsed with commonmark-java (BSD-2, pure Java, offline) and drawn natively; see ui/markdown/Markdown.kt.
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    // JankStats: frame timing that feeds the auto-degrade of effects (ui/fog/JankGovernor.kt).
    implementation(libs.androidx.metrics.performance)

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
    // The end-to-end suite: an in-process fake model (Anthropic Messages over SSE) and the heartbeat worker run by hand.
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.work.testing)
    // The native shell is driven through Compose's test APIs (test tags); espresso 3.7.0 for the same reason as above.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.core)
    // The CI jank gate (PerfGateTest) drives the shell without Compose's test rule, which would cancel its infinite animations.
    androidTestImplementation(libs.androidx.test.uiautomator)
}

// The rootfs becomes assets/rootfs.tar.xz through a generated directory under build/ (never in the source tree).
if (rootfsSource != null) {
    val stageRootfs = tasks.register<StageRootfsAsset>("stageRootfsAsset") {
        rootfs.set(rootfsSource)
        outputDir.set(layout.buildDirectory.dir("generated/rootfs-assets"))
    }
    androidComponents.onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(stageRootfs, StageRootfsAsset::outputDir)
    }
}

// -Pdshm.nativeDir=<dir> (native/build-runtime.py's OUT): the experimental Android-native dsh runtime — node, bash, ripgrep,
// git and the file-lock addon as jniLibs/<abi>/libdshn_*.so, plus assets/native/manifest.json and cert.pem. Optional: without
// it the app has the proot runtime only and the native mode reports itself unavailable.
providers.gradleProperty("dshm.nativeDir").orNull?.let { dir ->
    val native = file(dir).also { check(File(it, "assets/native/manifest.json").isFile) { "dshm.nativeDir has no assets/native/manifest.json: $dir" } }
    android.sourceSets.getByName("main") {
        jniLibs.srcDir(File(native, "jniLibs"))
        assets.srcDir(File(native, "assets"))
    }
}

apply(from = "proot.gradle.kts")
