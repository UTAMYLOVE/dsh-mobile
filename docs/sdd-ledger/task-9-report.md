# Task 9 report: dsh versions with health check and rollback

Status: DONE_WITH_CONCERNS (see Concerns).

## Final signatures
```kotlin
// runtime/GuestCommand.kt
data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)
fun interface GuestCommand { fun run(args: List<String>, timeoutMs: Long): CommandResult }
class ProotGuestCommand(runner: ProotRunner, rootfsDir: File, dnsServers: () -> List<String>) : GuestCommand
// runtime/DshVersions.kt
sealed interface UpdateOutcome { Updated(version) ; RolledBack(version, reason) ; Failed(reason) ; data object UpToDate }
class DshVersions(optDsh: File, cmd: GuestCommand, log: ErrorLog) {
    fun current(): String
    fun previous(): String?
    suspend fun latestAvailable(): String
    suspend fun install(version: String): Result<Unit>
    fun switchTo(version: String)
    fun rollback(): Boolean
    suspend fun updateWithAutoRollback(runtime: DshRuntime, health: HealthCheck): UpdateOutcome
}
// service/DshController.kt (only change): interface DshRuntime { fun stop(); suspend fun ensureRunning(): RunningDsh }, DshController : DshRuntime
```
`updateWithAutoRollback` takes `DshRuntime`, so `DshController` is passed as-is. DshController behaviour is unchanged (two `override` keywords, one interface).

## Behaviour notes
- Version regex `^\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$` is applied with full match (a trailing newline or space is rejected). `install` returns `Result.failure(IllegalArgumentException)` for a bad version (it is a Result API); `switchTo` throws `IllegalArgumentException`; neither ever calls the GuestCommand. `latestAvailable` throws `IllegalStateException` for non-zero exit or output that is not a version.
- Install: skips if `<v>/node_modules/@deepseek-ai/dsh` exists; else deletes stale `<v>.tmp`, runs npm (15 min), checks `package.json` version, renames `.tmp` -> `<v>` with ATOMIC_MOVE (a bare `<v>` dir without the package is removed first). Every failure path (exit code, timeout -1, wrong version, IO error such as ENOSPC) deletes `.tmp` in `finally` and logs to ErrorLog tag `dsh-versions`. Current is never touched.
- switchTo: `.previous` written via `.previous.tmp` + atomic rename, then `current.tmp` symlink -> `/opt/dsh/<v>` moved over `current` (ATOMIC_MOVE + REPLACE_EXISTING), then cleanup. `.previous` is written before the flip: a crash between the two leaves previous == current, which `previous()` reports as null (harmless).
- Cleanup removes only version-named dirs other than current/previous, `current.tmp`, `.previous.tmp`, and `<version>.tmp`; unrelated entries are left alone. Deletion uses `RootfsInstaller.deleteTree` (never follows symlinks; test with a symlink to an outside dir). `<version>.tmp` entries are skipped while an install of this instance is running, so a manual rollback during an update does not delete the in-progress install (test `switchDuringRunningInstallKeepsItsTmp`).
- `current()` works with the dangling host-side symlink (absolute guest target) because it uses `readSymbolicLink`/NOFOLLOW checks.
- updateWithAutoRollback: as in the brief. `health` is NOT called again: `ensureRunning()` already includes the HealthCheck. Cancellation after the switch: puts the old version back (NonCancellable), leaves dsh stopped, rethrows.
- ProotGuestCommand: `GuestNetworkFiles.write` each run, `/usr/bin/env -u LD_LIBRARY_PATH` prefix, env as in the brief, stdout/stderr drained on daemon threads (no pipe deadlock), timeout -> `destroyForcibly`, exit -1 (stderr gets a "timed out" line); failure to start proot -> -1.

## Tests (new: 34; whole suite 188, 0 failures, 0 errors, 0 skipped; Gradle output pristine)
DshVersionsTest 29 (all 9 brief names plus: prerelease accepted, current/previous parsing, garbage `.previous`, latestAvailable command/output validation, install args and timeout, existing version skips npm, stale tmp removed, timeout, npm "success" with nothing written, switch same version / missing version, symlink-safe cleanup, unrelated entries kept, install-vs-switch tmp protection, rollback without previous, auto-rollback when old version also fails, install failure -> Failed). ProotGuestCommandTest 5 (fake `libproot.so` shell script: argv/env prefix, network files, timeout kill, missing binary, 20000-line stdout+stderr).

## TDD evidence
- RED: first run of both new test classes: `compileDebugUnitTestKotlin` failed, unresolved references `DshVersions`, `UpdateOutcome`.
- GREEN: first full run of the two classes 33 tests / 1 failed (my own test assertion offset in ProotGuestCommandTest argv check) -> fixed test; 33/0. Added the concurrent-install test -> full suite `./gradlew :app:testDebugUnitTest --no-daemon -q`: 188 tests, 0 failed (counts from `app/build/test-results/testDebugUnitTest/*.xml`).

## Files
Main: runtime/DshVersions.kt, runtime/GuestCommand.kt (new); service/DshController.kt (DshRuntime interface + overrides).
Tests: runtime/DshVersionsTest.kt, runtime/ProotGuestCommandTest.kt.

## Self-review
- No shell strings anywhere: args are a list, version validated before use in paths and npm arguments.
- Version dirs are checked with NOFOLLOW, so a symlink named like a version is not accepted as installed.
- Fixed while reviewing: cleanup initially could race an in-flight install's tmp dir; now guarded.

## Concerns
1. After an automatic rollback, `.previous` points at the failed new version (switchTo semantics from the brief), so a manual rollback would go back to the broken version and its directory is kept. Task 11b may want to hide the rollback button after `RolledBack`, or we can clear `.previous` there.
2. `install()` returns `Result.failure` (not a throw) for a malformed version string; brief wording ("exception") is ambiguous for a Result API.
3. Not runtime-verified: real `npm install` in proot (only the process plumbing is tested with a fake proot), real symlink behaviour on Android's filesystem (tests run on Linux), `DshController` as `DshRuntime` in the real flow (compile-checked; behaviour covered by the existing 18 controller tests).
4. `latest < current` is reported as an update (brief: only equality means UpToDate).
