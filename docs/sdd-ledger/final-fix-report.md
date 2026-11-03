# Final fix wave report (brief: docs/sdd-ledger/final-fix-brief.md)

Status: DONE_WITH_CONCERNS (every brief item done; CI fully green, e2e included, on the last code commit fc3c566). Base 8eaace8 (52e0c4d plus the controller's ledger commit). Commits 5d2f3bb..fc3c566 (code), then this report on
`claude/dsh-mobile-cloud-handoff-vfhvdh`.

## Per brief item

RED evidence: "behavioural RED" means the new test ran against the old code and failed with the quoted message; "compile RED"
means the test needs an API the fix introduces, so before the fix it did not compile (noted where that is the only RED).

### 1. I-1 gateway path allow-list (5d2f3bb)
- `gateway/LocalGateway.kt:122-133` routes `deepseek`/`anthropic` and answers any rest path that is not allowed with
  `400 {"error":"dshm_bad_path"}` before anything is forwarded or a key is read; `:289-297` `isAllowedPath` (only
  `[A-Za-z0-9._~/-]`, so no `%`, `;` or `\`; no `.`/`..` segment); `PATH_CHARS` in the companion. `isConfinedTo` is unchanged and
  still checks the built URL. The stray KDoc ("Dot segments and backslashes...") was removed from `isDroppedRequestHeader` and the
  new KDoc sits on `isAllowedPath`.
- Tests (`LocalGatewayTest`): `anythingOutsideThePathAllowListIs400AndNeverForwarded` (raw request lines for both providers:
  `..%2fv1`, `..%2Fv1`, `v1%2fchat`, `..;/v1`, `%252e%252e`, `/v1/messages;x`, `%20`, `@`, `/./`, `/a/../`; 400 and zero upstream
  requests), `anAllowedPathWithAQueryIsStillForwarded` (`/v1/messages?beta=true`, `/v1/messages_~x-1.2` reach the upstream with
  the right path), `pathTraversalIsRejected` now expects 400.
- RED (behavioural, first full run on the old gateway): 562 tests, 2 failed: `pathTraversalIsRejected` "..: HTTP/1.1 404 Not
  Found", `anythingOutsideThePathAllowList...` SocketTimeoutException (`..%2fv1` was forwarded to the upstream). GREEN: 31/31.

### 2. I-2 main-screen rollback as an application job (bb83948)
- `ui/MainActivity.kt:330-375`: `rollback()` runs through `app.settingsJobs.launch` (application scope, one job at a time with
  Settings' apply/update/rollback/restart); the step runs in `withContext(NonCancellable)` and any exception, a stop-cancelled
  start (CancellationException) included, becomes `Failed` (closes the 11a deferred "throws skips the reconcile"). A running dsh
  is handed to the service (`app.supervise()`); the toast uses the application context; the screen, if not destroyed, follows the
  real state (`showRealStateAfterRollback`). While another job runs the rollback is refused with the new string
  `main_rollback_busy`.
- `DshMobileApp.kt:76-92`: `supervise()` clears `serviceGaveUp` and starts DshService; a refused start
  (ForegroundServiceStartNotAllowedException) is logged (`could not start the service (<class>)`), never thrown.
  `startDshService` is a `@VisibleForTesting` seam. `MainActivity.superviseAndShow` (Retry) and Settings' `supervise` use it.
- Tests (`MainActivityTest`): `aRollbackOutlivesTheActivityThatStartedIt` (activity destroyed right after confirming: the switch,
  the new start and the toast still happen), `aServiceStartRefusedInTheBackgroundAfterARollbackIsLoggedNotACrash`,
  `aRollbackIsRefusedWhileASettingsJobRuns`.
- RED (behavioural): outlives -> "timed out" (nothing switched); refused-start -> the coroutine died with
  `Exception in thread "... Main Thread @coroutine#458" android.app.ForegroundServiceStartNotAllowedException` (on a device this
  kills the process; under Robolectric the test then failed on the missing log line); mutual exclusion -> "nothing was switched
  while the other job runs expected:<0.2.[1]> but was:<0.2.[0]>". GREEN: all ui tests (182 at that point).

### 3. I-3 release gate (289ef3a), YAML parsed with PyYAML, shell logic dry-run locally
- `.github/workflows/release.yml`: new first job `gate` (tag push only, `permissions: checks: read, contents: read`,
  `GITHUB_TOKEN`) lists `repos/<repo>/commits/<sha>/check-runs` (github-actions app) and requires a `success` for `android`,
  `plugins`, `rootfs (arm64)`, `rootfs (x86_64)` and `e2e` (the real ci.yml job/check names); otherwise it fails with "Get CI green
  on this commit first, then tag it again". Any successful run of a job on that exact commit counts, because a tag push also
  starts ci.yml on the same commit (no branch filter there) and that run is still in progress. `release` needs `gate`; the dry run
  (workflow_dispatch) runs with `gate` skipped.
- New step before the build: the tag's versionCode must be greater than the versionCode parsed from the releases repo's
  `releases/latest` tag (`v<x.y.z>+<code>`); a 404 (no release yet) passes.
- Publish: `gh release create --draft`, `gh release upload --clobber` of the .apk and .sha256, a check that the draft has exactly
  those two assets, then `gh release edit <tag> --draft=false`.
- Ruling recorded in the workflow header: the rootfs is still built fresh at release (dsh pinned by version and integrity;
  transitive npm and Debian packages float; accepted).
- Local check: the gate's awk logic against a sample `runs.tsv` (success, pending+failure), the versionCode comparison and the
  asset-name comparison were run in bash. The workflow itself can only run after this branch is on the default branch (ledger).

### 4. I-4 vendored Termux debs (c3e5403)
- `third_party/termux/{arm64-v8a,x86_64}/` holds the six debs (proot 5.1.107.102, libtalloc 2.5.0, libandroid-shmem 0.7; 312 KB
  total; the exact files the build used, sha256 equal to the pins). `third_party/termux/README.md`: source URLs, versions,
  sha256, licences, source-code locations, update steps.
- `android/app/proot.gradle.kts`: `readDeb()` reads `third_party/termux/<abi>/<file>`, checks the pinned sha256, no network
  (`java.net.URI`/download code removed); the debs are task inputs.
- Verification: deleted `src/main/jniLibs` and the old download cache, ran `:app:fetchProot`: the eight extracted `.so` files are
  byte-identical (sha256) to the previous ones. `BundledNativeLibsTest` 2/2 green.

### 5. I-5 user data outside the rootfs, single version source (c03d346; e2e path part also there)
- `runtime/AppPaths.kt` (new): `rootfs`, `userdata/workspace` <-> `/root/workspace`, `userdata/dsh-home` <-> `/root/.dsh`,
  `binds`, `ensureUserDirs()`, `migrateLegacy()` (per bind: legacy dir inside the rootfs is a real, non-empty directory and the new
  place does not exist -> one atomic rename, empty mount point recreated; never overwrites; symlinks not followed).
- `runtime/ProotRunner.kt`: `binds` constructor parameter; each start creates both ends and adds `-b <host>:<guest>` before `-w`.
- `DshMobileApp.kt`: `appPaths`; ProotRunner gets `appPaths.binds`; `AgentConfigWriter(appPaths.dshHome)`;
  `Exporter(appPaths.workspace)`; `onCreate` runs the migration (logged, never throws); `RootfsInstaller(... beforeReplace =
  { appPaths.migrateLegacy() })` (a failing move fails the install, so the old tree with the data stays); the controller's new
  `beforeLaunch` creates the user dirs and calls `dshVersions.current()` (repairs a broken link).
- `runtime/RootfsInstaller.kt`: `beforeReplace` hook just before `swapIn`; it only ever touches rootfs, rootfs.tmp, rootfs.old.
- `service/DshController.kt`: `beforeLaunch` parameter replaces the host `mkdirs` of the workspace inside the rootfs; guest env uses
  `AppPaths.GUEST_*`.
- `runtime/DshVersions.kt:56-71`: `bundledVersion` parameter; `current()` repairs a missing/dangling `current` link to the bundled
  version (atomic link swap, logged). Production passes `BuildConfig.DSH_PINNED_VERSION`.
- Single version source: `android/app/build.gradle.kts:34-70` reads `rootfs/versions.env`; `BuildConfig.ROOTFS_VERSION =
  "<ROOTFS_VERSION>-<hash8>"` (currently `1-8c32af4b`), hash = sha256 over the `sha256sum` lines of every regular file under
  `rootfs/` and `plugins/` (pruned: `node_modules`, `dist`, `.turbo`, `coverage`, `*.tsbuildinfo`; no symlinks followed), sorted
  bytewise; the hard-coded `"1"` is gone. `rootfs/source-hash.sh` computes the same; `build-rootfs.sh` writes
  `<ROOTFS_VERSION>-<hash>` as the marker, `smoke-test.sh` expects it (and now also checks `/root/.dsh`), `inner-setup.sh` creates
  `/root/.dsh` as a mount point. `.gitattributes` keeps `rootfs/**` and `plugins/**` LF on every checkout (a CRLF checkout on
  Windows would change the hash).
- Tests: `AppPathsTest` (5: layout and binds, legacy move, never overwrite, empty/missing legacy, legacy symlink not followed),
  `RootfsInstallerTest.reinstallingTheRootfsKeepsTheUserDataAndMovesLegacyDataOut` (three installs: legacy data moved out on the
  first reinstall, data in userdata survives the next, filesDir holds only `rootfs` and `userdata`), `ProotRunnerTest.
  userDataIsBoundIntoTheGuestAndBothEndsExist`, `DshVersionsTest` (4: dangling link -> bundled, missing link -> bundled, valid
  link untouched, no bundled dir -> still an error), `DshControllerTest` (hook runs once before launch; a failing hook is a failed
  start with no launch and no patch left), `RootfsVersionTest` (BuildConfig values come from versions.env; `source-hash.sh`
  prints the same hash as Gradle), `UserDataPathsScanTest` (no `rootfs/root/(workspace|.dsh)` or host-relative
  `"root/(workspace|.dsh)` in `src/main` or `src/androidTest`).
- RED: the scan test's pattern matched 5 places before the change (AgentConfigWriter KDoc, DshController mkdirs, DshMobileApp
  x2, E2eTest memoryDir); the other tests are compile RED (AppPaths, binds, beforeReplace, beforeLaunch, bundledVersion are new).
  GREEN: full suite 602/602.
- M-9: `DSH_PINNED_VERSION` is kept and now derived from versions.env and used (DshVersions' bundled fallback, e2e t5).

### 6. I-6 download finished in the background (2b959fd)
- `ui/MainActivity.kt:420-425`: after a verified download, if the activity is not at least STARTED,
  `notifier.showUpdateReady(info)` instead of starting the installer.
- Test `aDownloadThatEndsWhileTheAppIsInTheBackgroundPostsTheReadyNoticeInsteadOfTheInstaller` (APK response held until the
  activity is stopped). RED: "timed out" (no notice, installer started). Two existing tests that only `create()`d the activity and
  expected the installer now start/resume it first.

### 7. I-7 WebChromeClient and workspace zip (fb561d7)
- `ui/DshChromeClient.kt` (new): `onShowFileChooser` -> `FileChooserParams.createIntent()` -> `openPicker`; every request is
  answered exactly once (an older unanswered one gets null; no picker -> null at once; cancel -> null); `onPickerResult` parses
  OK + data/clipData. `onJsAlert/Confirm/Prompt` are not overridden (WebView default dialogs). `MainActivity.kt:64` registers
  `StartActivityForResult`, `:194` installs the client. No permission added (ManifestPermissionsTest green).
- `export/Exporter.kt:93` `workspaceZip()` -> `cache/export/dsh-workspace-<stamp>.zip`, `walkFileTree` without FOLLOW_LINKS,
  only regular files stored, relative names. Settings: `SettingsEnv.exportWorkspace`, button "匯出整個工作區（zip）"
  (`export-workspace`, `SettingsScreen.kt:474`), run through the existing `export()` on Dispatchers.IO; SettingsActivity shares
  the zip.
- Tests: `MainActivityTest` (picker result URI, cancel -> null, no picker -> null, JS dialogs return false = default),
  `ExporterTest` (all regular files with relative names; symlinks to a file/dir outside, a link climbing up and an inside link are
  all left out; missing workspace -> valid empty zip), `SettingsScreenTest.exportButtonsRunTheExports` (workspace button). RED:
  compile (new class/method/env field; before, `webChromeClient` was null).

### 8. I-8 START_NOT_STICKY (2f9e1fa)
- `service/DshService.kt:51`. Test `theServiceIsNotRestartedByTheSystemAfterItsProcessDies` (null intent). RED: "expected:<2>
  but was:<1>". GREEN.

### 9. Minor items
- M-1 (1ad3a16): `HeartbeatDeps.serviceGaveUp`; `HeartbeatWorker.kt:70` skips the cold start and the dream, logs `skipped: dsh gave
  up after repeated crashes`, returns success; the daily update check still runs. Test
  `afterAGiveUpTheHeartbeatNeitherStartsDshNorDreamsAndSucceeds`; RED (guard disabled): "no dsh was started expected:<0> but
  was:<1>".
- M-2 (bb83948): `SettingsJobs(scope, holders)` retains `settings-job` before the job starts and, in a NonCancellable IO finally,
  releases it and calls `stopIfUnretained()`. `SettingsJobsTest` (3; compile RED: new constructor).
- M-3 (b10da7f): `OnboardingScreen.kt:201` `remember` instead of `rememberSaveable`. Test
  `theTypedKeyIsNotKeptInSavedInstanceState` (StateRestorationTester). RED: "the key survived a save/restore of the instance
  state".
- M-4 (794b036): `Proxy.NO_PROXY` in `DshClient` (health check, heartbeat, e2e) and `DownloadHandler`, the two loopback clients
  (every 127.0.0.1 OkHttp use goes through one of them; the gateway's upstream client and the update clients reach the internet
  and keep the system proxy). Tests with a base client pointed at a dead proxy. RED: `ConnectException: Failed to connect to
  /127.0.0.1:1` and `Failed(reason=network or file error)`.
- M-5 (f576109): `AndroidKeyVault.decryptOrNull` also catches `java.security.ProviderException`. `AndroidKeyVaultTest` (3). RED:
  `java.security.ProviderException: Keystore operation failed` escaped.
- M-6 (5dd8b60): `plugins/packages/memory/src/store.ts` `loadStartupContext` caps at `STARTUP_CONTEXT_MAX_BYTES` = 32 KiB (UTF-8),
  priority MEMORY.md, USER.md, today, yesterday; cut at a character boundary, last line `STARTUP_CONTEXT_TRUNCATED`. vitest: 4 new
  (cap + note, newer note kept over older, no split multi-byte char, under-cap untouched). RED: 3 failed (constants undefined).
- M-7 (adde969): `CrashPolicy` default clock `SystemClock::elapsedRealtime`; the app uses the default. `CrashPolicyClockTest` (2).
  RED: "expected:<RESTART> but was:<GIVE_UP>" (the app's policy ignored an 11-minute SystemClock advance).
- M-9: see item 5 (kept and used).
- M-10 (289ef3a): rootfs cache key `hashFiles('rootfs/**', 'plugins/**', '.github/workflows/ci.yml')`.

### 10. Task 13 leftovers (e2e531e)
- t3 records the daily notes' byte sizes before the prompt and looks for `E2E-MEMORY-MARK` only in the appended part.
- t4 calls the new `DailyUpdateCheck.markCheckedToday()` first (unit test `markingTodayCheckedSkipsTodaysCheckButNotTomorrows`),
  so the heartbeat does not contact GitHub.
- The e2e suite reads the workspace through `app.appPaths.workspace`; t5 passes the bundled version to DshVersions.

### 11. Not done (parked by Ruling): M-8, M-11.

## Deviations from the brief
1. I-1: a disallowed path is a 400 for both providers; `/v1` itself is not required as a prefix (the brief's rule set, not the
   review's regex). Unknown routes stay 404.
2. I-3: the gate accepts any successful check run of each job on the tagged commit, not only the newest, because the tag push
   itself starts a ci.yml run on the same commit that is still in progress when the gate runs.
3. I-4: the README states libtalloc's licence as Termux declares it (GPL-3.0) and notes upstream talloc's LGPL-3.0-or-later;
   libandroid-shmem is BSD-3-Clause. The brief said "GPL-2.0／LGPL"; I wrote what the packages actually carry. Source tarballs are
   not vendored, only their locations are given.
4. I-5: the hash excludes `coverage/` and `*.tsbuildinfo` in addition to the named build products; USER.md keeps its place right
   after MEMORY.md in the M-6 order (the brief names only MEMORY.md and the daily notes).
5. I-5: the migration runs in `Application.onCreate` (two directory checks, normally nothing to move) and again right before a
   rootfs reinstall swaps the old tree out; there is no installed base yet, so it is a safety net.
6. M-2: after a job, `stopIfUnretained()` stops dsh when nobody holds it. With the app in the background and the service refused
   (I-2's case) that means dsh stops right after the rollback instead of at the end of the UI grace. This follows the holder
   convention the brief names.

## Verification
- Android unit tests: 602 tests, 0 failures, 0 skipped (local `--rerun-tasks` on fc3c566; CI android job also 602) (`./gradlew :app:testDebugUnitTest`, 0 failures, 0 skipped).
- `pnpm -C plugins test` 117/117, `typecheck` clean, `build` ok; `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` ok.
- Hash agreement checked locally: `rootfs/source-hash.sh` = Gradle's `1-8c32af4b`, unchanged by `pnpm build`.

## CI
| Run | Commit | android | plugins | rootfs arm64 / x86_64 | e2e |
|---|---|---|---|---|---|
| 41562692034 | e2e531e (all fixes) | failure: `DshControllerTest.stdoutAndStderrGoToTheMaskedErrorLogButNotTheLaunchLine` (pre-existing race, see below) | success | success / success (fresh builds: new cache key) | **success** 7/7 |
| **41566251655** | **fc3c566** (test race fix) | **success** (602) | **success** | **success / success** (cache hit, smoke test run) | **success** 7/7 |

Final green run: **41566251655** on fc3c566. e2e on it: t1 sakura 182.6 s (includes the rootfs install), t2 0.6 s, t3 memory 1.6 s,
t4 heartbeat 0.1 s, t5 broken update rolled back 26.6 s, 11a external link 11.5 s, 11a sakura 4.9 s. t3 finding the mark in
`userdata/workspace/memory/<today>.md` (host side) is the runtime proof that the proot bind of the workspace works on Android;
t5 runs on the rootfs' `/opt/dsh` with the bundled-version fallback wired.

The android failure in 41562692034 was not caused by this wave: the test waited for stdout's last line only, while stderr has its
own reader, so `[dsh] boom token=***` could still be missing. fc3c566 waits for both streams (test-only change).

## Self-review
- Permissions: still exactly 9 (ManifestPermissionsTest green; the file picker uses the Activity Result API, no storage
  permission).
- dsh source untouched (no change under the rootfs' dsh install; plugins and app only).
- No `rootfs/root/workspace` or `rootfs/root/.dsh` host path in main or androidTest (enforced by UserDataPathsScanTest).
- No secret in new log lines or test messages: logs carry class names or fixed text; the e2e test prints no URL or token.

## Concerns
1. Not verified on the real phone (arm64, Android 17): the proot binds onto `/root/workspace` and `/root/.dsh` (verified on the
   x86_64 API 34 emulator by the e2e suite), the system file picker hand-off into the WebView (the WebView keeps
   `allowContentAccess = false`; whether Chromium's upload of a picked `content://` document is affected is unverified), and which
   of file inputs / JS dialogs / downloads dsh's UI actually uses.
2. release.yml (gate, versionCode check, draft-then-publish) cannot run until this branch is on the default branch; the first tag
   is its first real test. The gate needs ci.yml to have run on the exact tagged commit (tag a commit CI already passed).
3. third_party/termux carries GPL/LGPL binaries with pointers to their source, not the source tarballs themselves; if the boss
   wants a self-contained written offer, vendor the three source tarballs too.
4. M-2 consequence: when a rollback finishes with the app in the background and Android refused the service start, nobody holds
   dsh and it stops right after the job (instead of at the end of the ten-minute UI grace). Opening the app starts it again.
5. A rootfs upgrade resets dsh to the bundled version (Ruling); a dsh version the user installed through Settings is lost then,
   although conversations and memory are kept.
6. MainActivity.kt is now about 480 lines (rollback job, chooser, update flow); a split (e.g. update flow into its own class) would
   help the next change there.
7. The Robolectric suite still runs real `sh` processes with timing waits; one more such race was fixed here (fc3c566).
