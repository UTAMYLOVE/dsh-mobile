#!/usr/bin/env python3
"""Builds the startup animation's handwriting font, res/font/intro_hand.ttf, from 辰宇落雁體 2.0 Thin.

Why a subset: the full font is 9.5 MB with about 10,000 characters. The intro needs the 4,808 characters of
《常用國字標準字體表》 (so most names show in the hand), every character of the intro's own strings, ASCII and the CJK
punctuation. Hinting is dropped (--no-hinting): the font's own hinting draws broken strokes at small sizes on Android.

Why a rename: a subset is a "Modified Version" under the SIL Open Font License 1.1, and this font reserves the names
"辰宇落雁" and "Chenyuluoyan". So the family, full, unique and PostScript names (name IDs 1, 3, 4, 6, 16, 17) become
"DSHM Luoyan Hand". The copyright notice (ID 0), the designer credits and the licence (IDs 13, 14) are kept; the full
licence text ships in the app (assets/licenses/intro-hand-OFL.txt) and on its 開放原始碼授權 page.

It also writes res/raw/intro_hand_chars.txt, every character the subset covers, so the app knows which characters of a
name fall back to the system font (those are drawn without the thickening stroke).

Rerun it after adding or changing any intro string (res/values/strings.xml names starting with intro_, and every
res/values/strings_intro_*.xml), so no intro text ever falls back to the system font:

    pip install fonttools          # 4.68 was used
    # the source font: ChenYuluoyan-2.0-Thin.ttf from
    #   https://github.com/Chenyu-otf/chenyuluoyan_thin/releases/tag/v2.0
    python3 tools/fonts/subset_intro_font.py --source /path/to/ChenYuluoyan-2.0-Thin.ttf

Then run the app's FontsTest (it checks the names, the copyright and that every intro string is covered).
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

from fontTools.ttLib import TTFont

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
RES = os.path.join(REPO, "android", "app", "src", "main", "res")

FAMILY = "DSHM Luoyan Hand"
STYLE = "Thin"
POSTSCRIPT = "DSHMLuoyanHand-Thin"
RESERVED = ("辰宇落雁", "chenyuluoyan")
# Name IDs that present the font's name to users: replaced. Everything else (copyright, designer, licence) is kept.
RENAMED = {1, 3, 4, 6, 16, 17}


def read_list(path):
    """The characters of a list file: every non-comment line, all of its characters."""
    out = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("#"):
                continue
            out.extend(ch for ch in line.strip() if not ch.isspace())
    return out


def intro_strings():
    """Every string the intro draws: strings.xml entries named intro_*, and every string in strings_intro_*.xml."""
    texts = []
    values = os.path.join(RES, "values")
    for name in sorted(os.listdir(values)):
        if not name.endswith(".xml"):
            continue
        every = name.startswith("strings_intro_")
        if name != "strings.xml" and not every:
            continue
        root = ET.parse(os.path.join(values, name)).getroot()
        for el in root.iter():
            if el.tag not in ("string", "item"):
                continue
            owner = el.get("name") or ""
            if el.tag == "item" or every or owner.startswith("intro_"):
                texts.append("".join(el.itertext()))
    return texts


def wanted_chars():
    chars = set(read_list(os.path.join(HERE, "edu-standard-4808.txt")))
    # 彝 is how everyone writes the list's 彞.
    chars.add("彝")
    for text in intro_strings():
        # Format placeholders (%1$s) and escapes are not drawn.
        text = re.sub(r"%(\d+\$)?[sdf]", "", text).replace("\\n", "").replace("\\'", "'").replace('\\"', '"')
        chars.update(ch for ch in text if not ch.isspace() or ch == "\u3000")
    chars.update(chr(c) for c in range(0x20, 0x7F))
    chars.update(chr(c) for c in range(0x3000, 0x3040))
    chars.update(chr(c) for c in range(0xFF01, 0xFF5F))
    chars.update("·‧…—–‘’“”•")
    return chars


def rename(font):
    name = font["name"]
    version = (name.getDebugName(5) or "").strip()
    # Every language's record of these IDs goes (the source also had Traditional Chinese ones); English ones replace them.
    name.names = [n for n in name.names if n.nameID not in RENAMED]
    for name_id, text in (
        (1, FAMILY), (3, f"{version.replace('Version ', '')};DSHM;{POSTSCRIPT}"), (4, f"{FAMILY} {STYLE}"),
        (6, POSTSCRIPT), (16, FAMILY), (17, STYLE),
    ):
        name.setName(text, name_id, 3, 1, 0x409)
    # The version string says what this file is.
    for n in name.names:
        if n.nameID == 5:
            n.string = f"{version}; DSHM Mobile subset"
    # The licence description names the original as the source of this Modified Version (OFL condition 4 allows that).
    for n in name.names:
        if n.nameID == 13:
            n.string = (
                "DSHM Luoyan Hand is a subset of a font written by Liu, Wei-Chen and created by Wang, Li-Yu, "
                "renamed because it is a Modified Version. Licensed under the SIL Open Font License, Version 1.1."
            )
    for n in name.names:
        text = n.toUnicode().lower()
        if n.nameID not in (0, 9, 10, 12, 13, 14, 19) and any(r in text for r in RESERVED):
            raise SystemExit(f"name ID {n.nameID} still holds a reserved name: {n.toUnicode()!r}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", required=True, help="ChenYuluoyan-2.0-Thin.ttf (the original release file)")
    ap.add_argument("--out", default=os.path.join(RES, "font", "intro_hand.ttf"))
    ap.add_argument("--chars-out", default=os.path.join(RES, "raw", "intro_hand_chars.txt"))
    args = ap.parse_args()

    chars = wanted_chars()
    with tempfile.TemporaryDirectory() as tmp:
        unicodes = os.path.join(tmp, "unicodes.txt")
        with open(unicodes, "w") as fh:
            fh.write(",".join("U+%04X" % ord(c) for c in sorted(chars)))
        cut = os.path.join(tmp, "cut.ttf")
        cmd = [
            sys.executable, "-m", "fontTools.subset", args.source,
            "--unicodes-file=" + unicodes, "--no-hinting", "--name-IDs=*", "--name-languages=*",
            "--layout-features+=palt,halt,vpal", "--output-file=" + cut,
        ]
        subprocess.run(cmd, check=True)
        font = TTFont(cut)
        rename(font)
        os.makedirs(os.path.dirname(args.out), exist_ok=True)
        font.save(args.out)

    cmap = TTFont(args.out).getBestCmap()
    covered = sorted(chr(c) for c in cmap if c > 0x20)
    missing = sorted(c for c in chars if ord(c) not in cmap and c != " ")
    os.makedirs(os.path.dirname(args.chars_out), exist_ok=True)
    with open(args.chars_out, "w", encoding="utf-8") as fh:
        fh.write("".join(covered))
    print(f"{args.out}: {os.path.getsize(args.out)} bytes, {len(covered)} characters")
    if missing:
        print("not in the source font (these fall back to the system font):", "".join(missing))


if __name__ == "__main__":
    main()
