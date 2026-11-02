### Task 11c：首次啟動引導與子行程限制偵測

計畫原文 Task 11c，**加上前面任務的實際介面**。本文與計畫原文衝突時以本文為準。這是第一個有 UI 的任務：本任務負責把 **Jetpack Compose** 加進專案（之後 11a、11b 共用）。

**Files:**
- Create: `android/app/src/main/kotlin/app/dshmobile/system/PhantomProcessDetector.kt`
- Create: `android/app/src/main/kotlin/app/dshmobile/ui/OnboardingActivity.kt`、`ui/OnboardingScreen.kt`、`ui/theme/SakuraTheme.kt`（Compose `MaterialTheme` 配色，顏色照 Global Constraints：底 `#fff5f8`、主色 `#d6336c`、我方氣泡 `#f06595`、標籤底 `#ffd6e3`／字 `#c2255c`、框線 `#f3c6d3`、文字 `#5a2a3a`）
- Create: `android/app/src/main/kotlin/app/dshmobile/system/OnboardingState.kt`（SharedPreferences `dshm_onboarding`：每一步「已完成／已略過」）
- Create: `android/app/src/main/res/values/strings.xml`（App 名稱 `DSH Mobile`、引導文字，繁體中文）、`res/values/themes.xml`（無 ActionBar 的 Material 主題、狀態列淺粉）、`res/drawable/ic_notification.xml`（單色向量通知小圖示）、桌面圖示 `res/mipmap-anydpi-v26/ic_launcher.xml` + 前景/背景向量（粉紅底、白色簡單字樣或圖形；不要用任何商標）
- Modify: `android/app/src/main/AndroidManifest.xml`（`application` 加 `android:icon`、`android:roundIcon`、`android:theme`；宣告 `OnboardingActivity`，`exported=false`；**不得新增 uses-permission**）
- Modify: `notify/Notifier.kt`：小圖示改用 `R.drawable.ic_notification`（Task 8 暫用系統圖示）
- Modify: `android/gradle/libs.versions.toml`、`android/app/build.gradle.kts`：Compose（Compose BOM、`material3`、`ui`、`ui-tooling-preview`、`activity-compose`、Kotlin Compose compiler Gradle plugin `org.jetbrains.kotlin.plugin.compose`，版本對齊專案的 Kotlin 版本；全部寫死版號）；測試用 `ui-test-junit4` + `ui-test-manifest`（Robolectric 可跑 Compose 測試）
- Test: `android/app/src/test/kotlin/app/dshmobile/system/PhantomProcessDetectorTest.kt`、`OnboardingStateTest.kt`、`android/app/src/test/kotlin/app/dshmobile/ui/OnboardingScreenTest.kt`（Robolectric + Compose）

**Consumes（先讀）：** `runtime/RootfsInstaller.kt`（`install(onProgress)`、`isInstalled()`；APK asset 名稱 `rootfs.tar.xz` 由 Task 12/13 放入，**本任務不加 asset**；asset 不存在時解壓步驟要顯示可讀錯誤而不是當掉）、`security/KeyVault.kt`（`AndroidKeyVault.put(Provider.DEEPSEEK, key)`）、`DshMobileApp.kt`（單例圖）、`docs/sdd-ledger/task-8-report.md`。

**Interfaces（Produces）:**
- `enum class PhantomState { DISABLED, ENABLED, UNKNOWN }`；`class PhantomProcessDetector(resolver: ContentResolver)`；`fun state(): PhantomState`：讀 `Settings.Global.getString(resolver, "settings_enable_monitor_phantom_procs")`：`"false"` → DISABLED、`"true"` → ENABLED、其他／null／讀取例外 → UNKNOWN。**App 不寫入任何系統設定。**
- `class OnboardingState(prefs: SharedPreferences)`：`fun isDone(step: Step): Boolean`、`fun markDone(step)`、`fun markSkipped(step)`、`fun nextStep(): Step?`、`fun isComplete(): Boolean`（每一步都已完成或略過，且 rootfs 已安裝、DeepSeek 金鑰已存——後兩者由呼叫端傳入判斷）；`enum class Step { NOTIFICATIONS, BATTERY, PHANTOM, DEEPSEEK_KEY, ROOTFS }`。
- `OnboardingActivity` + `OnboardingScreen`（Compose，依序、可略過但會記住；`ROOTFS` 與 `DEEPSEEK_KEY` **不可略過**）：
  1. 通知權限：`POST_NOTIFICATIONS` 的執行期請求（`ActivityResultContracts.RequestPermission`）。
  2. 忽略電池最佳化：`Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（帶 `package:` URI；已忽略則自動完成）。
  3. 子行程限制：說明為什麼（多代理人會被系統殺掉）；按鈕開啟 `Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS`；若開發人員選項尚未開啟（`Settings.Global.DEVELOPMENT_SETTINGS_ENABLED` ≠ 1），先顯示教學「設定 → 關於手機 → 軟體資訊 → 連點『版本號碼』7 次」並提供 `Settings.ACTION_DEVICE_INFO_SETTINGS` 按鈕；回到 App（`ON_RESUME`）時重新偵測並顯示 `PhantomState` 結果（已關閉／仍開啟／無法判斷）。
  4. 輸入 DeepSeek 金鑰：密碼型輸入框（不顯示明文、不進 log、不存進 Compose `rememberSaveable` 以外的地方）、`AndroidKeyVault.put`；只做非空與長度檢查。
  5. 解壓 rootfs：`RootfsInstaller.install` 在背景執行（`Dispatchers.IO`），進度條 0–100；失敗顯示可讀錯誤與「重試」；完成後 `OnboardingState` 完成並啟動 `app.dshmobile.ui.MainActivity`（以 `ComponentName` 字串，Task 11a 才建立）然後 `finish()`。
- 所有畫面使用 `SakuraTheme`，文字繁體中文。

- [ ] **Step 1: 寫失敗測試**：`PhantomProcessDetector` 三種設定值（`"false"`、`"true"`、其他字串）對應三種狀態、設定不存在 → UNKNOWN（Robolectric 可寫入 `Settings.Global` 測試值）；`OnboardingState` 下一步順序、略過會記住、`ROOTFS`／`DEEPSEEK_KEY` 不可略過；`OnboardingScreen`（Compose 測試）：金鑰輸入後呼叫 vault.put、空字串不能下一步、rootfs 解壓失敗顯示錯誤與重試按鈕（注入假 installer）。
- [ ] **Step 2:** FAIL。
- [ ] **Step 3:** 實作。
- [ ] **Step 4:** 全套單元測試綠燈、輸出乾淨（`ManifestPermissionsTest` 必須仍通過）；`assembleDebug` 成功。
- [ ] **Step 5:** Commit `feat: onboarding and phantom process detector`。
