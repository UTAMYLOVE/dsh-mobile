# Task 11d report: export and memory backup

Status: DONE.

## Final signatures
```kotlin
object WorkspacePaths { fun resolveInside(root: File, rel: String): File?; internal fun isInside(realRoot: Path, real: Path): Boolean }
object SecretScrubber { fun scrub(text: String): String }
class Exporter(context: Context, workspace: File, errorLog: ErrorLog, clock: () -> Long = System::currentTimeMillis) {
    fun share(file: File, mime: String = guessMime(file))      // MimeTypeMap, fallback application/octet-stream
    fun shareWorkspaceFile(rel: String)
    fun memoryBackupZip(): File
    fun errorLogZip(): File
    fun clearExports()
    internal fun buildChooser(file: File, mime: String = ...): Intent   // what share() starts (plus FLAG_ACTIVITY_NEW_TASK)
}
```
- `resolveInside` returns the real (canonical) path of an existing regular file, or null for empty/NUL/absolute/`..` escape/symlink (file or any intermediate directory) leaving the real root. Symlinks that stay inside the root are allowed. Comparison is by path components (`Path.startsWith`), so `workspace-evil` does not match `workspace`.
- `share`: validates via real path (regular file inside workspace or `cacheDir/export`, else `IllegalArgumentException`), refreshes mtime of a file already in the export dir so cleanup cannot delete it, calls `clearExports()`, copies to `cacheDir/export/<name>` (REPLACE_EXISTING), URI from `FileProvider` authority `<packageName>.export`, `ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION` inside `Intent.createChooser` (the chooser also carries the grant flag). It starts the chooser with `FLAG_ACTIVITY_NEW_TASK`, so an application context works.
- `memoryBackupZip`: `MEMORY.md`, `USER.md`, every `*.md` under `memory/` (relative paths, `/` separators); symlinks (files and directories, no following) and missing files skipped; `.lock`, `.dream-state.json`, non-.md excluded.
- `errorLogZip`: `error.log` and `error.log.1` if they exist, UTF-8, passed through `SecretScrubber`. With no logs the zip is empty but valid.
- `SecretScrubber`: `token=\S+` -> `token=***`; `(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]+` -> `***`; `[A-Za-z0-9_-]{32,}` -> `***`. The lookbehind keeps `task-force`/`disk-usage` untouched.
- Manifest: `androidx.core.content.FileProvider`, authority `${applicationId}.export`, `exported=false`, `grantUriPermissions=true`, meta-data `@xml/file_paths`. `res/xml/file_paths.xml`: only `<cache-path name="export" path="export/"/>` (Task 12 adds `update/`). No uses-permission change.
- androidx.core (1.16.0) is already on the classpath transitively; `libs.versions.toml` untouched. Tests use the default Robolectric SDK (no Compose), no `@Config`.

## Tests
New 34: WorkspacePathsTest 9 (plain, `../`, absolute, empty/NUL, missing/dir, symlinked file out, symlinked intermediate dir out, in-root link allowed, shared-prefix sibling), SecretScrubberTest 6, ExporterTest 19 (backup contents/exclusions/symlinks/empty/relative names/clock name, error-log zip scrub + missing .1 + empty, share refusals: outside file, symlink out, `..`, directory; chooser + copy; grant flag + authority `app.dshmobile.export` + mime guess; overwrite and in-export file; shareWorkspaceFile refusals; clearExports 1 h cutoff, no dir, share-clears-stale).
Whole suite: 313 tests, 0 failures, 0 errors, 0 skipped (counts from app/build/test-results). ManifestPermissionsTest green. `assembleDebug` exit 0, output pristine.

## TDD evidence
- RED: with the three main sources moved aside, `:app:testDebugUnitTest --tests 'app.dshmobile.export.*'` failed at compileDebugUnitTestKotlin (`Unresolved reference 'Exporter'`, etc.). Note: I first ran the tests with the implementation already written (4 failures, below), then reproduced RED by removing the sources.
- GREEN: first run 34 tests / 4 failed (all share tests: `Failed to find configured root`, because FileProvider caches its PathStrategy statically across Robolectric tests, whose cache dirs differ) -> test `@Before` clears `FileProvider.sCache` by reflection -> 34/34; full suite 313/0.

## Files
Created: export/Exporter.kt, WorkspacePaths.kt, SecretScrubber.kt, res/xml/file_paths.xml, tests (3), this report. Modified: AndroidManifest.xml.

## Self-review / concerns
- The `sCache` reflection in ExporterTest is tied to androidx.core's private field name (present in 1.16.0); 11a tests that call `Exporter.share` under Robolectric need the same reset.
- `DshMobileApp` has no `exporter` singleton yet; 11a/11b construct `Exporter(context, File(filesDir, "rootfs/root/workspace"), errorLog)` (or add a lazy property).
- Zip file names have minute resolution: two exports in the same minute overwrite each other (intended, same-name overwrite).
- An empty zip (no entries) relies on the platform ZipOutputStream allowing it (true for JDK 7+ and OpenJDK-based Android 14+, minSdk is 34).
