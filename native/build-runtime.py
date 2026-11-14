#!/usr/bin/env python3
"""Turns unpacked Termux packages into the app's native dsh runtime: Android-native node, bash, ripgrep, git and python3.

Usage: build-runtime.py ABI USR FLOCK OUT
  ABI    arm64-v8a | x86_64
  USR    the Termux prefix unpacked by fetch-termux.py (OUT_OF_FETCH/usr)
  FLOCK  dsh's file-lock addon built for Android (build-flock.sh)
  OUT    gets jniLibs/<ABI>/libdshn_*.so and assets/native/{manifest.json,ca-bundle.crt,python.tar.xz}

Android only runs code an app ships in its native library directory (W^X: files under the app's data are never executable),
and only files named lib*.so are installed there. So every executable and every shared library it needs becomes one
libdshn_*.so, unchanged. manifest.json maps the program names and the libraries' sonames to their files; the app links
them back under their own names (NativeRunner).

Python's extension modules (lib-dynload) are shared libraries too and ship the same way (libdshn_py_<module>.so); the rest of
Python, the pure-Python standard library and pip, is data: assets/native/python.tar.xz, which the app unpacks once into its
files and links the modules into (NativeRunner.prepare).
"""
import hashlib
import io
import json
import lzma
import os
import re
import shutil
import subprocess
import sys
import tarfile

# Programs dsh and the agent use, and the git helper git runs for https remotes. python3 is added by main() (its file name
# carries the version Termux ships).
BINS = {
    "node": "bin/node",
    "bash": "bin/bash",
    "rg": "bin/rg",
    "git": "bin/git",
    "git-remote-http": "libexec/git-core/git-remote-http",
    # Everyday programs a model reaches for in a shell; Android's own toybox covers the rest (ls, sed, find, tar, ...).
    "curl": "bin/curl",
    "jq": "bin/jq",
    "sqlite3": "bin/sqlite3",
    "zip": "bin/zip",
    "unzip": "bin/unzip",
}
# What Android itself provides to every process (bionic's public libraries).
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so"}
# Same binary under another name (git chooses the helper by name).
ALIASES = {"git-remote-https": "git-remote-http"}

TERMUX_PREFIX = "/data/data/com.termux/files/usr"
# CPython's own test modules: not for use, a fifth of lib-dynload (1 of 5.3 MiB, 3.14).
PY_DROP_MODULE = re.compile(r"^(_test|_ctypes_test|xx|_xx)")
# Folders of lib/python3.X that are not shipped: the extension modules (jniLibs), the static build configuration, Debian's
# byte-compile helpers, caches, and the test suite and demos where Termux ships them.
PY_SKIP_TOP = {"lib-dynload", "debpython", "test", "idlelib", "turtledemo", "tkinter"}
# Termux paths in the standard library that the phone does not have, replaced at packing time. Each one must still be there,
# so a Termux update that moves them stops the build instead of shipping a Python that looks for Termux's shell.
PY_PATCHES = {
    # subprocess.run(..., shell=True)
    "subprocess.py": [(f"unix_shell = ('{TERMUX_PREFIX}/bin/sh')", "unix_shell = ('/system/bin/sh')")],
    # os.get_exec_path() and shutil.which() without PATH, os.execvp
    "posixpath.py": [(f"defpath = '{TERMUX_PREFIX}/bin'", "defpath = '/system/bin'")],
}
# pip's certificates are a symlink to Termux's bundle; the runtime's own bundle takes its place (pip loads it on every
# network call, PIP_CERT or not).
PIP_CACERT = "site-packages/pip/_vendor/certifi/cacert.pem"


def needed(path: str) -> list:
    out = subprocess.run(["readelf", "-d", path], check=True, capture_output=True, text=True).stdout
    return re.findall(r"\(NEEDED\)\s+Shared library: \[([^\]]+)\]", out)


def mangle(name: str) -> str:
    return re.sub(r"[^A-Za-z0-9]", "_", name)


def packaged_name(soname: str) -> str:
    return "libdshn_" + mangle(soname) + ".so"


def python_version(usr: str) -> str:
    """X.Y of the python Termux installed (lib/pythonX.Y with its lib-dynload)."""
    lib = os.path.join(usr, "lib")
    found = sorted(d[len("python"):] for d in os.listdir(lib) if re.fullmatch(r"python3\.\d+", d) and os.path.isdir(os.path.join(lib, d, "lib-dynload")))
    if len(found) != 1:
        sys.exit(f"expected exactly one lib/python3.X with lib-dynload in {usr}, found {found}: add python to native/packages.txt")
    return found[0]


def python_modules(stdlib: str) -> dict:
    """lib-dynload file name -> its jniLibs name, without CPython's test modules."""
    dynload = os.path.join(stdlib, "lib-dynload")
    modules = {}
    for f in sorted(os.listdir(dynload)):
        if not f.endswith(".so") or PY_DROP_MODULE.match(f):
            continue
        lib = f"libdshn_py_{mangle(f.split('.', 1)[0])}.so"
        if lib in modules.values():
            sys.exit(f"two extension modules map to {lib}")
        modules[f] = lib
    return modules


def python_archive(stdlib: str, version: str, ca_bundle: bytes) -> bytes:
    """The pure-Python standard library and pip as lib/pythonX.Y/... in a reproducible tar.xz (same input, same bytes)."""
    patches = {k: list(v) for k, v in PY_PATCHES.items()}
    leftovers = []
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.PAX_FORMAT) as tar:
        def add(rel: str, kind: bytes, data: bytes = b"", mode: int = 0o644, link: str = "") -> None:
            info = tarfile.TarInfo(f"lib/python{version}/{rel}" if rel else f"lib/python{version}")
            info.mtime, info.uid, info.gid, info.uname, info.gname = 0, 0, 0, "", ""
            info.type, info.mode = kind, mode
            if kind == tarfile.SYMTYPE:
                info.linkname = link
            info.size = len(data) if kind == tarfile.REGTYPE else 0
            tar.addfile(info, io.BytesIO(data) if kind == tarfile.REGTYPE else None)

        add("", tarfile.DIRTYPE, mode=0o755)
        add("lib-dynload", tarfile.DIRTYPE, mode=0o755)  # filled with links to the modules by the app
        for root, dirs, files in os.walk(stdlib):
            rel_root = os.path.relpath(root, stdlib)
            rel_root = "" if rel_root == "." else rel_root
            dirs[:] = sorted(d for d in dirs if d != "__pycache__" and not (rel_root == "" and (d in PY_SKIP_TOP or d.startswith("config-"))))
            for d in dirs:
                if os.path.islink(os.path.join(root, d)):
                    sys.exit(f"{os.path.join(rel_root, d)} is a symlinked folder: not expected in Termux's python")
                add(os.path.join(rel_root, d), tarfile.DIRTYPE, mode=0o755)
            for f in sorted(files):
                rel = os.path.join(rel_root, f)
                path = os.path.join(root, f)
                if rel == PIP_CACERT:
                    add(rel, tarfile.REGTYPE, ca_bundle)
                    continue
                # Windows launchers distlib only uses on Windows.
                if rel.startswith("site-packages/pip/_vendor/distlib/") and f.endswith(".exe"):
                    continue
                if f.endswith((".pyc", ".pyo")):
                    continue
                if os.path.islink(path):
                    target = os.readlink(path)
                    if os.path.isabs(target):
                        sys.exit(f"{rel} is a symlink to {target}, which the phone does not have")
                    add(rel, tarfile.SYMTYPE, link=target, mode=0o777)
                    continue
                data = open(path, "rb").read()
                for old, new in patches.pop(rel, []):
                    if data.count(old.encode()) != 1:
                        sys.exit(f"{rel}: expected one {old!r} to replace (Termux changed it?)")
                    data = data.replace(old.encode(), new.encode())
                if f.endswith(".py") and TERMUX_PREFIX.encode() in data:
                    leftovers.append(rel)
                add(rel, tarfile.REGTYPE, data, 0o755 if os.stat(path).st_mode & 0o100 else 0o644)
    if patches:
        sys.exit(f"files to patch not found: {sorted(patches)}")
    if leftovers:
        # Shebang lines, config file candidates and fallbacks that never apply on the phone (tempfile's last-resort folder:
        # the app always sets TMPDIR).
        print(f"python: Termux paths left in {len(leftovers)} files: {' '.join(sorted(leftovers))}")
    # xz-java on the phone allocates the whole dictionary: 8 MiB instead of preset 9's 64 MiB costs under 1% here (16 MiB
    # of tar, 2.6 MiB packed, 2026-11-15).
    return lzma.compress(buf.getvalue(), format=lzma.FORMAT_XZ, check=lzma.CHECK_CRC64, filters=[{"id": lzma.FILTER_LZMA2, "preset": 9 | lzma.PRESET_EXTREME, "dict_size": 8 << 20}])


def main() -> None:
    abi, usr, flock, out = sys.argv[1:5]
    lib_dir = os.path.join(usr, "lib")
    jni = os.path.join(out, "jniLibs", abi)
    assets = os.path.join(out, "assets", "native")
    for d in (jni, assets):
        shutil.rmtree(d, ignore_errors=True)
        os.makedirs(d)

    py_version = python_version(usr)
    stdlib = os.path.join(lib_dir, f"python{py_version}")
    py_modules = python_modules(stdlib)
    programs = dict(BINS, python3=f"bin/python{py_version}")
    aliases = dict(ALIASES, python="python3", **{f"python{py_version}": "python3"})

    # The closure of shared libraries the programs and Python's modules need from the Termux prefix; anything not there is
    # the system's (libc, libm, libdl, liblog, libandroid ...), which Android provides.
    libs, system = {}, set()
    todo = [os.path.join(usr, p) for p in programs.values()] + [os.path.join(stdlib, "lib-dynload", f) for f in py_modules]
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

    # The files are copied byte for byte: rewriting ELF headers (patchelf) corrupted node's libraries for bionic's linker
    # (SIGSEGV in prelink_image, run 41826201637). The app gives each library its own name back through a symlink
    # (native/lib/<soname> -> nativeLibraryDir/libdshn_*.so) and runs with LD_LIBRARY_PATH on that directory.
    def place(src: str, dst_name: str) -> None:
        dst = os.path.join(jni, dst_name)
        shutil.copyfile(src, dst)
        os.chmod(dst, 0o755)

    for name, path in libs.items():
        place(path, packaged_name(name))
    bins = {}
    for name, rel in programs.items():
        bins[name] = f"libdshn_bin_{mangle(name)}.so"
        place(os.path.join(usr, rel), bins[name])
    for alias, target in aliases.items():
        bins[alias] = bins[target]
    place(flock, "libdshn_flock.so")
    for f, lib in py_modules.items():
        place(os.path.join(stdlib, "lib-dynload", f), lib)

    ca_bundle = open(os.path.join(usr, "etc", "tls", "cert.pem"), "rb").read()
    with open(os.path.join(assets, "ca-bundle.crt"), "wb") as fh:
        fh.write(ca_bundle)
    archive = python_archive(stdlib, py_version, ca_bundle)
    with open(os.path.join(assets, "python.tar.xz"), "wb") as fh:
        fh.write(archive)

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
        "libs": {n: packaged_name(n) for n in sorted(libs)},
        "systemLibs": sorted(system),
        "packages": versions,
        # NativeRunner unpacks the archive to files/native/python (PYTHONHOME) when its sha256 changes and links each module
        # file name in lib/pythonX.Y/lib-dynload to its library; the programs are on PATH only once that worked.
        "python": {
            "version": py_version,
            "archive": "python.tar.xz",
            "sha256": hashlib.sha256(archive).hexdigest(),
            "programs": sorted(["python3", *(a for a, target in aliases.items() if target == "python3")]),
            "modules": py_modules,
        },
    }
    with open(os.path.join(assets, "manifest.json"), "w") as fh:
        json.dump(manifest, fh, indent=1, sort_keys=True)
    total = sum(os.path.getsize(os.path.join(jni, f)) for f in os.listdir(jni))
    py_libs = sum(os.path.getsize(os.path.join(jni, lib)) for lib in py_modules.values())
    print(f"python {py_version}: {len(py_modules)} extension modules ({py_libs / 1048576:.1f} MiB), python.tar.xz {len(archive) / 1048576:.1f} MiB")
    print(f"{abi}: {len(bins)} programs, {len(libs)} libraries, {total / 1048576:.1f} MiB; system: {' '.join(sorted(system))}")


if __name__ == "__main__":
    main()
