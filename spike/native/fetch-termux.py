#!/usr/bin/env python3
"""Downloads Termux packages (and their dependencies) for one arch and unpacks them into OUT/usr.

Usage: fetch-termux.py ARCH OUT PKG...   (ARCH: x86_64 | aarch64)
The Termux prefix /data/data/com.termux/files/usr becomes OUT/usr. Only the main repository is used.
"""
import io
import os
import shutil
import subprocess
import sys
import tarfile
import urllib.request

REPO = "https://packages.termux.dev/apt/termux-main"
PREFIX = "data/data/com.termux/files/usr"


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def parse(index: str) -> dict:
    pkgs = {}
    for block in index.split("\n\n"):
        fields = {}
        for line in block.splitlines():
            if ": " in line and not line.startswith(" "):
                k, v = line.split(": ", 1)
                fields[k] = v
        if "Package" in fields:
            pkgs[fields["Package"]] = fields
    return pkgs


def deps(fields: dict) -> list:
    out = []
    for part in fields.get("Depends", "").split(","):
        name = part.strip().split(" ")[0].split("|")[0].strip()
        if name:
            out.append(name)
    return out


def main() -> None:
    arch, out, wanted = sys.argv[1], sys.argv[2], sys.argv[3:]
    index = fetch(f"{REPO}/dists/stable/main/binary-{arch}/Packages").decode()
    pkgs = parse(index)
    todo, seen = list(wanted), []
    while todo:
        name = todo.pop()
        if name in seen:
            continue
        if name not in pkgs:
            print(f"skip (not in main repo): {name}")
            continue
        seen.append(name)
        todo.extend(deps(pkgs[name]))
    os.makedirs(out, exist_ok=True)
    work = os.path.join(out, ".debs")
    os.makedirs(work, exist_ok=True)
    for name in seen:
        f = pkgs[name]
        deb = os.path.join(work, os.path.basename(f["Filename"]))
        print(f"{name} {f['Version']}")
        with open(deb, "wb") as fh:
            fh.write(fetch(f"{REPO}/{f['Filename']}"))
        subprocess.run(["dpkg-deb", "-x", deb, os.path.join(out, ".root")], check=True)
    src = os.path.join(out, ".root", PREFIX)
    dst = os.path.join(out, "usr")
    shutil.copytree(src, dst, symlinks=True, dirs_exist_ok=True)
    shutil.rmtree(os.path.join(out, ".root"))
    shutil.rmtree(work)
    with open(os.path.join(out, "packages.txt"), "w") as fh:
        fh.write("\n".join(f"{n} {pkgs[n]['Version']}" for n in sorted(seen)) + "\n")


if __name__ == "__main__":
    main()
