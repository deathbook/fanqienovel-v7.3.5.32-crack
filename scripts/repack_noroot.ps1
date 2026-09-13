<#
.SYNOPSIS
    Assemble + sign the no-root FanQieNovelCrack APK.
.DESCRIPTION
    Produces a standalone APK that needs no root, no Magisk and no LSPosed:
    a Frida gadget rides along inside it and the crack script is a Frida
    script instead of an Xposed module.

    What goes into the APK
    ----------------------
      classes21.dex                       patched: MuteApplicationStub
                                          .attachBaseContext now calls
                                          Bootstrap.init(this) and then
                                          System.loadLibrary("fqgadget")
      classes22.dex                       NEW: the unpacker (Bootstrap)
      lib/armeabi-v7a/libfqgadget.so      the Frida gadget (32-bit ARM,
                                          because this sample ships only
                                          armeabi-v7a libraries)
      lib/armeabi-v7a/libfqgadget.config.so
                                          gadget config; it names the script by
                                          absolute path, so it is static
      assets/fanqie_crack.js              the crack script, unpacked to the
                                          files dir at first launch

    Only classes21.dex is rebuilt, and only the one method that matters is
    touched. The full apktool rebuild of this sample would re-assemble 280k
    smali files across 21 dexes and all 22k resources for the sake of a
    five-line edit; re-assembling the single 18.5k-class dex takes ~15 seconds
    and provably preserves the other 18,528 classes and all 54,132 methods
    (see the count check in docs/06).

    Note on assets: APK assets cannot be mmap'd or opened by the gadget, and a
    gadget config has to name its script by path. The script therefore lands in
    <app data>/files at first launch -- an absolute path that is knowable at
    build time, which makes the config static and the APK relocatable.

.PARAMETER OriginalApk
    The unmodified sample. Only classes21.dex is taken from it.

.PARAMETER PatchedDex
    classes21.dex after the smali round-trip. See docs/06_noroot_build.md for
    the exact extraction/patch/re-assembly steps.
#>
[CmdletBinding()]
param(
    [string] $OriginalApk  = "$PSScriptRoot\..\work\original.apk",
    [string] $PatchedDex   = "$PSScriptRoot\..\work\classes21_patched.dex",
    [string] $PatchedDexName = 'classes21.dex',
    [string] $BootstrapSrc = "$PSScriptRoot\..\module\noroot",
    [string] $FridaScript  = "$PSScriptRoot\..\frida\fanqie_crack.js",
    [string] $GadgetSo     = "$PSScriptRoot\..\work\downloads\frida-gadget-17.12.0-android-arm.so",
    [string] $TargetPackage = 'com.dragon.read',
    [string] $SdkRoot      = "$PSScriptRoot\..\work\android-sdk",
    [string] $BuildToolsVersion = '33.0.2',
    [int]    $ApiLevel     = 33,
    [string] $OutDir       = "$PSScriptRoot\..\dist",
    [string] $Keystore     = "$PSScriptRoot\..\work\fanqiecrack.jks",
    [string] $StorePass    = 'fanqiecrack',
    [string] $KeyAlias     = 'fanqiecrack',
    [string] $PayloadVersion = '1',
    [switch] $SkipSign
)

$ErrorActionPreference = 'Continue'
Set-StrictMode -Version Latest
function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Fail($m) { Write-Host "!!! $m" -ForegroundColor Red; exit 1 }

foreach ($p in @($OriginalApk, $PatchedDex, $FridaScript, $GadgetSo)) {
    if (-not (Test-Path $p)) { Fail "missing input: $p" }
}

# --------------------------------------------------------------------------
# 0. rebuild the patched dex if the smali has moved on
# --------------------------------------------------------------------------
# Hitting exactly this cost a full build/install/test cycle that produced a
# byte-identical APK and a confusing "the fix didn't work" result: the smali was
# edited, the dex was not re-assembled, and the packaging step happily shipped
# the previous dex. Compare timestamps and re-assemble rather than trusting the
# operator to remember.
$SmaliRoot = "$PSScriptRoot\..\work\dex21build"
$SmaliTarget = Join-Path $SmaliRoot 'smali\com\dragon\read\base\mute\MuteApplicationStub.smali'
if (Test-Path $SmaliTarget) {
    $dexTime = (Get-Item $PatchedDex).LastWriteTimeUtc
    $newestSmali = Get-ChildItem $SmaliRoot -Recurse -Filter *.smali |
                   Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($newestSmali.LastWriteTimeUtc -gt $dexTime) {
        Step "smali is newer than $([IO.Path]::GetFileName($PatchedDex)) -- re-assembling"
        $apktool = 'C:\Users\O5-3\Documents\VibeCoding\Crack\REVERSE_KIT\tools_core\apktool_3.0.3.jar'
        if (-not (Test-Path $apktool)) { Fail "apktool not found at $apktool (needed to re-assemble the patched dex)" }
        $container = Join-Path $PSScriptRoot '..\work\dex21container.apk'
        & java -Xmx4g -jar $apktool b $SmaliRoot -o $container 2>&1 |
            Select-String -Pattern 'Built|Exception|error' | ForEach-Object { Write-Host "    $_" }
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $container)) { Fail 'apktool re-assembly failed' }
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $cz = [System.IO.Compression.ZipFile]::OpenRead($container)
        $ce = $cz.Entries | Where-Object { $_.FullName -eq 'classes.dex' }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($ce, $PatchedDex, $true)
        $cz.Dispose()
        Step ("re-assembled $([IO.Path]::GetFileName($PatchedDex)) = {0:n0} bytes" -f (Get-Item $PatchedDex).Length)
    } else {
        Step 'patched dex is up to date with the smali'
    }
}

$bt = Join-Path $SdkRoot "build-tools\$BuildToolsVersion"
$zipalign  = Join-Path $bt 'zipalign.exe'
$apksigner = Join-Path $bt 'apksigner.bat'
$androidJar = Join-Path $SdkRoot "platforms\android-$ApiLevel\android.jar"
$d8Jar = Join-Path $bt 'lib\d8.jar'
foreach ($t in @($zipalign, $androidJar, $d8Jar)) {
    if (-not (Test-Path $t)) { Fail "missing build tool: $t" }
}

$jdk = Get-ChildItem 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue |
       Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } |
       Sort-Object Name -Descending | Select-Object -First 1
if (-not $jdk) { Fail 'no JDK found' }
$javac   = Join-Path $jdk.FullName 'bin\javac.exe'
$keytool = Join-Path $jdk.FullName 'bin\keytool.exe'
$javacVersion = (& $javac -version 2>&1) -join ' '
if ($javacVersion -match 'javac\s+(?:1\.)?(\d+)' -and [int]$Matches[1] -ge 11) {
    $javacFlags = @('-nowarn', '--release', '11')
} else {
    $javacFlags = @('-nowarn', '-source', '1.8', '-target', '1.8', '-Xlint:-options')
}

$work = Join-Path $PSScriptRoot '..\work\noroot'
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Force -Path $work, $OutDir | Out-Null

# --------------------------------------------------------------------------
# 1. Bootstrap -> classes22.dex
# --------------------------------------------------------------------------
Step 'javac (Bootstrap)'
$srcs = @(Get-ChildItem $BootstrapSrc -Recurse -Filter *.java | ForEach-Object { $_.FullName })
if ($srcs.Count -eq 0) { Fail "no Bootstrap sources under $BootstrapSrc" }
$classesDir = Join-Path $work 'classes'
& $javac @javacFlags -cp $androidJar -d $classesDir @srcs
if ($LASTEXITCODE -ne 0) { Fail 'Bootstrap compilation failed' }

Step 'd8 (Bootstrap -> classes.dex)'
# Launched via the main class, not build-tools' d8.bat: that wrapper passes
# -Djava.ext.dirs, which JDK 9 removed and JDK 24 rejects outright.
$classFiles = @(Get-ChildItem $classesDir -Recurse -Filter *.class | ForEach-Object { $_.FullName })
$dexDir = Join-Path $work 'dex'
New-Item -ItemType Directory -Force -Path $dexDir | Out-Null

# Start-Process rather than the call operator here on purpose. d8 needs each
# .class file named individually, and PowerShell 5.1 mangles a *single-element*
# array splat whose one element is a Windows path: the argument arrives at the
# JVM as "C" plus "\Users\...", and d8 reports the memorable
# "Error in program input 'C'". The module build passes 15 files and is fine;
# this one passes Bootstrap.class alone, so it needs the quoting Start-Process
# does for us. (d8 in build-tools 33 also rejects directories as program input,
# so collapsing to one argument is not an option.)
$d8Args = @('-Xmx1g', '-cp', $d8Jar, 'com.android.tools.r8.D8',
            '--release', '--min-api', '24',
            '--lib', $androidJar, '--output', $dexDir) + $classFiles
$proc = Start-Process -FilePath 'java' -ArgumentList $d8Args -Wait -NoNewWindow -PassThru
if ($proc.ExitCode -ne 0) { Fail "d8 failed for Bootstrap (exit $($proc.ExitCode))" }
$bootstrapDex = Join-Path $dexDir 'classes.dex'
if (-not (Test-Path $bootstrapDex)) { Fail 'no classes.dex produced for Bootstrap' }
Step ("classes22.dex = {0:n0} bytes" -f (Get-Item $bootstrapDex).Length)

# --------------------------------------------------------------------------
# 2. gadget config
# --------------------------------------------------------------------------
# The gadget looks for <dir>/<stem>.config.so next to itself, so the library is
# named libfqgadget.so and the config libfqgadget.config.so. Relative script
# paths would resolve against the process cwd ("/"), so the script is named by
# the one absolute path that is stable across installs.
$scriptAbs = "/data/data/$TargetPackage/files/fanqie_crack.js"
$config = @{ interaction = @{ type = 'script'; path = $scriptAbs; on_change = 'reload' };
             log = @{ level = 'info' } } | ConvertTo-Json -Depth 5 -Compress
Step "gadget config -> $scriptAbs"
$configPath = Join-Path $work 'libfqgadget.config.so'
[IO.File]::WriteAllText($configPath, $config, (New-Object Text.UTF8Encoding($false)))

# --------------------------------------------------------------------------
# 3. assemble the APK
# --------------------------------------------------------------------------
Step 'assemble APK (replace classes21, add classes22 + native libs + asset)'
$unsigned = Join-Path $work 'unsigned.apk'
Copy-Item $OriginalApk $unsigned -Force

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, 'Update')
try {
    $existing = $zip.Entries | Where-Object { $_.FullName -eq 'classes.dex' -and $false }
    foreach ($name in @($PatchedDexName, 'classes22.dex',
                        'lib/armeabi-v7a/libfqgadget.so',
                        'lib/armeabi-v7a/libfqgadget.config.so',
                        'assets/fanqie_crack.js')) {
        $old = $zip.Entries | Where-Object { $_.FullName -eq $name }
        if ($old) { $old.Delete() }
    }

    $add = @(
        @{ file = $PatchedDex;   entry = $PatchedDexName },
        @{ file = $bootstrapDex; entry = 'classes22.dex' },
        @{ file = $GadgetSo;     entry = 'lib/armeabi-v7a/libfqgadget.so' },
        @{ file = $configPath;   entry = 'lib/armeabi-v7a/libfqgadget.config.so' },
        @{ file = $FridaScript;  entry = 'assets/fanqie_crack.js' }
    )
    foreach ($a in $add) {
        # .so files must be STORED (page-alignable, mmap-friendly); the rest
        # compresses fine.
        $level = if ($a.entry -like '*.so') {
            [System.IO.Compression.CompressionLevel]::NoCompression
        } else {
            [System.IO.Compression.CompressionLevel]::Optimal
        }
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $a.file, $a.entry, $level)
        $sz = (Get-Item $a.file).Length
        Step ("  + {0,-42} {1,12:n0} bytes" -f $a.entry, $sz)
    }

    # Drop the old signature: its .SF digests describe the APK as it was before
    # we edited it, so leaving it in place would make the package look tampered.
    foreach ($e in @($zip.Entries | Where-Object {
                $_.FullName -like 'META-INF/*.RSA' -or
                $_.FullName -like 'META-INF/*.SF' -or
                $_.FullName -like 'META-INF/*.DSA' -or
                $_.FullName -eq 'META-INF/MANIFEST.MF' })) {
        $e.Delete()
    }
} finally { $zip.Dispose() }

# --------------------------------------------------------------------------
# 4. align + sign
# --------------------------------------------------------------------------
$aligned = Join-Path $work 'aligned.apk'
Step 'zipalign -p 4 (page-align the .so entries)'
& $zipalign -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { Fail 'zipalign failed' }

$alignOut = & $zipalign -c -p 4 -v $aligned 2>&1
$alignOut | Select-Object -Last 2

$finalApk = Join-Path $OutDir 'FanQieNovelCrack-noroot-v1.0.apk'
Copy-Item $aligned $finalApk -Force

if (-not $SkipSign) {
    if (-not (Test-Path $Keystore)) {
        Step 'keytool (creating debug keystore)'
        & $keytool -genkeypair -v -keystore $Keystore -alias $KeyAlias `
            -keyalg RSA -keysize 2048 -validity 10000 `
            -storepass $StorePass -keypass $StorePass `
            -dname 'CN=FanQieNovelCrack, OU=Lesson2, O=deathbook, L=NA, ST=NA, C=CN' | Out-Null
    }
    Step 'apksigner'
    & $apksigner sign --ks $Keystore --ks-key-alias $KeyAlias `
        --ks-pass "pass:$StorePass" --key-pass "pass:$StorePass" `
        --v1-signing-enabled true --v2-signing-enabled true $finalApk
    if ($LASTEXITCODE -ne 0) { Fail 'apksigner sign failed' }
    $verifyOut = & $apksigner verify --print-certs $finalApk 2>&1
    $verifyOut | Select-Object -First 3
    if ($LASTEXITCODE -ne 0) { Fail 'apksigner verify failed' }
}

$item = Get-Item $finalApk
Step ("built {0}  ({1:n0} bytes, sha256 {2})" -f $item.Name, $item.Length,
      (Get-FileHash $finalApk -Algorithm SHA256).Hash)
Write-Host @"

Reminder: this APK is signed with a different key than the official app, so the
official package must be uninstalled first:

    adb uninstall $TargetPackage
    adb install "$finalApk"
    adb logcat -s FanQieCrack:*
"@
