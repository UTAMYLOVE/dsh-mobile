#!/usr/bin/env python3
"""Turns unpacked Termux packages into the app's native dsh runtime: Android-native node, bash, ripgrep and git.

Usage: build-runtime.py ABI USR FLOCK OUT
  ABI    arm64-v8a | x86_64
  USR    the Termux prefix unpacked by fetch-termux.py (OUT_OF_FETCH/usr)
  FLOCK  dsh's file-lock addon built for Android (build-flock.sh)
  OUT    gets jniLibs/<ABI>/libdshn_*.so and assets/native/{manifest.json,cert.pem}

Android only runs code an app ships in its native library directory (W^X: files under the app's data are never executable),
and only files named lib*.so are installed there. So every executable and every shared library it needs becomes one
libdshn_*.so; each NEEDED entry is rewritten to the new name and RUNPATH is dropped, so the app runs them with
LD_LIBRARY_PATH set to the native library directory. manifest.json maps the program names to their files.
"""
import json
import os
import re
import shutil
import subprocess
import sys

# Programs dsh and the agent use, and the git helper git runs for https remotes.
BINS = {
    "node": "bin/node",
    "bash": "bin/bash",
    "rg": "bin/rg",
    "git": "bin/git",
    "git-remote-http": "libexec/git-core/git-remote-http",
}
# What Android itself provides to every process (bionic's public libraries).
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so"}
# Same binary under another name (git chooses the helper by name).
ALIASES = {"git-remote-https": "git-remote-http"}


def needed(path: str) -> list:
    out = subprocess.run(["readelf", "-d", path], check=True, capture_output=True, text=True).stdout
    return re.findall(r"\(NEEDED\)\s+Shared library: \[([^\]]+)\]", out)


def packaged_name(soname: str) -> str:
    return "libdshn_" + re.sub(r"[^A-Za-z0-9]", "_", soname) + ".so"


def main() -> None:
    abi, usr, flock, out = sys.argv[1:5]
    lib_dir = os.path.join(usr, "lib")
    jni = os.path.join(out, "jniLibs", abi)
    assets = os.path.join(out, "assets", "native")
    for d in (jni, assets):
        shutil.rmtree(d, ignore_errors=True)
        os.makedirs(d)

    # The closure of shared libraries the programs need from the Termux prefix; anything not there is the system's (libc,
    # libm, libdl, liblog, libandroid ...), which Android provides.
    libs, system, todo = {}, set(), [os.path.join(usr, p) for p in BINS.values()]
    while todo:
        for name in needed(todo.pop()):
            if name in libs or name in system:
                continue
            path = os.path.join(lib_dir, name)
            if os.path.exists(path):
                libs[name] = os.path.realpath(path)
                todo.append(libs[name])
            elif name in SYSTEM_LIBS:
                system.add(name)
            else:
                sys.exit(f"{name} is neither in the Termux prefix nor part of Android: add its package to native/packages.txt")

    def place(src: str, dst_name: str, soname: str | None) -> None:
        dst = os.path.join(jni, dst_name)
        shutil.copyfile(src, dst)
        os.chmod(dst, 0o755)
        args = ["patchelf", "--remove-rpath"]
        for n in needed(src):
            if n in libs:
                args += ["--replace-needed", n, packaged_name(n)]
        if soname:
            args += ["--set-soname", soname]
        subprocess.run(args + [dst], check=True)

    for name, path in libs.items():
        place(path, packaged_name(name), packaged_name(name))
    bins = {}
    for name, rel in BINS.items():
        bins[name] = f"libdshn_bin_{re.sub(r'[^A-Za-z0-9]', '_', name)}.so"
        place(os.path.join(usr, rel), bins[name], None)
    for alias, target in ALIASES.items():
        bins[alias] = bins[target]
    place(flock, "libdshn_flock.so", "libdshn_flock.so")

    shutil.copyfile(os.path.join(usr, "etc", "tls", "cert.pem"), os.path.join(assets, "cert.pem"))
    versions = {}
    packages_txt = os.path.join(os.path.dirname(usr), "packages.txt")
    if os.path.exists(packages_txt):
        for line in open(packages_txt):
            if line.strip():
                n, v = line.split(" ", 1)
                versions[n] = v.strip()
    manifest = {
        "abi": abi,
        "bins": bins,
        "flock": "libdshn_flock.so",
        "libs": sorted(packaged_name(n) for n in libs),
        "systemLibs": sorted(system),
        "packages": versions,
    }
    with open(os.path.join(assets, "manifest.json"), "w") as fh:
        json.dump(manifest, fh, indent=1, sort_keys=True)
    total = sum(os.path.getsize(os.path.join(jni, f)) for f in os.listdir(jni))
    print(f"{abi}: {len(bins)} programs, {len(libs)} libraries, {total / 1048576:.1f} MiB; system: {' '.join(sorted(system))}")


if __name__ == "__main__":
    main()
