#!/usr/bin/env python3
"""Converts upstream superpowers (https://github.com/obra/superpowers, MIT) into dsh skills for DSH Mobile.

Usage: convert.py UPSTREAM_CHECKOUT OUT_DIR
  OUT_DIR becomes android/app/src/main/assets/dsh-skills: one folder per skill, named dshm-sp-<upstream name>, installed by the
  app into $DSH_HOME/skills (AppSkills). The upstream text is kept; only what dsh needs changes:
  - skill names: `superpowers:<name>` and `name: <name>` become dshm-sp-<name> (dsh skill names cannot hold a colon, and the
    app owns the dshm- prefix: those folders are rewritten on every start and removed when no longer shipped);
  - using-superpowers gets references/dsh-tools.md (the tool mapping, like upstream's own per-harness references) and a line
    pointing to it;
  - a short DSH Mobile note on top of brainstorming and using-superpowers, so the app's own skills (novel chapters, bookkeeping,
    daily brief) keep their simple flow;
  - diagnosing-superpowers is left out (it reads Claude Code's own session transcripts, which dsh does not have).
"""
import os
import re
import shutil
import subprocess
import sys

PREFIX = "dshm-sp-"
SKIP = {"diagnosing-superpowers"}
HERE = os.path.dirname(os.path.abspath(__file__))

APP_NOTE = """> **DSH Mobile note.** This skill comes from superpowers, adapted for dsh on the owner's phone. The app's own skills
> (`dshm-novel`, `dshm-bookkeeping`, `dshm-daily-brief`, `dshm-phone`, `dshm-pages`) win for their own tasks: writing the next
> chapter, recording an expense or giving the morning briefing never needs a design session first. Talk to the owner in
> Traditional Chinese, in plain words, and keep questions short: they read on a phone.

"""


def rename(text: str, names: set) -> str:
    text = re.sub(r"superpowers:([a-z0-9-]+)", lambda m: PREFIX + m.group(1) if m.group(1) in names else m.group(0), text)
    return text


def main() -> None:
    upstream, out = sys.argv[1], sys.argv[2]
    src = os.path.join(upstream, "skills")
    names = {n for n in os.listdir(src) if os.path.isdir(os.path.join(src, n)) and n not in SKIP}
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    for name in sorted(names):
        dst = os.path.join(out, PREFIX + name)
        shutil.copytree(os.path.join(src, name), dst)
        for root, _, files in os.walk(dst):
            for f in files:
                path = os.path.join(root, f)
                try:
                    text = open(path, encoding="utf-8").read()
                except UnicodeDecodeError:
                    continue
                new = rename(text, names)
                if f == "SKILL.md" and root == dst:
                    new = re.sub(r"(?m)^name:\s*" + re.escape(name) + r"\s*$", "name: " + PREFIX + name, new, count=1)
                    if name in ("brainstorming", "using-superpowers"):
                        end = new.index("\n---", 3) + 4
                        new = new[:end] + "\n\n" + APP_NOTE + new[end:].lstrip("\n")
                    if name == "using-superpowers":
                        new = new.replace("- Claude Code: `references/claude-code-tools.md`",
                                          "- dsh (DSH Mobile): `references/dsh-tools.md`\n- Claude Code: `references/claude-code-tools.md`", 1)
                if new != text:
                    open(path, "w", encoding="utf-8").write(new)
    shutil.copyfile(os.path.join(HERE, "dsh-tools.md"), os.path.join(out, PREFIX + "using-superpowers", "references", "dsh-tools.md"))
    shutil.copyfile(os.path.join(upstream, "LICENSE"), os.path.join(out, "LICENSE-superpowers"))
    commit = subprocess.run(["git", "-C", upstream, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    with open(os.path.join(out, "UPSTREAM"), "w") as fh:
        fh.write(f"https://github.com/obra/superpowers {commit}\nconverted by third_party/superpowers/convert.py\n")
    leftover = subprocess.run(["grep", "-rln", "superpowers:", out], capture_output=True, text=True).stdout.strip()
    print(f"{len(names)} skills -> {out}" + (f"; still naming superpowers: {leftover}" if leftover else ""))


if __name__ == "__main__":
    main()
