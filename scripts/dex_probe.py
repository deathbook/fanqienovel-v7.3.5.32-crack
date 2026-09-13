#!/usr/bin/env python3
"""dex_probe.py -- precise, architecture-independent questions about an APK's dex.

Why not just grep the smali?  Two reasons that bit us:
  * apktool redistributes classes across ``smali_classesN`` dirs, so the dex a
    string lives in tells you nothing about which directory to grep.
  * baksmali escapes non-ASCII literals (``\\u81ea\\u52a8...``), so a naive
    grep for the Chinese text finds nothing.

This reads the dex directly, so both problems disappear.

Subcommands
-----------
  refs    <apk> <regex>   classes/methods that load a matching string
  jumbo   <apk> <regex>   same, but only const-string/jumbo (rare, catches
                          strings in big dexes)
  methods <apk> <regex>   declared methods whose name matches
  classes <apk> <regex>   declared classes whose name matches
  fields  <apk> <regex>   declared fields whose name matches
  calls   <apk> <regex>   methods that *invoke* a matching method
  xrefs   <apk> <class> <method>   who invokes that exact method
  strdump <apk> <regex>   just print matching strings with their indices

Options
-------
  --dex N     restrict to classesN.dex (repeatable)
  --limit N   cap output lines (default 200)

The instruction walk uses the real Dalvik width table so we never mistake
operand bytes for opcodes.
"""

from __future__ import annotations

import io
import os
import re
import struct
import sys
import zipfile
from typing import Dict, Iterator, List, Optional, Sequence, Tuple

# --------------------------------------------------------------------------
# opcode widths, in 16-bit code units.  Index == opcode byte.
# Unknown/unused opcodes are 1 so the walk always terminates.
# --------------------------------------------------------------------------
WIDTH = [
    # 0x00 nop .. 0x0f return
    1, 1, 2, 3, 1, 2, 3, 1, 2, 3, 1, 1, 1, 1, 1, 1,
    # 0x10 return-wide .. 0x1f check-cast
    1, 1, 1, 2, 3, 2, 2, 3, 5, 2, 2, 3, 2, 1, 1, 2,
    # 0x20 instance-of .. 0x2f cmpl-double
    2, 1, 2, 2, 3, 3, 3, 1, 1, 2, 3, 3, 3, 2, 2, 2,
    # 0x30 cmpg-double .. 0x3f (0x3e,0x3f unused)
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1,
    # 0x40 unused .. 0x4f aput-byte   (aget/aput family, all 1)
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
    # 0x50 aput-char .. 0x5f iput-short
    1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
    # 0x60 sget .. 0x6f invoke-super  (0x6e,0x6f are 35c == 3 units)
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3,
    # 0x70 invoke-direct .. 0x7f  (0x73 unused; 0x74-0x78 range forms)
    3, 3, 3, 1, 3, 3, 3, 3, 3, 1, 1, 1, 1, 1, 1, 1,
    # 0x80 neg-double .. 0x8f int-to-short
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
    # 0x90 add-int .. 0x9f
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
    # 0xa0 .. 0xaf
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
    # 0xb0 add-int/lit16 .. 0xbf
    3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
    # 0xc0 .. 0xcf
    3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
    # 0xd0 add-int/lit8 .. 0xdf
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
    # 0xe0 .. 0xef   (only 0xe0-0xe2 used)
    2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
    # 0xf0 unused .. 0xff const-method-type
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 4, 4, 4, 4, 2, 2,
]
assert len(WIDTH) == 256, len(WIDTH)

OP_CONST_STRING = 0x1A
OP_CONST_STRING_JUMBO = 0x1B
OP_INVOKE_STATIC = 0x71
OP_INVOKE_SUPER = 0x6F
OP_NEW_INSTANCE = 0x22
OP_CONST_CLASS = 0x1C

INVOKE_OPS = {0x6E, 0x6F, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78}
# 0x6e invoke-virtual, 0x6f invoke-super, 0x70 invoke-direct,
# 0x71 invoke-static, 0x72 invoke-interface,
# 0x74..0x78 the /range forms


# --------------------------------------------------------------------------
# primitives
# --------------------------------------------------------------------------
def uleb(buf: bytes, off: int) -> Tuple[int, int]:
    result = 0
    shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, off
        shift += 7


def mutf8(raw: bytes) -> str:
    try:
        s = raw.decode("utf-8", "surrogatepass")
    except UnicodeDecodeError:
        return raw.decode("utf-8", "replace")
    if any("\ud800" <= c <= "\udfff" for c in s):
        try:
            s = s.encode("utf-16", "surrogatepass").decode("utf-16")
        except UnicodeError:
            pass
    return s


class Dex:
    def __init__(self, name: str, data: bytes):
        self.name = name
        self.data = data
        (self.magic, _ck, _sig, self.file_size, self.header_size,
         self.endian_tag, _ls, _lo, _mo, self.string_ids_size,
         self.string_ids_off, self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off,
         self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off,
         self.class_defs_size, self.class_defs_off) = struct.unpack_from(
            "<8sI20sIIIIIIIIIIIIIIIIII", data, 0)
        self._strings: Optional[List[str]] = None

    # -- string pool -------------------------------------------------------
    @property
    def strings(self) -> List[str]:
        if self._strings is None:
            out = []
            for i in range(self.string_ids_size):
                off = struct.unpack_from("<I", self.data, self.string_ids_off + i * 4)[0]
                _n, p = uleb(self.data, off)
                end = self.data.index(b"\x00", p)
                out.append(mutf8(self.data[p:end]))
            self._strings = out
        return self._strings

    def str(self, idx: int) -> str:
        if idx < 0 or idx >= self.string_ids_size:
            return "<bad#%d>" % idx
        return self.strings[idx]

    # -- id tables ---------------------------------------------------------
    def type_descr(self, idx: int) -> str:
        d = struct.unpack_from("<I", self.data, self.type_ids_off + idx * 4)[0]
        return self.str(d)

    def method_id(self, idx: int) -> Tuple[str, str, str]:
        c, p, n = struct.unpack_from("<HHI", self.data, self.method_ids_off + idx * 8)
        return self.type_descr(c), self.str(n), self.proto_descr(p)

    def proto_descr(self, idx: int) -> str:
        _shorty, ret, params_off = struct.unpack_from(
            "<III", self.data, self.proto_ids_off + idx * 12)
        args = []
        if params_off:
            n = struct.unpack_from("<I", self.data, params_off)[0]
            for i in range(n):
                t = struct.unpack_from("<H", self.data, params_off + 4 + i * 2)[0]
                args.append(self.type_descr(t))
        return "(" + "".join(args) + ")" + self.type_descr(ret)

    def field_id(self, idx: int) -> Tuple[str, str, str]:
        c, t, n = struct.unpack_from("<HHI", self.data, self.field_ids_off + idx * 8)
        return self.type_descr(c), self.str(n), self.type_descr(t)

    def class_defs(self) -> Iterator[Tuple[int, int, int, int, int]]:
        """yield (class_idx, access, super_idx, class_data_off, source_idx)"""
        for i in range(self.class_defs_size):
            base = self.class_defs_off + i * 32
            (class_idx, access, super_idx, _ifaces, source_idx,
             _anno, class_data_off, _sv) = struct.unpack_from("<IIIIIIII", self.data, base)
            yield class_idx, access, super_idx, class_data_off, source_idx

    def methods_of(self, class_data_off: int):
        """yield (method_idx, access, code_off) for one class."""
        if class_data_off == 0:
            return
        p = class_data_off
        sf, p = uleb(self.data, p)
        inf, p = uleb(self.data, p)
        dm, p = uleb(self.data, p)
        vm, p = uleb(self.data, p)
        for _ in range(sf):
            _i, p = uleb(self.data, p); _a, p = uleb(self.data, p)
        for _ in range(inf):
            _i, p = uleb(self.data, p); _a, p = uleb(self.data, p)
        for total in (dm, vm):
            idx = 0
            for _ in range(total):
                d, p = uleb(self.data, p)
                a, p = uleb(self.data, p)
                code_off, p = uleb(self.data, p)
                idx += d
                yield idx, a, code_off

    def fields_of(self, class_data_off: int):
        if class_data_off == 0:
            return
        p = class_data_off
        sf, p = uleb(self.data, p)
        inf, p = uleb(self.data, p)
        _dm, p = uleb(self.data, p)
        _vm, p = uleb(self.data, p)
        for total in (sf, inf):
            idx = 0
            for _ in range(total):
                d, p = uleb(self.data, p)
                a, p = uleb(self.data, p)
                idx += d
                yield idx, a

    def insns(self, code_off: int) -> List[int]:
        if code_off == 0:
            return []
        _regs, _ins, _outs, _tries, _dbg, size = struct.unpack_from(
            "<HHHHII", self.data, code_off)
        start = code_off + 16
        raw = self.data[start:start + size * 2]
        return list(struct.unpack_from("<%dH" % size, raw, 0)) if size else []


# --------------------------------------------------------------------------
# walker
# --------------------------------------------------------------------------
def walk(insns: Sequence[int], want_strings: set, want_methods: set,
         want_types: set):
    """Yield (kind, value) for interesting instructions.

    kind is one of 'str', 'invoke', 'new', 'class'.
    """
    i = 0
    n = len(insns)
    while i < n:
        op = insns[i] & 0xFF
        w = WIDTH[op]
        if w < 1 or i + w > n:
            break
        if op == OP_CONST_STRING and want_strings:
            v = insns[i + 1]
            if v in want_strings:
                yield "str", v
        elif op == OP_CONST_STRING_JUMBO and want_strings:
            v = insns[i + 1] | (insns[i + 2] << 16)
            if v in want_strings:
                yield "str", v
        elif op in INVOKE_OPS and want_methods:
            if w < 3:
                i += w
                continue
            if op in (0x74, 0x75, 0x76, 0x77, 0x78):  # /range -> 3rc form
                v = insns[i + 1] | (insns[i + 2] << 16)
            else:  # 35c, second code unit is BBBB
                v = insns[i + 1]
            if v in want_methods:
                yield "invoke", v
        elif op == OP_NEW_INSTANCE and want_types:
            v = insns[i + 1]
            if v in want_types:
                yield "new", v
        elif op == OP_CONST_CLASS and want_types:
            v = insns[i + 1]
            if v in want_types:
                yield "class", v
        i += w


# --------------------------------------------------------------------------
# apk plumbing
# --------------------------------------------------------------------------
def iter_dex(apk: str, only: Optional[set] = None) -> Iterator[Tuple[str, bytes]]:
    if os.path.isdir(apk):
        for name in sorted(os.listdir(apk)):
            if name.endswith(".dex") and (only is None or name in only):
                with open(os.path.join(apk, name), "rb") as fh:
                    yield name, fh.read()
        return
    if apk.lower().endswith(".dex"):
        # a bare .dex (e.g. the one d8 just produced) is not a zip
        with open(apk, "rb") as fh:
            yield os.path.basename(apk), fh.read()
        return
    with zipfile.ZipFile(apk) as z:
        names = sorted([n for n in z.namelist() if n.endswith(".dex")],
                       key=lambda s: (len(s), s))
        for n in names:
            if only is not None and n not in only:
                continue
            yield n, z.read(n)


def load(apk: str, only: Optional[set]) -> List[Dex]:
    return [Dex(n, b) for n, b in iter_dex(apk, only)]


def pretty(descr: str) -> str:
    return descr[1:-1].replace("/", ".") if descr.startswith("L") and descr.endswith(";") else descr


# --------------------------------------------------------------------------
# subcommands
# --------------------------------------------------------------------------
def cmd_refs(apk: str, pattern: str, only, limit: int, jumbo_only: bool = False):
    rx = re.compile(pattern)
    seen = set()
    total = 0
    for dex in load(apk, only):
        idxs = {i for i, s in enumerate(dex.strings) if rx.search(s)}
        if not idxs:
            continue
        want_types: set = set()
        for ci, _a, _s, _cd, _sf in dex.class_defs():
            want_types.add(ci)
        for ci, _a, _s, cd, _sf in dex.class_defs():
            cls = dex.type_descr(ci)
            for mi, _acc, code in dex.methods_of(cd):
                ins = dex.insns(code)
                if not ins:
                    continue
                for kind, v in walk(ins, idxs, set(), want_types):
                    if kind == "str":
                        key = (dex.name, cls, mi)
                        if key in seen:
                            continue
                        seen.add(key)
                        total += 1
                        if total <= limit:
                            c, n, p = dex.method_id(mi)
                            print("%-14s %-60s %s%s   <= %r" % (
                                dex.name, pretty(cls), n, p, dex.str(v)[:70]))
    print("-- %d (class, method) sites reference matching strings" % total)


def cmd_methods(apk: str, pattern: str, only, limit: int, class_rx=None):
    rx = re.compile(pattern)
    count = 0
    for dex in load(apk, only):
        for ci, _a, _s, cd, _sf in dex.class_defs():
            cls = dex.type_descr(ci)
            if class_rx is not None and not class_rx.search(cls):
                continue
            for mi, _acc, _code in dex.methods_of(cd):
                _c, n, p = dex.method_id(mi)
                if rx.search(n):
                    count += 1
                    if count <= limit:
                        print("%-14s %-70s %s%s" % (dex.name, pretty(cls), n, p))
    print("-- %d methods" % count)


def cmd_classmembers(apk: str, class_pattern: str, only, limit: int):
    """dump every field + method of classes whose name matches class_pattern"""
    rx = re.compile(class_pattern)
    count = 0
    for dex in load(apk, only):
        for ci, _a, super_i, cd, _sf in dex.class_defs():
            cls = dex.type_descr(ci)
            if not rx.search(cls):
                continue
            count += 1
            print("=" * 100)
            print("%s   (%s)  super=%s" % (pretty(cls), dex.name, pretty(dex.type_descr(super_i))))
            for fi, acc in dex.fields_of(cd):
                _c, n, t = dex.field_id(fi)
                print("    FIELD  %-40s %-42s acc=0x%x" % (n, t, acc))
            for mi, acc, code in dex.methods_of(cd):
                _c, n, p = dex.method_id(mi)
                flag = " [NATIVE]" if acc & 0x100 else ""
                print("    METHOD %-40s %-42s acc=0x%x%s" % (n, p, acc, flag))
    print("-- %d class(es)" % count)


def cmd_classes(apk: str, pattern: str, only, limit: int):
    rx = re.compile(pattern)
    count = 0
    for dex in load(apk, only):
        for ci, _a, super_i, _cd, _sf in dex.class_defs():
            cls = dex.type_descr(ci)
            if rx.search(cls):
                count += 1
                if count <= limit:
                    print("%-14s %-80s super=%s" % (dex.name, pretty(cls), pretty(dex.type_descr(super_i))))
    print("-- %d classes" % count)


def cmd_fields(apk: str, pattern: str, only, limit: int):
    rx = re.compile(pattern)
    count = 0
    for dex in load(apk, only):
        for ci, _a, _s, cd, _sf in dex.class_defs():
            cls = dex.type_descr(ci)
            for fi, _acc in dex.fields_of(cd):
                c, n, t = dex.field_id(fi)
                if rx.search(n) or rx.search(cls):
                    count += 1
                    if count <= limit:
                        print("%-14s %-60s %s %s" % (dex.name, pretty(cls), n, t))
    print("-- %d fields" % count)


def cmd_calls(apk: str, pattern: str, only, limit: int):
    """methods that invoke a callee whose name matches `pattern`"""
    rx = re.compile(pattern)
    count = 0
    for dex in load(apk, only):
        want: Dict[int, Tuple[str, str, str]] = {}
        for mi in range(dex.method_ids_size):
            c, n, p = dex.method_id(mi)
            if rx.search(n):
                want[mi] = (c, n, p)
        if not want:
            continue
        want_set = set(want)
        for ci, _a, _s, cd, _sf in dex.class_defs():
            cls = dex.type_descr(ci)
            for mi, _acc, code in dex.methods_of(cd):
                ins = dex.insns(code)
                if not ins:
                    continue
                for kind, v in walk(ins, set(), want_set, set()):
                    if kind == "invoke":
                        count += 1
                        if count <= limit:
                            _c, n, p = dex.method_id(mi)
                            tc, tn, tp = want[v]
                            print("%-14s %-48s %s%s\n%-14s     -> %s.%s%s" % (
                                dex.name, pretty(cls), n, p,
                                "", pretty(tc), tn, tp))
    print("-- %d call sites" % count)


def cmd_strdump(apk: str, pattern: str, only, limit: int):
    rx = re.compile(pattern)
    count = 0
    for dex in load(apk, only):
        for i, s in enumerate(dex.strings):
            if rx.search(s):
                count += 1
                if count <= limit:
                    print("%-14s #%-8d %s" % (dex.name, i, s[:160]))
    print("-- %d strings" % count)


USAGE = __doc__


def main(argv: List[str]) -> int:
    if len(argv) < 3:
        print(USAGE)
        return 2
    cmd = argv[1]
    apk = argv[2]
    rest = argv[3:]
    limit = 200
    only = None
    positional = []
    i = 0
    while i < len(rest):
        a = rest[i]
        if a == "--limit":
            limit = int(rest[i + 1]); i += 2; continue
        if a == "--dex":
            only = only or set()
            v = rest[i + 1]
            only.add(v if v.endswith(".dex") else "classes%s.dex" % ("" if v == "1" else v))
            i += 2
            continue
        positional.append(a); i += 1

    table = {
        "refs": cmd_refs, "methods": cmd_methods, "classes": cmd_classes,
        "fields": cmd_fields, "calls": cmd_calls, "strdump": cmd_strdump,
        "members": cmd_classmembers,
    }
    fn = table.get(cmd)
    if fn is None:
        print(USAGE)
        return 2
    fn(apk, positional[0], only, limit)
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
    raise SystemExit(main(sys.argv))
