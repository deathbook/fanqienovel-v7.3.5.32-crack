<#
.SYNOPSIS
    Deploy the LSPosed module to an emulator/device that already has LSPosed.
.DESCRIPTION
    Installs the APK, points LSPosed's database at the freshly installed copy,
    reboots, launches the target, and dumps the module's log lines.

    Why the database step exists at all: LSPosed stores the *resolved* apk path
    of every enabled module. Reinstalling a package gets a new
    /data/app/<random>/ directory, so a rebuilt module keeps loading the old,
    now-deleted APK until the row is updated. Repointing it by hand also means
    the module does not have to be toggled through the manager UI, which is what
    the manager's own switch does internally.

    Requires root on the device (MuMu: adb root works; Magisk su is not needed).
#>
[CmdletBinding()]
param(
    [string] $Serial = '127.0.0.1:16384',
    [string] $Apk = "$PSScriptRoot\..\dist\FanQieNovelCrack-lsposed-v1.0.apk",
    [string] $Target = 'com.dragon.read',
    [string] $Module = 'com.deathbook.fanqie.crack',
    [string] $MuMuManager = 'C:\Program Files\NetEase\MuMu\nx_main\MuMuManager.exe',
    [int]    $VmIndex = 0,
    [switch] $SkipReboot,
    [switch] $SkipLaunch
)

$ErrorActionPreference = 'Continue'
Set-StrictMode -Version Latest

function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Fail($m) { Write-Host "!!! $m" -ForegroundColor Red; exit 1 }

if (-not (Test-Path $Apk)) { Fail "apk not found: $Apk" }
$adbExe = 'adb'

function Invoke-Adb([string[]] $AdbArgs) { & $adbExe '-s' $Serial @AdbArgs }

# --------------------------------------------------------------------------
Step "install $([IO.Path]::GetFileName($Apk))"
$out = Invoke-Adb @('install', '-r', $Apk) 2>&1 | Out-String
Write-Host ($out.Trim() -split "`n" | Select-Object -Last 1)
if ($out -notmatch 'Success') { Fail "install failed:`n$out" }

Step 'resolve installed apk path'
# `pm path` rather than an `ls` glob: the /data/app/<random-tag> directory name
# changes on every reinstall, and the device shell does not always expand the
# glob, which turns a missing match into a literal path and a confusing error.
$raw = (Invoke-Adb @('shell', "pm path $Module") | Out-String).Trim()
$path = ($raw -split "`n" | Where-Object { $_ -match '^package:' } | Select-Object -First 1) -replace '^package:', ''
if (-not $path) { Fail "could not resolve installed apk path (pm path said: $raw)" }
Write-Host "    $path"

Step 'ensure adb root'
Invoke-Adb @('root') | Out-Null
Start-Sleep -Seconds 4
$who = (Invoke-Adb @('shell', 'id') | Out-String).Trim()
if ($who -notmatch 'uid=0') { Fail "adb root unavailable -- cannot write the LSPosed database ($who)" }

# --------------------------------------------------------------------------
Step 'point LSPosed at the module'
$sql = @"
UPDATE modules SET apk_path = '$path', enabled = 1 WHERE module_pkg_name = '$Module';
INSERT OR IGNORE INTO scope (mid, app_pkg_name, user_id)
  SELECT mid, '$Target', 0 FROM modules WHERE module_pkg_name = '$Module';
SELECT 'modules', mid, module_pkg_name, enabled FROM modules;
SELECT 'scope', mid, app_pkg_name, user_id FROM scope;
"@
$tmp = Join-Path $env:TEMP 'fanqie_repoint.sql'
# LSPosed's db is only readable as root and the SQL contains characters the
# device shell would mangle, so always go through a file.
# no BOM: sqlite3 would otherwise see it glued to the first keyword
[IO.File]::WriteAllText($tmp, $sql, (New-Object Text.UTF8Encoding($false)))
Invoke-Adb @('push', $tmp, '/data/local/tmp/repoint.sql') | Out-Null
$applied = Invoke-Adb @('shell', 'sqlite3 /data/adb/lspd/config/modules_config.db < /data/local/tmp/repoint.sql') 2>&1 | Out-String
Write-Host ($applied.Trim())
if ($applied -notmatch "modules\|") { Fail "LSPosed db update failed:`n$applied" }
if ($applied -match "error") { Fail "LSPosed db update reported an error:`n$applied" }

# --------------------------------------------------------------------------
if (-not $SkipReboot) {
    Step 'reboot (LSPosed only loads modules at zygote start)'
    & $MuMuManager control -v $VmIndex shutdown | Out-Null
    Start-Sleep -Seconds 12
    & $MuMuManager control -v $VmIndex launch | Out-Null
    for ($i = 0; $i -lt 45; $i++) {
        Start-Sleep -Seconds 8
        $j = (& $MuMuManager info -v $VmIndex | Out-String) | ConvertFrom-Json
        if ($j.is_android_started) { break }
    }
    Start-Sleep -Seconds 30
    & $adbExe disconnect $Serial 2>&1 | Out-Null
    & $adbExe connect $Serial 2>&1 | Out-Null
    Start-Sleep -Seconds 3
    $boot = (Invoke-Adb @('shell', 'getprop sys.boot_completed') | Out-String).Trim()
    Write-Host "    boot_completed=$boot"
}

if (-not $SkipLaunch) {
    Step "launch $Target"
    Invoke-Adb @('logcat', '-c') | Out-Null
    Invoke-Adb @('shell', "am force-stop $Target") | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Adb @('shell', "monkey -p $Target -c android.intent.category.LAUNCHER 1") | Out-Null
    Start-Sleep -Seconds 40
}

Step 'module log'
$tag = (Invoke-Adb @('logcat', '-d', '-s', 'FanQieCrack:*') | Out-String)
Write-Host $tag
$hits = ($tag -split "`n" | Where-Object { $_ -match 'PROBE|SELFTEST|attached' }).Count
Step "done -- $hits module log line(s)"




