#!/usr/bin/env python3
"""Extract every string from every dex inside an APK (or a folder of dexs).

Usage:
    python dex_strings.py <apk-or-dir> <out.txt> [--dex]

Writes one string per line to <out.txt> (deduplicated, original order preserved
per-dex).  Uses a real dex parser, not a regex, so MUTF-8 and lengths are exact.
"""
from __future__ import annotations

import io
import os
import struct
import sys
import zipfile


def read_uleb128(buf: bytes, off: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, off
        shift += 7


def mutf8_decode(raw: bytes) -> str:
    """Decode Java MUTF-8 (CESU-8-ish) to a Python str.

    MUTF-8 differs from UTF-8 in that U+0000 is 0xC0 0x80 and characters outside
    the BMP are encoded as two 3-byte surrogate sequences.  Decoding with
    ``surrogatepass`` yields lone surrogates which we then recombine.
    """
    try:
        s = raw.decode("utf-8", "surrogatepass")
    except UnicodeDecodeError:
        return raw.decode("utf-8", "replace")
    if any("\ud800" <= ch <= "\udfff" for ch in s):
        try:
            s = s.encode("utf-16", "surrogatepass").decode("utf-16")
        except UnicodeError:
            pass
    return s


def dex_strings(data: bytes):
    # header: magic[8] checksum u4 signature[20] file_size u4 header_size u4
    #         endian_tag u4 link_size u4 link_off u4 map_off u4
    #         string_ids_size u4 string_ids_off u4 type_ids_size u4 ...
    (magic, _checksum, _sig, _file_size, _header_size, endian_tag,
     _link_size, _link_off, _map_off, string_ids_size,
     string_ids_off, _type_ids_size, _type_ids_off) = struct.unpack_from(
        "<8sI20sIIIIIIIIII", data, 0)

    if magic[:3] not in (b"dex", b"cde"):
        raise ValueError("not a dex: %r" % magic[:8])
    if endian_tag != 0x12345678:
        raise ValueError("unsupported endian_tag 0x%08x" % endian_tag)

    out = []
    for i in range(string_ids_size):
        off = struct.unpack_from("<I", data, string_ids_off + i * 4)[0]
        # ULEB128 utf16 length, then MUTF-8 bytes terminated by 0x00
        _utf16_len, p = read_uleb128(data, off)
        end = data.index(b"\x00", p)
        raw = data[p:end]
        out.append(mutf8_decode(raw))
    return out


def iter_dex(apk_or_dir: str):
    if os.path.isdir(apk_or_dir):
        for name in sorted(os.listdir(apk_or_dir)):
            if name.endswith(".dex"):
                p = os.path.join(apk_or_dir, name)
                with open(p, "rb") as f:
                    yield name, f.read()
    else:
        with zipfile.ZipFile(apk_or_dir) as z:
            names = [n for n in z.namelist() if n.endswith(".dex")]
            names.sort(key=lambda n: (len(n), n))
            for n in names:
                yield n, z.read(n)


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    src, dst = sys.argv[1], sys.argv[2]
    seen = set()
    total = 0
    with io.open(dst, "w", encoding="utf-8", newline="\n") as fh:
        for name, blob in iter_dex(src):
            try:
                strs = dex_strings(blob)
            except Exception as exc:  # noqa: BLE001
                print("!! %s: %s" % (name, exc), file=sys.stderr)
                continue
            # tag each string with the dex it came from -> needed later to map
            # a string back to the smali directory
            for s in strs:
                total += 1
                key = (name, s)
                if key in seen:
                    continue
                seen.add(key)
                if "\t" in s or "\n" in s:
                    continue  # keep the TSV parseable
                fh.write("%s\t%s\n" % (name, s))
    print("dex files parsed, %d string slots, %d unique (dex,string) pairs" % (total, len(seen)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
