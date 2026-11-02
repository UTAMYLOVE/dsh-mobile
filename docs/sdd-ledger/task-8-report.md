# Task 8 report: dsh controller, foreground service, crash policy, error log, notifier, health check, dsh patch

Status: DONE_WITH_CONCERNS (see Concerns).

## Final signatures (later tasks depend on these)

```kotlin
// service/CrashPolicy.kt
class CrashPolicy(max: Int = 3, windowMs: Long = 600_000, clock: () -> Long) {
    enum class Decision { RESTART, GIVE_UP }
    fun onCrash(): Decision; fun reset()
}
// service/DshController.kt
fun interface DshLauncher { fun start(env: Map<String, String>, args: List<String>): Process }
data class RunningDsh(val dshPort: Int, val gatewayPort: Int, val token: SessionToken, val launch: LaunchUrl)
class DshController(
    filesDir: File,
    isRootfsInstalled: () -> Boolean,
    launcher: DshLauncher,
    vault: KeyVault,
    meter: CostMeter,
    errorLog: ErrorLog,
    healthCheck: suspend (LaunchUrl) -> Boolean,      // production: HealthCheck::check
    dnsServers: () -> List<String>,
    onNotify: (NotifyRequest) -> Unit,
    onCostAlert: (usd: Double) -> Unit,
    onRateGuard: (Provider) -> Unit,
    upstreams: Map<Provider, HttpUrl> = DEFAULT_UPSTREAMS,
    launchTimeoutMs: Long = 60_000,
    stopGraceMs: Long = 2_000,
) {
    suspend fun ensureRunning(): RunningDsh
    fun stop()
    val isRunning: StateFlow<Boolean>
    fun onExit(listener: (exitCode: Int) -> Unit): AutoCloseable   // close() unregisters
    companion object { DEFAULT_UPSTREAMS; DSH_ARGS }
}
// runtime/DshClient.kt
class DshClient(base: OkHttpClient, launch: LaunchUrl) {
    suspend fun login(); suspend fun get(path: String): Response; suspend fun postJson(path: String, json: String): Response
}
// runtime/HealthCheck.kt
class HealthCheck(http: OkHttpClient, timeoutMs: Long = 60_000, retryIntervalMs: Long = 500) { suspend fun check(launch: LaunchUrl): Boolean }
// notify/Notifier.kt
class Notifier(context: Context) {
    fun show(req: NotifyRequest); fun showCostAlert(usd: Double); fun showRateGuard(p: Provider)
    fun showCrashGiveUp(); fun serviceNotification(): Notification
    companion object { CHANNEL_MESSAGES/ALERTS/SERVICE, EXTRA_SESSION_ID="sessionId", EXTRA_ACTION="action", ACTION_ROLLBACK, ACTION_EXPORT_ERROR_LOG, ID_SERVICE }
}
// log/ErrorLog.kt
class ErrorLog(filesDir: File, clock: Clock = Clock.systemUTC()) { fun append(tag, msg); fun addSecret(s); fun file(): File }
// config/DshPatch.kt
object DshPatch {
    fun render(gatewayBase: String, pluginDir: String = "/opt/dshm/plugins"): String
    internal fun sections(gatewayBase, pluginDir): List<String>   // Task 11b: add a section here
    internal fun providers(gatewayBase) / privacy() / directoryPicker() / plugins(pluginDir) / q(s)
}
// runtime/GuestNetworkFiles.kt:  object GuestNetworkFiles { fun write(rootfsDir: File, dnsServers: List<String>) }
// service/DshService.kt:         class DshService : Service;  companion fun start(context)
// DshMobileApp.kt: singletons (lazy) errorLog, notifier, keyVault, costMeter, rootfsInstaller, crashPolicy, controller
```

## Behaviour notes
- Startup order follows the brief (a)-(h). Process-start failure, launch-line timeout, early exit and failed health check all end in `teardown` (process, gateway, patch file) and an `IllegalStateException` whose reason is also written to ErrorLog (tag `controller`).
- Launch line is consumed (not logged); its token is registered as an ErrorLog secret; every other stdout line and all stderr go to ErrorLog tag `dsh`.
- The patch file (`rootfs/opt/dshm/dshm.cordis.yml`, mode 0600, created fresh, so an existing link is never written through) is deleted at teardown because it holds the gateway URL with the session token.
- `teardown` is idempotent and a second caller blocks until the first finished, so after `stop()`/a failed start returns, the gateway port is closed. A process that is already dead is not `destroy()`ed (destroy closes the pipes before the readers log its last output).
- DshService: `startForeground(ServiceCompat, SPECIAL_USE)` first, then `ensureRunning()`; `onExit` and start failures go to `CrashPolicy` (RESTART after 2 s, GIVE_UP -> `showCrashGiveUp()` + `stopSelf()`); policy reset after 5 min of stable running; `onDestroy` stops dsh on a background thread. START_STICKY.
- Manifest: `android:name=".DshMobileApp"`, `DshService` with `foregroundServiceType="specialUse"` and the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property. No uses-permission change.
- Guest DNS from `ConnectivityManager.getLinkProperties(activeNetwork).dnsServers` (link-local IPv6 dropped, scope id stripped) in `DshMobileApp.activeNetworkDnsServers`.

## Tests (new: 67, whole suite: 154, 0 failures, 0 errors, 0 skipped)
CrashPolicyTest 4, ErrorLogTest 9, DshPatchTest 8, GuestNetworkFilesTest 6, DshClientTest 6, HealthCheckTest 6, DshControllerTest 18, NotifierTest 10 (Robolectric). ManifestPermissionsTest still green (1). The three Task-8-relevant groups were re-run with `--rerun-tasks` three times: stable.
Controller tests use real tiny `sh -c` processes (launch-line echo + sleep) and the real LocalGateway on a loopback port; every controller is stopped and every process `destroyForcibly`ed in `@After`.

## TDD evidence
- RED: after writing all 9 test files, `./gradlew :app:testDebugUnitTest` failed at `compileDebugUnitTestKotlin` (unresolved references to every Task 8 class).
- GREEN iterations: first full run 154 tests / 1 failed (`exitBeforeLaunchLineFailsFast`: gateway still open when the exception surfaced, because a racing teardown returned early) -> teardown made blocking-idempotent; second run 154 / 1 failed (`[dsh] oops` stderr lost because `destroy()` closed the pipe of an already-dead process) -> dead processes are not destroyed; third run 154 / 0 failed. Counts from `app/build/test-results/testDebugUnitTest/*.xml`.
- `./gradlew :app:assembleDebug --no-daemon -q`: exit 0.

## Only compile-verified (no automated runtime test)
- `DshService` (foreground start, crash/restart flow, 5-minute reset, onDestroy) and `DshMobileApp` wiring (incl. `ConnectivityManager` DNS lookup, `AndroidKeyVault`, `ProotRunner` launcher). Needs device/Task 13 E2E.
- Real proot + dsh launch (`/usr/bin/env -u LD_LIBRARY_PATH node ...`) and the real health routes through dsh's login: only the cookie/303 protocol is covered, via MockWebServer.
- Notifier: tested under Robolectric (channels, channel ids, extras, component, FLAG_IMMUTABLE, actions, silent without permission); actual rendering/tap behaviour on a device is not.

## Files
Main: service/{CrashPolicy,DshController,DshService}.kt, notify/Notifier.kt, runtime/{HealthCheck,DshClient,GuestNetworkFiles}.kt, config/DshPatch.kt, log/ErrorLog.kt, DshMobileApp.kt, AndroidManifest.xml (modified).
Tests: service/{CrashPolicyTest,DshControllerTest}.kt, log/ErrorLogTest.kt, config/DshPatchTest.kt, notify/NotifierTest.kt, runtime/{GuestNetworkFilesTest,HealthCheckTest,DshClientTest,FakeDshServer}.kt.

## Self-review
- Secrets: real keys appear only in the vault -> LocalGateway; env test asserts no value contains the vault's `sk-real...`; patch file test asserts the same. Session token: `SessionToken.toString()` is masked, exception messages never include URLs/tokens (DshClient messages carry only status codes), ErrorLog masks the session token (secret + 64-hex rule), `token=` values and the launch token (registered at parse time).
- Processes: all paths through start failure, stop, early exit and cancellation go through `teardown`; reader coroutines swallow the IOException caused by closing pipes (otherwise an uncaught coroutine exception would crash the Android process).
- ErrorLog rotation threshold is 1 MiB (1,048,576 bytes); runs of 64 or more hex characters are masked (brief says 64; longer runs would otherwise leak a prefix).
- `HealthCheck` logs in once per `check()` and retries only the failing step (the launch token may be single use).

## Concerns
1. `DshController` constructor takes `healthCheck: suspend (LaunchUrl) -> Boolean` and three notification lambdas instead of a Notifier (so tests need no Android context); `DshMobileApp` wires them. `onExit` returns an `AutoCloseable` (superset of the brief).
2. Notifier uses framework `Notification.Builder` and the system icon `android.R.drawable.ic_dialog_info` (the project has no res/ yet); Task 11 should supply a real small icon.
3. A start failure in `DshService` is counted by `CrashPolicy` like a crash (so "rootfs not installed" produces three quick tries, then the give-up notification). Task 11c should install the rootfs before starting the service.
4. `DshService` has no stop action/intent; callers use `stopService`.
5. The PROOT_TMP_DIR is `cacheDir/proot`.
