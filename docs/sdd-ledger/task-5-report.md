# Task 5 report: local gateway

Status: DONE_WITH_CONCERNS (concerns at the end are small judgment calls, nothing blocking).

## What was implemented
- `gateway/Provider.kt` (new, not in the brief's list): `enum Provider { DEEPSEEK, ANTHROPIC }`, shared by `security/KeyVault.kt` and the gateway.
- `gateway/UsageParser.kt`: `Usage`, `UsageUpdate` (nullable fields), `UsageAccumulator` (later event overrides present fields, keeps absent ones, never adds), `UsageParser.fromJson / fromSseEvent`, `SseUsageScanner` (byte-stream `data:` line scanner, handles split feeds, CRLF, 1 MiB line cap), `UsageTap` (picks SSE / JSON / none by response Content-Type; JSON buffered up to 8 MiB).
- `gateway/PriceTable.kt`: prices copied verbatim from the brief; UTC Mon-Fri peak 01-04 and 06-10 (start inclusive, end exclusive); legacy DeepSeek names; Haiku 5.5 tier above 100,000 total input; search fees; unknown model falls back to deepseek-v4-pro peak / claude-fable-5-1; only a trailing `-YYYYMMDD` is stripped from names.
- `gateway/CostMeter.kt`: SharedPreferences-backed daily total (day boundary in `zone`, default Asia/Taipei), `alertUsd` (default 5.0) and `hardCapUsd` persisted in the same store, `consumeAlert()` once per day, `isBlocked()`, all synchronized.
- `gateway/RateGuard.kt`: per-provider sliding 60 s window; rejected calls are not counted.
- `security/KeyVault.kt`: `KeyVault` interface and `AndroidKeyVault` (Keystore AES-256-GCM key alias `dshm-keyvault`, prefs file `dshm_keys`, `Base64(iv):Base64(ct)` per provider, provider name bound as AAD, no logging; decrypt failures return null).
- `gateway/LocalGateway.kt`: Ktor CIO server bound to 127.0.0.1 port 0; OkHttp upstream client (no redirects, no retry, 15 s connect timeout, 15 min read timeout, no call timeout). Routes `/{token}/deepseek|anthropic/...`, `POST /{token}/notify`; constant-time token check (403 before anything else); 404 elsewhere; dot-segment paths (`..`, `%2e%2e`) rejected with 404. Check order daily cap, rate guard (onRateGuard at most once per 10 min per provider), key. Header drop/inject, body strip of `dsh_session_log` / `dsh_plugin_packages` (original bytes forwarded untouched when nothing is stripped or the body is not a JSON object), response relayed chunk by chunk, usage booked exactly once in a `finally` (clean end, upstream abort, client cancel), then `consumeAlert()` -> `onCostAlert`.
- Added behaviour beyond the brief (all safety/robustness): `502 {"error":"dshm_upstream_unreachable"}` when upstream is unreachable; `401 {"error":"dshm_invalid_key","provider":..}` when the stored key has characters illegal in a header (OkHttp's exception text would otherwise quote the key); `Accept-Encoding: identity` is sent upstream so bodies stay uncompressed and usage is readable; constructor got a trailing `clock: Clock = Clock.systemUTC()` (used for the 10-minute onRateGuard quiet period, makes it testable).

## Library versions (pinned in `android/gradle/libs.versions.toml`)
Ktor server core + CIO 3.4.3, OkHttp 4.12.0 and MockWebServer 4.12.0 (test), kotlinx-coroutines-core 1.10.2, kotlinx-serialization-json 1.9.0 (JsonElement parsing only, no plugin), slf4j-nop 2.0.17 (Ktor logs through SLF4J; the no-op binding removes the "no provider" warning and guarantees request URLs, which carry the token, are never logged). `:app:assembleDebug` also succeeds with the new dependencies.

## Tests
New: LocalGatewayTest (28), CostMeterTest (9), PriceTableTest (10), UsageParserTest (11), RateGuardTest (3), plus test helpers `InMemorySharedPreferences` and `MutableClock`. All brief test names exist verbatim in LocalGatewayTest; extras cover Anthropic injection, non-JSON body passthrough, GET passthrough, status passthrough, path traversal (raw socket), unknown path, JSON usage, SSE split across network chunks with CRLF, client cancel, no-usage requests, notify validation, invalid key, unreachable upstream, 10-minute rate-guard notice, stop(). `AndroidKeyVault` is not unit-tested (Robolectric has no AndroidKeyStore), as the brief allows.
The 150 ms chunk test warms the gateway with one request first (class loading) and then asserts first byte < 150 ms and total >= 350 ms with upstream throttled to 200 bytes / 200 ms; thresholds are as in the brief. LocalGatewayTest was run 3 more times in a row after the final fix: stable.

## TDD evidence
RED: `cd android && ./gradlew :app:testDebugUnitTest --no-daemon -q --tests 'app.dshmobile.gateway.*'` with tests only (no production code) failed at `compileDebugUnitTestKotlin`: `e: .../CostMeterTest.kt:15:26 Unresolved reference 'PriceTable'`, `Unresolved reference 'Usage'`, `'CostMeter'`, `'Provider'` and so on. Expected: none of the gateway types existed yet.
First GREEN attempt after implementing: 59 tests, 3 failed, all test-side issues, fixed: CostMeterTest's `MutableClock.withZone` ignored the zone (CostMeter now uses `instant().atZone(zone)`, which is also more robust), the path-traversal test needed a raw socket because OkHttp normalises dot segments, and MockWebServer's host name is `localhost`.
GREEN: `./gradlew :app:testDebugUnitTest --no-daemon -q` (full suite), no output. XML results: 86 tests, 0 failures, 0 errors, 0 skipped (CostMeterTest 9, LocalGatewayTest 28, PriceTableTest 10, RateGuardTest 3, UsageParserTest 11, plus the existing ManifestPermissionsTest 1, DshLaunchLineTest 6, ProotRunnerTest 3, RootfsInstallerTest 12, SessionTokenTest 3). No system-out/system-err in any XML.

## Files changed
Modified: `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts` (CRLF line endings preserved).
Created (main): `gateway/{Provider,UsageParser,PriceTable,CostMeter,RateGuard,LocalGateway}.kt`, `security/KeyVault.kt`.
Created (test): `gateway/{LocalGatewayTest,CostMeterTest,PriceTableTest,UsageParserTest,RateGuardTest,InMemorySharedPreferences,MutableClock}.kt`.

## Self-review
- Completeness against the brief: routes, error JSON, header names, prices, peak hours, ordering of checks, exactly-once recording, token never logged: checked. Nothing in toString, exception messages or responses contains the token or a key (OkHttp header exceptions are swallowed without their message; slf4j-nop).
- YAGNI: UsageTap/SseUsageScanner are needed for streaming and abort accounting; no extra settings or abstractions.
- Fixed during review: removed a redundant provider-specific branch, moved the date/zone logic into `instant().atZone(zone)`.

## Concerns
1. `Provider.kt` is an extra file (the enum is needed by both `security` and `gateway`).
2. Read timeout on the upstream client is 15 minutes rather than infinite (brief says no overall timeout; a dead socket should not hang forever). Adjust if reasoning models can stay silent longer than that without SSE pings.
3. The request body is buffered whole before forwarding (needed to strip fields); the response is streamed.
4. Ktor CIO's `connectionIdleTimeoutSeconds` is raised to 3600 so long quiet streams are not cut; not verifiable on a real device from here.
5. On Android, `AndroidKeyVault` and the actual Ktor-on-device run are untested in this environment.
