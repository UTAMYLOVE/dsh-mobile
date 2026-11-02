### Task 2: Rootfs 打包腳本

**Files:**
- Create: `rootfs/build-rootfs.sh`
- Create: `rootfs/smoke-test.sh`
- Create: `rootfs/auth-gate.cjs` 的放置位置 `/opt/dshm/`（檔案本身由 Task 4 產生，這裡只負責複製）
- Modify: `.github/workflows/ci.yml`（加 rootfs job）

**Interfaces:**
- Consumes: Task 0 §Pin。
- Produces: `rootfs/build-rootfs.sh <arm64|x86_64> <out.tar.xz>` 產生的 rootfs 內含：
  - Debian bookworm 最小系統、`/usr/local/bin/node`（24.x）
  - `/opt/dsh/<DSH_PINNED_VERSION>/`（`npm install --prefix` 安裝）、`/opt/dsh/current` → 該版本的符號連結
  - `/opt/dshm/auth-gate.cjs`、`/opt/dshm/plugins/{memory,notify}`（建置產物）
  - `/root/workspace/`（空）
  - `/etc/dshm-rootfs-version`（內容 = `ROOTFS_VERSION`）

- [ ] **Step 1:** 寫 `smoke-test.sh <tar>`：解開後用 `proot`（CI 上配 qemu-user-static）執行並檢查：`node --version` 以 `v24.` 開頭；`/opt/dsh/current/node_modules/.bin/dsh --version` 等於 `DSH_PINNED_VERSION`；`/etc/dshm-rootfs-version` 存在。
- [ ] **Step 2:** 在還沒有 build 腳本時跑 smoke test → FAIL。
- [ ] **Step 3:** 寫 `build-rootfs.sh`（debootstrap 或 proot-distro 公開 tarball 二擇一，實作者決定；裁掉 man、doc、locale）。
- [ ] **Step 4:** CI 兩種架構都 build + smoke test PASS；壓縮後大小印在 job summary。
- [ ] **Step 5:** Commit `build: rootfs with node 24 and pinned dsh`。

---

