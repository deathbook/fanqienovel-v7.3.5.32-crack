-- Re-point LSPosed at the freshly installed module APK.
--
-- Reinstalling a package gives it a new /data/app/<random-tag> directory, and
-- LSPosed stores the resolved apk_path in its database, so the row has to be
-- updated whenever the module is rebuilt and reinstalled.
UPDATE modules
   SET apk_path = '/data/app/~~I4RuL3An9ELSdSuWpe-p5w==/com.deathbook.fanqie.crack-tAb6pgrycaB0inWq0I8b1Q==/base.apk',
       enabled  = 1
 WHERE module_pkg_name = 'com.deathbook.fanqie.crack';

INSERT OR IGNORE INTO scope (mid, app_pkg_name, user_id)
SELECT mid, 'com.dragon.read', 0 FROM modules
 WHERE module_pkg_name = 'com.deathbook.fanqie.crack';

SELECT 'modules:', mid, module_pkg_name, enabled, apk_path FROM modules;
SELECT 'scope  :', mid, app_pkg_name, user_id FROM scope;
