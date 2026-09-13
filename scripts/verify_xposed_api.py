#!/usr/bin/env python3
"""verify_xposed_api.py -- link-check a module's Xposed references before install.

The first build of this module compiled, packaged, installed, and *loaded* --
and then failed every hook with::

    NoSuchMethodError: No static method findAndHookMethod(Ljava/lang/Class;
        Ljava/lang/String;[Ljava/lang/Object;)V
        in class Lde/robv/android/xposed/XposedHelpers;

The cause was a one-word mistake in the compile-time stub: the real method
returns ``XC_MethodHook.Unhook``, not ``void``. Nothing in the normal build
pipeline can see that, because the stub is by definition a lie -- it only has to
satisfy javac, and javac was satisfied.

This script closes that gap. It reads the *real* framework dex off the device
and asserts that every Xposed member the module dex references actually exists
with the exact same descriptor. A module that would attach and then silently
hook nothing now fails the build instead.

Usage
-----
    python verify_xposed_api.py <module.dex> <framework.dex> [...more framework dex]

Exit code 0 = every reference resolved, 1 = at least one dangling reference.
"""

from __future__ import annotations

import os
import sys
from typing import Dict, List, Set, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dex_probe import Dex  # noqa: E402

XPREFIX = "Lde/robv/android/xposed/"


def declared_methods(dex: Dex) -> Set[Tuple[str, str, str]]:
    """(class_descr, method_name, proto_descr) actually defined by this dex."""
    out: Set[Tuple[str, str, str]] = set()
    for ci, _a, _s, cd, _sf in dex.class_defs():
        cls = dex.type_descr(ci)
        for mi, _acc, _code in dex.methods_of(cd):
            _c, n, p = dex.method_id(mi)
            out.add((cls, n, p))
    return out


def referenced_xposed(dex: Dex) -> Dict[Tuple[str, str, str], List[str]]:
    """Xposed members this dex calls, mapped to the methods that call them."""
    callers: Dict[Tuple[str, str, str], List[str]] = {}
    wanted: Set[int] = set()
    for mi in range(dex.method_ids_size):
        c, n, p = dex.method_id(mi)
        if c.startswith(XPREFIX):
            wanted.add(mi)
            callers.setdefault((c, n, p), [])
    if not wanted:
        return {}

    for ci, _a, _s, cd, _sf in dex.class_defs():
        cls = dex.type_descr(ci)
        for mi, _acc, code in dex.methods_of(cd):
            ins = dex.insns(code)
            if not ins:
                continue
            for kind, v in _walk_invokes(ins):
                if kind and v in wanted:
                    _c, n, p = dex.method_id(mi)
                    target = (dex.method_id(v)[0], dex.method_id(v)[1], dex.method_id(v)[2])
                    callers[target].append("%s.%s%s" % (cls, n, p))
    return callers


def _walk_invokes(insns):
    from dex_probe import WIDTH, INVOKE_OPS
    i = 0
    n = len(insns)
    while i < n:
        op = insns[i] & 0xFF
        w = WIDTH[op]
        if w < 1 or i + w > n:
            break
        if op in INVOKE_OPS and w >= 3:
            if op in (0x74, 0x75, 0x76, 0x77, 0x78):
                v = insns[i + 1] | (insns[i + 2] << 16)
            else:
                v = insns[i + 1]
            yield True, v
        i += w


def main(argv: List[str]) -> int:
    if len(argv) < 3:
        print(__doc__)
        return 2
    module_dex = Dex(os.path.basename(argv[1]), open(argv[1], "rb").read())
    framework: Set[Tuple[str, str, str]] = set()
    for path in argv[2:]:
        fw = Dex(os.path.basename(path), open(path, "rb").read())
        framework |= declared_methods(fw)

    if not framework:
        print("!! no framework methods parsed -- wrong file passed?")
        return 1

    refs = referenced_xposed(module_dex)
    if not refs:
        print("-- module references no de.robv.android.xposed members (nothing to check)")
        return 0

    bad = []
    for (cls_name, name, proto), sites in sorted(refs.items()):
        if (cls_name, name, proto) not in framework:
            bad.append((cls_name, name, proto, sites))

    print("checked %d distinct Xposed references" % len(refs))
    for cls_name, name, proto, sites in bad:
        pretty = cls_name[1:-1].replace("/", ".")
        print("!! MISSING %s.%s%s" % (pretty, name, proto))
        for s in sorted(set(sites))[:4]:
            print("      called from %s" % s)

    if bad:
        print("FAIL: %d dangling Xposed reference(s)" % len(bad))
        return 1
    print("OK: every referenced Xposed member exists in the framework dex")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
    raise SystemExit(main(sys.argv))
