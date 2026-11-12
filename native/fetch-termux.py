#!/usr/bin/env python3
"""Downloads Termux packages for one arch and unpacks them into OUT/usr.

Usage: fetch-termux.py ARCH OUT [--exact] [--lock FILE [--update-lock]] [--cache DIR] PKG...
  ARCH          x86_64 | aarch64
  --exact       only the named packages (no dependency resolution): the caller lists every package it needs
  --lock FILE   pinned "<arch> <package> <version> <filename> <sha256>" lines: download exactly those files and verify them;
                fails if a package is not pinned for ARCH; a file that is gone from the pool (and the cache) is replaced
                by the repository's current build, with a warning
  --update-lock rewrite FILE's lines for ARCH from the current repository instead of reading them
  --cache DIR   keep the verified .deb files here (CI caches it: Termux keeps only the newest build in its pool)
The Termux prefix /data/data/com.termux/files/usr becomes OUT/usr. Only the main repository is used.
"""
import hashlib
import os
import shutil
import subprocess
import sys
import urllib.error
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


def read_lock(path: str) -> list:
    if not os.path.exists(path):
        return []
    return [line.split() for line in open(path) if line.strip() and not line.startswith("#")]


def main() -> None:
    args = sys.argv[1:]
    arch, out = args[0], args[1]
    rest = args[2:]
    exact = "--exact" in rest
    update = "--update-lock" in rest
    lock = rest[rest.index("--lock") + 1] if "--lock" in rest else None
    cache = rest[rest.index("--cache") + 1] if "--cache" in rest else None
    skip = {lock, cache}
    wanted = [a for a in rest if not a.startswith("--") and a not in skip]

    # name -> (version, filename, sha256)
    chosen = {}
    if lock and not update:
        pinned = {r[1]: (r[2], r[3], r[4]) for r in read_lock(lock) if r[0] == arch}
        missing = [w for w in wanted if w not in pinned]
        if missing:
            sys.exit(f"not pinned for {arch} in {lock}: {' '.join(missing)} (run with --update-lock)")
        chosen = {w: pinned[w] for w in wanted}
    else:
        pkgs = parse(fetch(f"{REPO}/dists/stable/main/binary-{arch}/Packages").decode())
        todo, seen = list(wanted), []
        while todo:
            name = todo.pop()
            if name in seen:
                continue
            if name not in pkgs:
                print(f"skip (not in main repo): {name}")
                continue
            seen.append(name)
            if not exact:
                todo.extend(deps(pkgs[name]))
        chosen = {n: (pkgs[n]["Version"], pkgs[n]["Filename"], pkgs[n]["SHA256"]) for n in seen}
        if lock:
            kept = [r for r in read_lock(lock) if r[0] != arch]
            rows = kept + [[arch, n, *chosen[n]] for n in sorted(chosen)]
            with open(lock, "w") as fh:
                fh.write("# arch package version filename sha256 — written by native/fetch-termux.py --update-lock\n")
                fh.writelines(" ".join(r) + "\n" for r in sorted(rows))

    work = cache or os.path.join(out, ".debs")
    os.makedirs(work, exist_ok=True)
    root = os.path.join(out, ".root")
    shutil.rmtree(root, ignore_errors=True)
    os.makedirs(root)
    current, replaced = None, []
    for name, (version, filename, sha) in sorted(dict(chosen).items()):
        deb = os.path.join(work, os.path.basename(filename))
        if not (os.path.exists(deb) and hashlib.sha256(open(deb, "rb").read()).hexdigest() == sha):
            try:
                data = fetch(f"{REPO}/{filename}")
            except urllib.error.HTTPError as e:
                if e.code != 404 or not lock or update:
                    raise
                # Termux keeps only the newest build of a package: a pin that left the pool (and the cache) must not stop a
                # release. The repository's current build is taken instead, checked against the repository index, and said
                # loudly; the emulator suite then runs on it before anything ships. Re-pin with --update-lock.
                if current is None:
                    current = parse(fetch(f"{REPO}/dists/stable/main/binary-{arch}/Packages").decode())
                if name not in current:
                    sys.exit(f"{name} {version}: gone from the pool and not in the repository index")
                was = version
                version, filename, sha = current[name]["Version"], current[name]["Filename"], current[name]["SHA256"]
                chosen[name] = (version, filename, sha)
                replaced.append(f"{name} {was} -> {version}")
                print(f"WARNING: pinned {name} {was} is gone from the Termux pool; using {version}", file=sys.stderr)
                deb = os.path.join(work, os.path.basename(filename))
                data = fetch(f"{REPO}/{filename}")
            got = hashlib.sha256(data).hexdigest()
            if got != sha:
                sys.exit(f"{name} {version}: sha256 {got} != pinned {sha}")
            with open(deb, "wb") as fh:
                fh.write(data)
        print(f"{name} {version}")
        subprocess.run(["dpkg-deb", "-x", deb, root], check=True)
    dst = os.path.join(out, "usr")
    shutil.rmtree(dst, ignore_errors=True)
    shutil.move(os.path.join(root, PREFIX), dst)
    shutil.rmtree(root)
    if not cache:
        shutil.rmtree(work)
    with open(os.path.join(out, "packages.txt"), "w") as fh:
        fh.write("\n".join(f"{n} {chosen[n][0]}" for n in sorted(chosen)) + "\n")
    if replaced:
        print("WARNING: pins replaced by the repository's current builds: " + "; ".join(replaced), file=sys.stderr)


if __name__ == "__main__":
    main()
