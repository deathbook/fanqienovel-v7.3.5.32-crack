#!/usr/bin/env python3
"""bso_grep.py -- byte-level search across the native libraries of an APK.

Find which .so registers a given JNI native method, which library contains a
given constant, and dump the printable strings around a hit.

Usage
-----
  python bso_grep.py <apk|dir> -e <needle> [...]      # list libs containing needle
  python bso_grep.py <apk|dir> -s <lib> <needle>      # show offsets inside one lib
  python bso_grep.py <apk|dir> -S <lib>               # dump printable strings of one lib
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import zipfile
from typing import Iterable, List, Tuple


def iter_libs(src: str) -> Iterable[Tuple[str, bytes]]:
    if os.path.isdir(src):
        for dp, _dn, fn in os.walk(src):
            for f in fn:
                if f.endswith(".so"):
                    p = os.path.join(dp, f)
                    with open(p, "rb") as fh:
                        yield os.path.relpath(p, src), fh.read()
    else:
        with zipfile.ZipFile(src) as z:
            for n in z.namelist():
                if n.endswith(".so"):
                    yield n, z.read(n)


def main(argv: List[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("-e", "--each", nargs="+", help="needles; list libs that contain ANY of them")
    ap.add_argument("-s", "--in-lib", nargs=2, metavar=("LIB_SUBSTR", "NEEDLE"))
    ap.add_argument("-S", "--strings", metavar="LIB_SUBSTR")
    ap.add_argument("-w", "--context", type=int, default=48)
    ap.add_argument("-m", "--max", type=int, default=200)
    a = ap.parse_args(argv[1:])

    needles = [n.encode() for n in (a.each or [])]

    hits = 0
    for name, blob in iter_libs(a.src):
        if a.each:
            found = [nd for nd in needles if nd in blob]
            if found:
                hits += 1
                print("%-46s %10d  %s" % (
                    name, len(blob),
                    " ".join("%r@%d" % (nd.decode(), blob.find(nd)) for nd in found)))
        if a.in_lib:
            sub, needle = a.in_lib
            if sub not in name:
                continue
            nd = needle.encode()
            off = 0
            cnt = 0
            while True:
                i = blob.find(nd, off)
                if i < 0:
                    break
                cnt += 1
                off = i + 1
                if cnt > a.max:
                    break
                lo = max(0, i - a.context)
                hi = min(len(blob), i + len(nd) + a.context)
                snippet = blob[lo:hi]
                printable = "".join(chr(b) if 32 <= b < 127 else "." for b in snippet)
                print("%s +0x%x  %s" % (name, i, printable))
            print("-- %d occurrence(s) in %s" % (cnt, name))
        if a.strings:
            if a.strings not in name:
                continue
            pat = re.compile(rb"[\x20-\x7e]{5,}")
            for m in pat.finditer(blob):
                print("%s +0x%x  %s" % (name, m.start(), m.group().decode("ascii")))
    if a.each and not hits:
        print("-- no library contains those needles")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
    raise SystemExit(main(sys.argv))
