#!/usr/bin/env python3
"""Repoint a module's row at the APK path it actually has now, and set its scope.

Why this exists
---------------
LSPosed -- and Vector, which reuses /data/adb/lspd and the very same schema --
stores a module APK's ABSOLUTE path:

    modules(mid INTEGER PRIMARY KEY AUTOINCREMENT,
            module_pkg_name TEXT NOT NULL UNIQUE,
            apk_path TEXT NOT NULL,
            enabled BOOLEAN DEFAULT 0 CHECK (enabled IN (0,1)))
    scope(mid INTEGER, app_pkg_name TEXT NOT NULL, user_id INTEGER NOT NULL,
          PRIMARY KEY (mid, app_pkg_name, user_id))

Every reinstall relocates the APK to a fresh
    /data/app/~~<random>/<pkg>-<random>/base.apk
so the stored path quietly starts pointing at nothing. The symptom is the worst
kind: the module stays enabled in the UI, the manager still lists it, and the
framework loads no hooks at all -- so it reads as "the module is broken" when
nothing is wrong with the APK.

This is the same edit the manager performs when you toggle a module on, done
directly so that it also works when the manager's own list is the thing that
is broken.

Usage
-----
    python repoint_module.py <db> --show
    python repoint_module.py <db> <module-pkg> <apk-path> [scope-pkg ...]

Example
-------
    adb pull /data/adb/lspd/config/modules_config.db .
    APK=$(adb shell pm path com.deathbook.fanqie.crack | sed 's/package://')
    python repoint_module.py modules_config.db com.deathbook.fanqie.crack \\
        "$APK" com.dragon.read
    adb push modules_config.db /data/adb/lspd/config/modules_config.db
    adb shell chown root:root /data/adb/lspd/config/modules_config.db
    adb shell chmod 600 /data/adb/lspd/config/modules_config.db
    # then reboot: the daemon reads this database once, at boot
"""

import sqlite3
import sys


def show(cur):
    print("modules:")
    for row in cur.execute(
        "select mid, module_pkg_name, enabled, apk_path from modules order by mid"
    ):
        print("    mid={} pkg={} enabled={} apk={}".format(*row))
    print("scope:")
    for row in cur.execute("select mid, app_pkg_name, user_id from scope order by mid"):
        print("    mid={} app={} user={}".format(*row))


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2

    db, rest = argv[1], argv[2:]
    conn = sqlite3.connect(db)
    cur = conn.cursor()

    if not rest or rest[0] == "--show":
        show(cur)
        return 0

    module_pkg, apk_path = rest[0], rest[1]
    scopes = rest[2:]

    cur.execute(
        "update modules set apk_path=?, enabled=1 where module_pkg_name=?",
        (apk_path, module_pkg),
    )
    if cur.rowcount == 0:
        cur.execute(
            "insert into modules(module_pkg_name,apk_path,enabled) values(?,?,1)",
            (module_pkg, apk_path),
        )

    cur.execute("select mid from modules where module_pkg_name=?", (module_pkg,))
    mid = cur.fetchone()[0]

    if scopes:
        # Replaced rather than appended: the caller named the complete set, and
        # a stale row pointing at an app the module no longer targets keeps the
        # daemon hooking it after the user has turned it off.
        cur.execute("delete from scope where mid=?", (mid,))
        for scope in scopes:
            cur.execute(
                "insert or ignore into scope(mid,app_pkg_name,user_id) values(?,?,0)",
                (mid, scope),
            )

    conn.commit()
    print("mid={} -> {} @ {}".format(mid, module_pkg, apk_path))
    show(cur)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
