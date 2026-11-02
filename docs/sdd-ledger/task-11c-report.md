# Task 11c report: first-launch onboarding and phantom-process detector

Status: DONE_WITH_CONCERNS (see Concerns).

## Versions (all pinned in android/gradle/libs.versions.toml)
- Compose BOM 2026.01.01 (ui / material3 1.4.0 / runtime 1.10.2), `activity-compose` 1.12.4.
- Kotlin Compose compiler Gradle plugin `org.jetbrains.kotlin.plugin.compose` 2.2.10 = the Kotlin version AGP 9.5 bundles (built-in Kotlin; the project pins no Kotlin plugin of its own).
- Tests: `ui-test-junit4` (BOM), `ui-test-manifest` as `debugImplementation`, `espresso-core` 3.7.0 (overrides the 3.5.0 that ui-test pulls in).
- No uses-permission was added; ManifestPermissionsTest green.

## Final signatures
```kotlin
// system/PhantomProcessDetector.kt
enum class PhantomState { DISABLED, ENABLED, UNKNOWN }
class PhantomProcessDetector(resolver: ContentResolver) { fun state(): PhantomState }   // read-only, "false"/"true"/other|null|RuntimeException

// system/OnboardingState.kt
class OnboardingState(prefs: SharedPreferences) {
    enum class Step(val skippable: Boolean) { NOTIFICATIONS(true), BATTERY(true), PHANTOM(true), DEEPSEEK_KEY(false), ROOTFS(false) }
    fun isDone(step): Boolean; fun markDone(step); fun markSkipped(step)  // IllegalArgumentException for DEEPSEEK_KEY/ROOTFS
    fun clear(step)                                   // extra: forget a step whose result vanished
    fun nextStep(): Step?
    fun isComplete(rootfsInstalled: Boolean, keyStored: Boolean): Boolean
    companion object { const val PREFS = "dshm_onboarding" }
}

// ui/theme/SakuraTheme.kt
@Composable fun SakuraTheme(content: @Composable () -> Unit)
object Sakura { Background, Primary, UserBubble, TagBackground, TagText, Border, Text }  // brief colours, Compose Color

// ui/OnboardingScreen.kt
@Composable fun OnboardingScreen(
    state: OnboardingState, platform: OnboardingPlatform, vault: KeyVault, detector: PhantomProcessDetector,
    isRootfsInstalled: () -> Boolean, installRootfs: (onProgress: (Int) -> Unit) -> Unit,  // run on Dispatchers.IO
    onFinished: () -> Unit, modifier: Modifier = Modifier)

// ui/OnboardingPlatform.kt
interface OnboardingPlatform { notificationsGranted(); batteryOptimizationIgnored(); developerOptionsEnabled(); openBatterySettings(); openDeveloperSettings(); openDeviceInfoSettings() }
class AndroidOnboardingPlatform(activity: Activity) : OnboardingPlatform
object OnboardingIntents { ignoreBatteryOptimizations(pkg); developerSettings(); deviceInfoSettings(); mainActivity(pkg) }
// ui/OnboardingActivity.kt: ComponentActivity, exported=false, wires DshMobileApp.rootfsInstaller / keyVault; onFinished -> startActivity(ComponentName app.dshmobile.ui.MainActivity), finish()
```
Notifier.MAIN_ACTIVITY went from private to public const (reused by OnboardingIntents); Notifier small icon is now `R.drawable.ic_notification`.

## Behaviour notes
- Flow starts at `state.nextStep()`. Notifications (RequestPermission launcher) and battery auto-complete when already granted/ignored, re-checked on every ON_RESUME. Phantom step: tutorial + "software info" button when developer options are off, else "developer options" button; the PhantomState is re-read on ON_RESUME and shown (已關閉限制 / 限制仍開啟 / 無法判斷); "繼續" appears only when DISABLED. Key step: password field (keyboardType Password, PasswordVisualTransformation, `rememberSaveable`), enabled for trimmed length 16..512, `vault.put(DEEPSEEK, trimmed)`, field cleared on success, vault exception shows a generic error (message never shown). Rootfs step: auto-runs on IO, 0-100 progress, `FileNotFoundException` -> "找不到內建的執行環境檔案" (asset arrives with Task 12/13), other failures show the message and 重試; already-installed rootfs finishes without installing. No skip button on key/rootfs.
- Activity clears a recorded ROOTFS/DEEPSEEK_KEY step when its result no longer exists (rootfs version bump, vault wiped).
- Manifest: `configChanges` on OnboardingActivity so rotation/font change does not restart the extraction.
- Resources: strings.xml (zh-Hant), themes.xml (framework Material Light NoActionBar, pink status/nav bar), colors.xml, `ic_notification` (monochrome D), adaptive launcher icon + round + monochrome layer (pink background, white D; original art, no logos).

## Tests (new 37; whole suite 279, 0 failures / 0 errors / 0 skipped; output pristine)
PhantomProcessDetectorTest 4, OnboardingStateTest 10, OnboardingIntentsTest 3, OnboardingScreenTest 20 (Robolectric + Compose). ManifestPermissionsTest and NotifierTest still green. `./gradlew :app:assembleDebug --no-daemon -q`: exit 0.

## TDD evidence
- RED: tests written first; `compileDebugUnitTestKotlin` failed with unresolved references to OnboardingState / Step / PhantomProcessDetector / OnboardingScreen etc.
- GREEN iterations: (1) Compose tests failed 20/20 with `NoSuchMethodException: InputManager.getInstance()` (Espresso idling on the Android 17 jar; espresso 3.7.0 does not help) -> `@Config(sdk=[36])`; (2) 4 failures: test flaws (rootfs title vanished because the fake install finished; phantom state text split in a Row) and a test that matched Compose's raw `InputText` semantic (test-only; displayed Text/EditableText are masked) -> fixed; (3) 279 tests, 0 failed.

## Compile/runtime verification limits
Not exercised automatically: the real `RequestPermission` dialog, real settings intents (`AndroidOnboardingPlatform`), `OnboardingActivity` wiring and launching MainActivity (does not exist until 11a; ActivityNotFoundException is caught and the screen just closes), ON_RESUME-after-real-settings round trip (resume is simulated via `moveToState`), real keystore `AndroidKeyVault.put`, launcher icon rendering, `PhantomProcessDetector` reading a real device (Robolectric shadows Settings.Global; the throwing-resolver path is a plain catch with no test because ContentResolver's abstract members are hidden API).

## Self-review
- Key hygiene: key never logged/toString'd; only lives in the password field state and the vault; cleared after save; vault failure message not shown; test asserts it is never displayed as text.
- App never writes settings: only Intent actions that open screens.
- No new permissions; Compose pulls none.
- CRLF: libs.versions.toml and app/build.gradle.kts use CRLF in the repo; edits preserved it.

## Concerns
1. Compose tests need Robolectric SDK 36 (`@Config(sdk=[36])` on OnboardingScreenTest) because Espresso breaks on the Android 17 jar. The `android-all-instrumented-16-robolectric-13921718-i7` jar was not cached and repo1.maven.org returned 429 from Robolectric, so I copied the jar/pom (+sha512, verified) from the Google Maven Central mirror into `~/.m2/repository/org/robolectric/android-all-instrumented/`. Any other machine/CI must be able to download that jar (normal networks can). 11a/11b Compose tests will need the same `@Config(sdk=[36])`.
2. `isComplete(rootfsInstalled, keyStored)` takes the two caller-supplied facts as parameters (brief showed `isComplete()` but said the caller passes them).
3. The key is validated only by length (16..512, trimmed); invalid keys surface when the gateway first calls DeepSeek.
4. `vault.put` runs on the main thread (one AES-GCM op; first call also generates the Keystore key).
5. Backgrounding/process death mid-extraction restarts it on next launch (installer is atomic via rootfs.tmp).
