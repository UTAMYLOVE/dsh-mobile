# Task 12 report: shell app self-update and release workflow

Status: DONE_WITH_CONCERNS (see Concerns).

## Final signatures

```kotlin
// update/ApkUpdateChecker.kt
data class ReleaseInfo(val versionCode: Int, val versionName: String, val apkUrl: String, val sha256: String)
class ApkUpdateChecker(http: OkHttpClient, repo: String, currentVersionCode: Int,
                       apiBase: HttpUrl = "https://api.github.com".toHttpUrl(),
                       allowedDownloadPrefixes: List<String> = DEFAULT_ALLOWED_PREFIXES) {
    suspend fun latest(): ReleaseInfo?          // null for anything unusable; network IOException propagates (caller logs)
    companion object { val DEFAULT_ALLOWED_PREFIXES = listOf("https://github.com/", "https://objects.githubusercontent.com/")
                       fun isAllowed(url: String, prefixes: List<String>): Boolean }
}
// update/ApkInstaller.kt
class ApkInstaller(context: Context, http: OkHttpClient, allowedPrefixes: List<String> = ApkUpdateChecker.DEFAULT_ALLOWED_PREFIXES) {
    suspend fun download(info: ReleaseInfo): File?     // cacheDir/update/dsh-mobile-<code>.apk, null on any failure / hash mismatch
    fun install(apk: File)                              // FileProvider URI + ACTION_VIEW, or the unknown-sources settings page
    internal fun buildInstallIntent(apk: File): Intent
}
// update/DailyUpdateCheck.kt  (new, not in the brief: the testable "once per Taipei day" unit)
class DailyUpdateCheck(prefs: SharedPreferences, check: suspend () -> ReleaseInfo?, onAvailable: (ReleaseInfo) -> Unit,
                       errorLog: ErrorLog, clock: Clock = Clock.systemUTC()) { suspend fun runIfDue() }   // PREFS = "dshm_update"
// heartbeat/HeartbeatWorker.kt: HeartbeatDeps(..., updateCheck: DailyUpdateCheck? = null)
// notify/Notifier.kt: fun showUpdateAvailable(info: ReleaseInfo); const val ACTION_INSTALL_UPDATE = "install_update"; ID_UPDATE = 6
// ui/LaunchRequest.kt: data object InstallUpdate
// DshMobileApp: updateChecker, apkInstaller, dailyUpdateCheck (lazy), heartbeatDeps.updateCheck = dailyUpdateCheck
```

## Behaviour
- Checker: `GET {apiBase}/repos/<repo>/releases/latest`, `Accept: application/vnd.github+json`, no Authorization or cookies. Tag must match `^v(\d+\.\d+\.\d+)\+(\d+)$` (full match, versionCode must fit an Int) and its code must be greater than the current one (checked before the hash file is fetched). Exactly one asset ending in `.apk` and exactly one asset named `<apk name>.sha256`; both `browser_download_url`s must start with an allowed prefix (prefixes end in `/`, so `https://github.com.evil.example/` fails). The `.sha256` file is read bounded (4 KiB, release JSON 1 MiB), the first whitespace-separated field must be exactly 64 hex digits (lower-cased in the result). Non-2xx, bad JSON, wrong shapes -> null. `followSslRedirects(false)` (no https -> http downgrade).
- Installer: refuses a URL outside the allow-list (never fetched), streams to `.apk.part` while hashing, mismatch / non-2xx / truncated / over 1 GiB -> `.part` deleted, null. Match -> atomic rename to `.apk`. Other files in `cache/update/` are removed first; an `.apk` already there whose hash matches is reused (so the second tap after granting "install unknown apps" does not download 130 MB again). Cancellation cancels the call and rethrows.
- `install`: `canRequestPackageInstalls()` false -> `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` with `package:<id>`; else `ACTION_VIEW`, type `application/vnd.android.package-archive`, `FLAG_GRANT_READ_URI_PERMISSION`, URI from the existing FileProvider (`<id>.export`); `res/xml/file_paths.xml` gained `<cache-path name="update" path="update/"/>`. A file outside `cache/update/` makes FileProvider throw `IllegalArgumentException`. No manifest change, no new permission (ManifestPermissionsTest green, merged release manifest still has the 9 allowed).
- Daily check: `HeartbeatWorker.doWork` calls `deps.updateCheck?.runIfDue()` after going foreground and before `retain`/dsh, so it needs no rootfs and cannot change the heartbeat result. The checked day (`yyyy-MM-dd` in Asia/Taipei, computed from `clock.instant()`) is stored in SharedPreferences `dshm_update` only after the check ran to the end ("nothing new" counts). A thrown check is logged as `[update] check failed (<ExceptionClass>)` only and the next heartbeat tries again (cheap, unauthenticated). Cancellation is rethrown and does not mark the day. Update found -> `[update] release X (N) is available` + `Notifier.showUpdateAvailable`.
- Notification: alerts channel, fixed id `ID_UPDATE` (a newer notice replaces the old), tap opens MainActivity with only `action=install_update`.
- MainActivity (`LaunchRequest.InstallUpdate`): because the activity is exported the intent carries no URL or hash; it re-runs `app.updateChecker.latest()`, then `apkInstaller.download`, then `install`, with Toasts (checking / none / downloading X / failed) and `[update]` ErrorLog lines (exception class only). A second tap while running is ignored. Extras are consumed like the other actions.

## Gradle (android/app/build.gradle.kts, still CRLF)
- `dshm.versionName`, `dshm.versionCode` override `defaultConfig` (defaults `0.1.0` / `1`).
- `dshm.rootfsAbi` (`arm64` | `x86_64`) and `dshm.rootfsFile` must be set together (else the build fails with a clear message): `ndk.abiFilters` = `arm64-v8a` / `x86_64`, and a custom `StageRootfsAsset` task copies the file to `build/generated/rootfs-assets/rootfs.tar.xz`, wired in with `androidComponents.onVariants { sources.assets.addGeneratedSourceDirectory(...) }` (the plain `sourceSets.assets.srcDir(provider)` is rejected by AGP 9.5). Nothing in the source tree. Unset: nothing is bundled, ABIs unchanged.
- `androidResources.noCompress += "xz"`.
- Signing: `signingConfigs.release` is created only when `DSHM_KEYSTORE_FILE` is set; then `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` are required (clear error if missing). The release build type uses it when it exists. Otherwise `assembleRelease` yields `app-release-unsigned.apk`.

## Workflow (.github/workflows/release.yml)
Triggers `push: tags: ['v*']` and `workflow_dispatch` (input `dry_run`, boolean, default true); `permissions: contents: read`; concurrency per ref, no cancel. One job:
1. checkout
2. Resolve version (tag from `ref_name` via `env:`, validated by regex; dispatch needs `dry_run=true` else it errors; dry run uses `0.0.0` / `1`; reads `dshm.releasesRepo` from `android/gradle.properties`; outputs version_name, version_code, publish, tag, releases_repo)
3. setup-java 21, setup-android, SDK packages, setup-gradle, `./gradlew :app:testDebugUnitTest --no-daemon`
4. pnpm/node, `pnpm -C plugins install --frozen-lockfile`, `test`, `build`
5. setup-qemu (arm64), stage the three plugins (same as ci.yml), `build-rootfs.sh arm64`, `smoke-test.sh`
6. Build unsigned release APK (`-Pdshm.rootfsAbi=arm64 -Pdshm.rootfsFile=... -Pdshm.versionName/-versionCode`, values through `env:`; no secret or signing variable exists yet)
7. Inspect unsigned APK: `assets/rootfs.tar.xz` Stored, no `lib/` outside `arm64-v8a`, no `.jks/.keystore/.pem/.p12/.key` in the APK
8. Dry run: upload the unsigned APK as artifact `dsh-mobile-unsigned-apk` (7 days). The remaining steps are `if: publish == 'true'`:
9. Decode keystore (`ANDROID_KEYSTORE_BASE64` via `env:` only, `umask 077`, to `$RUNNER_TEMP/dshm-release.keystore`, path to `GITHUB_ENV`)
10. Build signed release APK (`env:` ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD, `--no-build-cache`, old outputs removed)
11. `apksigner verify --verbose --print-certs`, copy to `dist/dsh-mobile-<name>-<code>.apk`, `sha256sum` -> `.sha256` (and `sha256sum -c`)
12. `gh release create <tag> --repo <releases repo> <apk> <apk>.sha256 --title ... --notes ...` with `GH_TOKEN` = `RELEASES_TOKEN` (only this step); the public repo gets only the two files
13. Delete keystore (`if: always()`)
No step echoes a secret; none is interpolated into a script body.

## Tests (full suite: 550 tests, 0 failures, 0 errors, 0 skipped, output pristine)
New: `ApkUpdateCheckerTest` 20 (newer / same / older, versionCode not name, bad tags `v1.2`, `1.2.3+4`, `v1.2.3+x` and more, missing / foreign `.sha256`, missing / duplicate apk, bad / oversized hash file, uppercase hash, allow-list incl. look-alike host and never fetching a foreign URL, non-2xx, malformed JSON, headers and no credentials, older release skips the hash fetch, IOException propagates), `ApkInstallerTest` 13 (match -> `.apk` and no `.part`; mismatch and tampered byte -> everything deleted, null; non-2xx; truncated body; disallowed URL not fetched; stale files removed; verified apk reused; corrupted leftover replaced; install intent via FileProvider; settings page when not allowed; path outside `cache/update` refused), `DailyUpdateCheckTest` 9 (once per day, Taipei midnight vs UTC midnight, failure logged by class only and retried, announce failure contained, cancellation not swallowed or marked, persists across instances), `HeartbeatWorkerTest` +4 (two heartbeats same day -> one check; next Taipei day checks again; throwing check -> success, reminders still posted, holders released, `[update]` log; update check needs no rootfs), `NotifierTest` +2, `LaunchRequestTest` +1, `MainActivityTest` +3 (install_update downloads, verifies and opens the installer; wrong hash never reaches the installer and leaves no apk; no newer release -> toast only).

## TDD evidence
- RED: update/heartbeat/notifier/launch-request tests written first; `compileDebugUnitTestKotlin` failed with Unresolved reference `ReleaseInfo`, `DailyUpdateCheck`, `showUpdateAvailable`, no parameter `updateCheck` (the MainActivity tests were added after the implementation, so they have no separate RED).
- GREEN iteration: first run of the 5 focused groups, 124 tests, 1 failure (`ApkInstallerTest.installOpens...`: FileProvider's static path cache from an earlier Robolectric test; fixed by clearing `sCache` in `@Before`, same trick as MainActivityTest). Then `app.dshmobile.update.*`: 13 + 20 + 9, 0 failures.
- Final: `./gradlew :app:testDebugUnitTest --no-daemon -q --rerun-tasks` 550 / 0 / 0 / 0, no output; `./gradlew :app:assembleDebug --no-daemon -q` exit 0.

## Local release build evidence
- No properties, no signing env: `./gradlew :app:assembleRelease --no-daemon -q` exit 0, only `app-release-unsigned.apk`, versionCode 1 / 0.1.0, no `rootfs` entry, 9 uses-permission in the merged manifest.
- `-Pdshm.rootfsAbi=x86_64 -Pdshm.rootfsFile=<dummy tar.xz in scratchpad> -Pdshm.versionName=0.2.0 -Pdshm.versionCode=2`: `unzip -v` shows `180 Stored 180 0% ... assets/rootfs.tar.xz`; `lib/` has only `x86_64/` (libproot, libproot-loader, libtalloc, libandroidx.graphics.path); output-metadata `versionCode 2`, `versionName 0.2.0`; no generated file in the source tree.
- Same with `-Pdshm.rootfsAbi=arm64` and a throwaway keystore (temp keytool keystore in the scratchpad, passwords in env only): `app-release.apk` produced, `apksigner verify` -> Verifies (v2), only `lib/arm64-v8a`.
- `release.yml` parses with `python3 -c "import yaml..."` (triggers push + workflow_dispatch, 22 steps); the version regex and the APK inspection commands were run locally against valid / invalid tags and the x86_64 test APK.

## Files changed
Main: `update/{ApkUpdateChecker,ApkInstaller,DailyUpdateCheck}.kt` (new), `heartbeat/HeartbeatWorker.kt`, `notify/Notifier.kt`, `ui/{MainActivity,LaunchRequest}.kt`, `DshMobileApp.kt`, `res/values/strings.xml` (4 strings), `res/xml/file_paths.xml`, `app/build.gradle.kts`, `.github/workflows/release.yml` (new).
Tests: `update/{ApkUpdateCheckerTest,ApkInstallerTest,DailyUpdateCheckTest}.kt` (new), `heartbeat/HeartbeatWorkerTest.kt`, `notify/NotifierTest.kt`, `ui/{LaunchRequestTest,MainActivityTest}.kt`.

## Compile-only / not run here
- The release workflow itself (YAML only parsed; the controller runs the `dry_run` dispatch). Never exercised: the signed path (steps 9-13), `apksigner` location via `$ANDROID_HOME`, `gh release create` against the real public repo, Docker/qemu rootfs build for arm64 inside this workflow.
- The real system installer hand-off and the "install unknown apps" settings page (only the intents are asserted), the real GitHub API shape (MockWebServer stands in; `browser_download_url` and `tag_name` are the documented fields), and the redirect of release downloads to GitHub's CDN host (OkHttp follows it; only the first URL is allow-listed).

## Self-review findings (fixed)
- `Source.read(buffer, n)` is not a loop: replaced by `source.request(limit + 1)` for the bounded read.
- `MutableClock.withZone` ignores the zone, so the Taipei day is computed with `instant().atZone(TAIPEI)` (true in production and in tests).
- Download URL prefixes end in `/`, so `https://github.com.evil.example/...` and `https://github.com@evil/...` do not pass.
- The intent never carries a URL or hash (exported activity): MainActivity re-fetches from the releases repo.
- AGP 9.5 rejects `sourceSets.assets.srcDir(provider)`; switched to the Variant API with a typed task.

## Concerns
1. The download runs in `lifecycleScope`: leaving the app (activity destroyed) cancels it, and an install started after the user backgrounds the app can be blocked by Android's background-activity-start rules. The `.apk` is kept only when verified, so the next tap resumes cheaply (reuse path), but not a partial download. A foreground service or `DownloadManager` would be the fuller fix; out of scope.
2. Only the first download URL is allow-listed; GitHub redirects assets to its CDN (`release-assets.githubusercontent.com` today), which is not on the brief's two-prefix list and cannot be, so redirects are followed (https only). Integrity rests on the SHA-256 from the same release plus Android's own signature check (an update signed with another key is refused by the OS).
3. `latest()` cannot tell "no update" from "unusable release" (both null), so the daily log line is the generic `no newer release`; a persistent malformed release stays silent.
4. A failed (exception) check is retried at every heartbeat that day, not once; the unauthenticated API allows 60 requests per hour per IP, one request per 45 min is far below that.
5. The signed build is a second `assembleRelease` after the unsigned one (as the brief says); the unsigned build is cheap to repeat but doubles Gradle time.
6. The releases repo needs at least one commit on its default branch: `gh release create` creates the tag from it. No file is pushed there.
7. Dry runs (`0.0.0` / `1`) produce an APK that downgrades nothing but is only an artifact; it is never published.

## 給宇哥：發佈前要設定的 GitHub Secrets

在**私有的原始碼倉庫**（Settings -> Secrets and variables -> Actions -> New repository secret）建立下面五個 Secret。只有推送 `v<版本>+<versionCode>` 標籤（例如 `v0.2.0+2`）時才會用到；手動執行的 dry run 完全不需要。

| Secret | 內容 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | 簽章金鑰庫檔案的 base64 |
| `ANDROID_KEYSTORE_PASSWORD` | 金鑰庫密碼 |
| `ANDROID_KEY_ALIAS` | 金鑰別名（下面範例用 `dshm`） |
| `ANDROID_KEY_PASSWORD` | 金鑰密碼（可與金鑰庫密碼相同） |
| `RELEASES_TOKEN` | 能寫入公開發佈倉庫 `UTAMYLOVE/dsh-mobile-releases` 的 token（fine-grained PAT，只選那一個倉庫，權限 Contents: Read and write） |

建立金鑰庫（在自己電腦上做一次，檔案與密碼都不要放進 git，也不要貼到聊天或 log）：

```
keytool -genkeypair -v -keystore dshm-release.keystore -alias dshm \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 dshm-release.keystore   # macOS 用 base64 -i dshm-release.keystore；輸出貼到 ANDROID_KEYSTORE_BASE64
```

注意：
- 金鑰庫遺失或換掉，已安裝的手機就無法再用更新安裝（Android 要求同一把簽章金鑰），請另外備份在安全的地方。
- 公開倉庫 `dsh-mobile-releases` 至少要有一個提交（例如一個 README），`gh release create` 才能建立標籤。
- 公開倉庫只會收到 APK 和 `.sha256` 兩個檔案。

## Fix round 1
Finding: the first update could not complete without waiting a day. `installUpdate` downloaded the APK first; with `canRequestPackageInstalls()` false `install()` only opened the settings page, and the tap that started it (auto-cancelled notification, consumed `action` extra) could not be repeated.

Changes:
- `MainActivity.installUpdate`: checks `app.apkInstaller.canInstall()` before anything else. If false: sets the persisted `PendingUpdate` flag, opens `ACTION_MANAGE_UNKNOWN_APP_SOURCES` (`package:` URI) via `ApkInstaller.openInstallSettings()`, Toast `main_update_needs_permission` ("請先在設定頁允許 DSH Mobile 安裝應用程式；允許後回到這裡，更新會自動繼續"), and returns: no release lookup, no download.
- `MainActivity.onResume` (new): if the flag is set and `canInstall()` is now true, clears the flag and runs `installUpdate()` (re-fetch release, download with SHA-256 check, reuse of an already verified APK, install). Still not allowed: nothing happens, flag stays. The flag is cleared before the run so a failing attempt does not re-download on every resume. Only the update runs; dsh is not touched.
- `update/PendingUpdate.kt` (new): SharedPreferences-backed boolean (`dshm_update` / `pending_install`).
- `ApkInstaller` is now `open` with `open fun canInstall()`, `open fun openInstallSettings()`, `open fun install(...)` (test seam); `install()` KDoc corrected (it no longer claims a re-tap exists; the settings fallback only covers a grant revoked in between).
- Belt and braces: if `install()` throws (e.g. `ActivityNotFoundException`) after a verified download, MainActivity logs `[update] could not open the installer: <class>`, posts `Notifier.showUpdateReady(info)` (same `ID_UPDATE`, "DSH Mobile X 已下載，點一下安裝", tap = `action=install_update`, nothing else in the intent) and shows Toast `main_update_ready`. A tap re-runs the flow and reuses the verified APK.
- The existing test `whenThereIsNoNewerReleaseItSaysSoAndInstallsNothing` now grants install permission first (the permission check precedes the lookup).

Tests (new, `MainActivityTest` +5, `NotifierTest` +1; Robolectric, `setCanRequestPackageInstalls(false)` then `true`):
- `withoutInstallPermissionTheSettingsPageOpensFirstAndNothingIsDownloaded`: settings intent first (action and `package:` URI), MockWebServer `requestCount == 0`, flag set, toast, no file in `cache/update`.
- `resumingWithTheGrantContinuesTheUpdateAndClearsTheFlag`: after granting and `pause().resume()`: download, `ACTION_VIEW` install intent, `dsh-mobile-9.apk` present, flag cleared.
- `resumingWhileStillNotAllowedDoesNothingAndKeepsTheFlag`: no activity started, request count 0, flag kept.
- `aWrongHashAfterTheGrantStillNeverInstalls`: failed toast, nothing started, `cache/update` empty, flag not left set.
- `ifTheInstallerCannotBeLaunchedAReadyNoticeIsPostedWithTheSameAction`, `NotifierTest.updateReadyNoticeRetriesThroughTheSameAction`.

RED: with the new tests and only the resource and `open` scaffolding in place, `MainActivityTest.withoutInstallPermissionTheSettingsPageOpensFirstAndNothingIsDownloaded` failed: "no release lookup and no download before the permission expected:<0> but was:<3>" (release, hash file and APK were fetched before the settings page). GREEN after the change.

Commands and counts: `./gradlew :app:testDebugUnitTest --no-daemon -q --tests 'app.dshmobile.ui.MainActivityTest' --tests 'app.dshmobile.update.*' --tests 'app.dshmobile.notify.*' --tests 'app.dshmobile.ui.LaunchRequestTest'` (one failure: the old no-newer-release test, fixed as above) then `MainActivityTest` alone 38 tests, 0 failures; full `./gradlew :app:testDebugUnitTest --no-daemon -q --rerun-tasks`: 556 tests, 0 failures, 0 errors, 0 skipped, no output; `./gradlew :app:assembleDebug --no-daemon -q` exit 0. No manifest change (no new permission).
