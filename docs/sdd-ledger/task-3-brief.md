### Task 3: 解壓與 proot 執行器

**Files:**
- Create: `android/app/src/main/kotlin/app/dshmobile/runtime/RootfsInstaller.kt`
- Create: `android/app/src/main/kotlin/app/dshmobile/runtime/ProotRunner.kt`
- Create: `android/app/src/main/jniLibs/{arm64-v8a,x86_64}/libproot.so`、`libproot-loader.so`（Termux proot 建置產物，CI 下載後放入；Android 10+ 只能從 nativeLibraryDir 執行檔案，所以要偽裝成 .so，並設 `extractNativeLibs=true`）
- Test: `android/app/src/test/kotlin/app/dshmobile/runtime/RootfsInstallerTest.kt`、`ProotRunnerTest.kt`

**Interfaces:**
- Produces:
  - `class RootfsInstaller(open: (String) -> InputStream, filesDir: File, expectedVersion: String)`；`fun isInstalled(): Boolean`；`fun install(onProgress: (percent: Int) -> Unit)`；rootfs 在 `filesDir/rootfs`。
  - `class ProotRunner(nativeLibDir: File, rootfsDir: File, tmpDir: File)`；`fun command(args: List<String>, env: Map<String, String>): ProcessBuilder`。

- [ ] **Step 1: 寫失敗測試**

```kotlin
@Test fun installIsIdempotentAndVersioned() {
    installer.install {}
    assertTrue(installer.isInstalled())
    File(filesDir, "rootfs/etc/dshm-rootfs-version").writeText("old")
    assertFalse(installer.isInstalled())          // 版本不符要重裝
}
@Test fun interruptedInstallLeavesNoHalfRootfs() {
    // open() 在一半丟 IOException
    assertThrows<IOException> { brokenInstaller.install {} }
    assertFalse(brokenInstaller.isInstalled())
    assertFalse(File(filesDir, "rootfs").exists()) // 先解到 rootfs.tmp，成功才改名
}
@Test fun commandUsesBundledProotAndLoader() {
    val pb = runner.command(listOf("node", "-v"), mapOf("A" to "1"))
    assertEquals(File(nativeLibDir, "libproot.so").path, pb.command()[0])
    assertEquals(File(nativeLibDir, "libproot-loader.so").path, pb.environment()["PROOT_LOADER"])
    assertTrue(pb.command().containsAll(listOf("-r", rootfsDir.path, "-0", "-w", "/root/workspace")))
    assertEquals("1", pb.environment()["A"])
}
```

- [ ] **Step 2:** 跑 → FAIL。
- [ ] **Step 3:** 實作；`install` 解到 `rootfs.tmp` 再原子改名。proot 參數另含 `--link2symlink`、綁 `/proc`、`/dev`、`/sys`。
- [ ] **Step 4:** PASS。
- [ ] **Step 5:** Commit `feat: rootfs installer and proot runner`。

---

