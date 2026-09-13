-- Enable the FanQieNovelCrack module in LSPosed's database.
--
-- This is exactly what tapping the toggle in the LSPosed manager does; it is
-- done here because the manager's module list came up empty even though the
-- APK's xposedmodule meta-data and assets/xposed_init are both present and
-- correct (verified against the *installed* apk, not the build output).
--
--   modules(mid, module_pkg_name, apk_path, enabled)
--   scope  (mid, app_pkg_name, user_id)
--
-- mid is assigned by hand because sqlite_sequence currently only knows about
-- lspd (mid 1).

INSERT INTO modules (mid, module_pkg_name, apk_path, enabled)
VALUES (2,
        'com.deathbook.fanqie.crack',
        '/data/app/~~sifgkFi3N3rdrePx-iXq8g==/com.deathbook.fanqie.crack-Pfc7pZoMTz8V7A-GqlBbgg==/base.apk',
        1);

INSERT INTO scope (mid, app_pkg_name, user_id)
VALUES (2, 'com.dragon.read', 0);

UPDATE sqlite_sequence SET seq = 2 WHERE name = 'modules';

SELECT 'modules:', mid, module_pkg_name, enabled FROM modules;
SELECT 'scope  :', mid, app_pkg_name, user_id FROM scope;
