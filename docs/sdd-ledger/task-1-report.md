# Task 1 report: repo scaffold and CI

Status: DONE (with minor notes)

## Built
- android/: Gradle 9.8.2 wrapper, AGP 9.5.0 (built-in Kotlin, no separate kotlin plugin), version catalog, `:app` (app.dshmobile, compile/target 37, min 34, JDK 21), BuildConfig ROOTFS_VERSION="1", DSH_PINNED_VERSION="0.2.1-rc.5", RELEASES_REPO from `dshm.releasesRepo` (gradle.properties). WorkManager 2.12.0, JUnit 4.13.2, Robolectric 4.17, androidx.test:core 1.7.0.
- Manifest: exactly the 9 permissions; `tools:node="remove"` for androidx.core's `app.dshmobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (permission + uses-permission). WorkManager's own permissions were all inside the 9.
- plugins/: pnpm 11 workspace (`packages/*`; hello/ excluded), vitest 3.2.7, TS 5.9.3, lockfile; `allowBuilds: esbuild` in pnpm-workspace.yaml (pnpm 11 errors on ignored build scripts otherwise).
- .github/workflows/ci.yml: android job (temurin 21, setup-android v4, setup-gradle) and plugins job (Node 24, pnpm 11.28.10).

## TDD
RED: `./gradlew :app:testDebugUnitTest` (JAVA_HOME jdk21)
 1. First run: Robolectric `IllegalAccessException ... jdk.internal.access` -> fixed with jvmArgs --add-exports/--add-opens in testOptions.
 2. Real RED: `ManifestPermissionsTest > permissionsAreExactlyTheAllowedSet FAILED  AssertionError at ManifestPermissionsTest.kt:20` (empty manifest; merged set only had WorkManager's WAKE_LOCK, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE...).
 3. After declaring 9: still failed, extra `app.dshmobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` -> removed via tools:node.
GREEN: same command -> tests="1" failures="0" (BUILD SUCCESSFUL). `pnpm -C plugins test` -> "No test files found, exiting with code 0".

## CI
Final green run: https://github.com/UTAMYLOVE/dsh-mobile/actions/runs/41451421475 (android success, plugins success).
Fixes needed along the way: setup-android v3 fails ("Failed to find package 'tools'") -> v4; gradlew lacked exec bit -> `git update-index --chmod=+x`.

## Commits
a544f8c chore: scaffold android app, plugins workspace, CI
c79f505 ci: use setup-android v4 and setup-java v5
8336e5d ci: make gradlew executable

## Files
.github/workflows/ci.yml, .gitignore (+.idea, *.iml), android/{build,settings}.gradle.kts, android/gradle.properties, android/gradle/libs.versions.toml, android/gradle/wrapper/*, android/gradlew(.bat), android/app/build.gradle.kts, android/app/src/main/AndroidManifest.xml, android/app/src/test/kotlin/app/dshmobile/ManifestPermissionsTest.kt, plugins/{package.json,pnpm-workspace.yaml,pnpm-lock.yaml,vitest.config.ts,tsconfig.json}. android/local.properties is git-ignored (use forward slashes in sdk.dir; backslash path broke Gradle).

## Self-review / concerns
- Manifest merger prints 2 warnings ("tagged to remove other declarations but no other declaration present") for the removed androidx permission; harmless, result verified (9 permissions).
- Robolectric runs on its default SDK (not 37); no @Config sdk pin was needed. Later tasks may need one.
- No launcher activity/icon yet (not in scope). CI still emits Node 20 deprecation annotations for checkout@v4 / setup-node@v4 etc.
- Task commit message got two follow-up CI fix commits rather than one.
