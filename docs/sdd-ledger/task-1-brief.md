### Task 1: 倉庫骨架與 CI

**Files:**
- Create: `android/`（Gradle Kotlin DSL、`app` 模組、`app.dshmobile`）
- Create: `plugins/package.json`、`plugins/pnpm-workspace.yaml`、`plugins/vitest.config.ts`
- Create: `.github/workflows/ci.yml`
- Create: `android/app/src/test/kotlin/app/dshmobile/ManifestPermissionsTest.kt`

**Interfaces:**
- Produces: 指令 `./gradlew :app:testDebugUnitTest`、`pnpm -C plugins test`；CI 在每次 push 跑這兩個。`BuildConfig.ROOTFS_VERSION: String`、`BuildConfig.DSH_PINNED_VERSION: String`、`BuildConfig.RELEASES_REPO: String`。

- [ ] **Step 1: 寫失敗測試** — 讀合併後的 manifest（Robolectric `PackageManager`），斷言權限集合恰好等於 Global Constraints 的 9 個：

```kotlin
@Test fun permissionsAreExactlyTheAllowedSet() {
    val perms = context.packageManager
        .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions!!.toSet()
    assertEquals(ALLOWED_PERMISSIONS, perms)   // 9 個，常數定義在測試裡
}
```

- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest` → FAIL。
- [ ] **Step 3:** 建 manifest 宣告這 9 個；WorkManager 帶進的權限若超出，用 `tools:node="remove"` 移除。
- [ ] **Step 4:** 測試 PASS；`pnpm -C plugins test` 在空 workspace 下 PASS。
- [ ] **Step 5:** CI 綠燈；Commit `chore: scaffold android app, plugins workspace, CI`。

---

