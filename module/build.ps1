<#
.SYNOPSIS
    Build + sign the FanQieNovelCrack LSPosed module APK.

.DESCRIPTION
    Deliberately gradle-free.

    This module has no Activities, no layouts and no runtime dependencies; the
    only things it needs from a build system are "compile a binary manifest",
    "compile some Java", "make a dex" and "sign". A four-line aapt2/d8/apksigner
    pipeline does that in a few seconds and keeps the whole build reproducible
    from a checked-in script plus a pinned SDK, with no gradle daemon, no
    network resolution and no dependency-lock rots.

    The stubs under stubs/ are compiled to a throw-away directory and are
    deliberately never fed to d8: the shipped dex must reference the framework's
    real de.robv.android.xposed classes, not our compile-time stand-ins.

.EXAMPLE
    pwsh -File module\build.ps1
    pwsh -File module\build.ps1 -SdkRoot D:\android-sdk -NoSign
#>
[CmdletBinding()]
param(
    [string] $SdkRoot = "$PSScriptRoot\..\work\android-sdk",
    [string] $BuildToolsVersion = '33.0.2',
    [int]    $ApiLevel = 33,
    [string] $OutDir = "$PSScriptRoot\..\dist",
    [string] $Keystore = "$PSScriptRoot\..\work\fanqiecrack.jks",
    [string] $StorePass = 'fanqiecrack',
    [string] $KeyAlias  = 'fanqiecrack',
    [string] $FrameworkDex = "$PSScriptRoot\..\work\lspd.dex",
    [string] $DeviceSerial = '127.0.0.1:16384',
    [switch] $NoSign
)

# Native tools (aapt2/d8/zipalign/apksigner/javac) write progress to stderr, and
# PowerShell 5.1 turns native stderr into an ErrorRecord -- with
# ErrorActionPreference='Stop' that aborts the build on a *successful* step.
# Every native invocation below therefore checks $LASTEXITCODE explicitly and
# routes failures through Fail, which is stricter than relying on the
# preference anyway.
$ErrorActionPreference = 'Continue'
Set-StrictMode -Version Latest

function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Fail($msg) { Write-Host "!!! $msg" -ForegroundColor Red; exit 1 }

# --------------------------------------------------------------------------
# 0. locate tools
# --------------------------------------------------------------------------
$bt      = Join-Path $SdkRoot "build-tools\$BuildToolsVersion"
$androidJar = Join-Path $SdkRoot "platforms\android-$ApiLevel\android.jar"
$aapt2   = Join-Path $bt 'aapt2.exe'
$d8      = Join-Path $bt 'd8.bat'
$zipalign= Join-Path $bt 'zipalign.exe'
$apksigner = Join-Path $bt 'apksigner.bat'

foreach ($t in @($aapt2, $d8, $zipalign, $androidJar)) {
    if (-not (Test-Path $t)) { Fail "missing build tool: $t`n(install with: sdkmanager ""platforms;android-$ApiLevel"" ""build-tools;$BuildToolsVersion"")" }
}
if (-not $NoSign -and -not (Test-Path $apksigner)) { Fail "missing apksigner: $apksigner" }

# --------------------------------------------------------------------------
# 1. pick a JDK, and settle on javac flags it actually understands
# --------------------------------------------------------------------------
# There is more than one JDK on a typical reverse-engineering box (here: 1.8
# next to 24). JAVA_HOME and the javapath shims disagree about which is which,
# and `--release` is a JDK 9+ flag, so resolve explicitly and probe rather than
# assume -- a wrong guess here produces a confusing "invalid flag" abort.
function Resolve-JdkHome {
    $candidates = New-Object System.Collections.Generic.List[string]
    $hint = (& java -XshowSettings:properties -version 2>&1 |
             Select-String 'java\.home\s*=' | Select-Object -First 1)
    if ($hint) { $candidates.Add($hint.ToString().Split('=', 2)[1].Trim()) }
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    foreach ($root in @('C:\Program Files\Java',
                        'C:\Program Files\Eclipse Adoptium',
                        'C:\Program Files\Microsoft',
                        'C:\Program Files\Android\Android Studio\jbr')) {
        if (Test-Path $root) {
            Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { $candidates.Add($_.FullName) }
        }
    }
    $usable = $candidates | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\javac.exe')) } |
              Select-Object -Unique
    if (-not $usable) { Fail 'no JDK with javac.exe found' }
    # Highest directory name wins -- good enough for jdk-8 vs jdk-24 ordering.
    return ($usable | Sort-Object -Descending | Select-Object -First 1)
}

$env:JAVA_HOME = Resolve-JdkHome
$javac   = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
$javacVersion = (& $javac -version 2>&1) -join ' '
Step "JAVA_HOME = $env:JAVA_HOME  ($javacVersion)"

if ($javacVersion -match 'javac\s+(?:1\.)?(\d+)' -and [int]$Matches[1] -ge 11) {
    $javacFlags = @('-nowarn', '--release', '11')
} else {
    # JDK 8: -source/-target 8 is exactly what Android wants anyway.
    $javacFlags = @('-nowarn', '-source', '1.8', '-target', '1.8', '-Xlint:-options')
}

# --------------------------------------------------------------------------
# 2. clean scratch
# --------------------------------------------------------------------------
$work = Join-Path $PSScriptRoot 'build'
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
foreach ($d in 'compiled', 'gen', 'stubs-classes', 'classes', 'dex') {
    New-Item -ItemType Directory -Force -Path (Join-Path $work $d) | Out-Null
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# --------------------------------------------------------------------------
# 2. resources + binary manifest
# --------------------------------------------------------------------------
Step 'aapt2 compile (resources)'
& $aapt2 compile --dir (Join-Path $PSScriptRoot 'res') -o (Join-Path $work 'compiled.zip')
if ($LASTEXITCODE -ne 0) { Fail 'aapt2 compile failed' }

$baseApk = Join-Path $work 'base.apk'
Step 'aapt2 link (manifest + assets)'
& $aapt2 link `
    -o $baseApk `
    -I $androidJar `
    --manifest (Join-Path $PSScriptRoot 'AndroidManifest.xml') `
    -A (Join-Path $PSScriptRoot 'assets') `
    --java (Join-Path $work 'gen') `
    --min-sdk-version 24 `
    --target-sdk-version $ApiLevel `
    --auto-add-overlay `
    --no-version-vectors `
    (Join-Path $work 'compiled.zip')
if ($LASTEXITCODE -ne 0) { Fail 'aapt2 link failed' }

# aapt2 silently accepts <meta-data> outside <application>, and the resulting
# APK installs and even runs -- but PackageManager then reports a null metaData
# Bundle and LSPosed never lists the module. See the script for the full story.
Step 'verify Xposed meta-data nesting'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'verify_manifest_meta.ps1') -Aapt2 $aapt2 -Apk $baseApk
if ($LASTEXITCODE -ne 0) { Fail 'compiled manifest is missing the Xposed meta-data inside <application>' }

# --------------------------------------------------------------------------
# 3. compile
# --------------------------------------------------------------------------
Step 'javac (framework stubs -- never dexed)'
# Every source list is wrapped in @() -- an empty pipeline result is $null, and
# splatting $null passes a literal empty-string argument to javac ("invalid
# flag: :"), which is a confusing way to learn a directory was empty.
$stubSources = @(Get-ChildItem (Join-Path $PSScriptRoot 'stubs') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$stubOut = Join-Path $work 'stubs-classes'
& $javac @javacFlags -d $stubOut @stubSources
if ($LASTEXITCODE -ne 0) { Fail 'stub compilation failed' }

Step 'javac (module)'
$srcSources = @(Get-ChildItem (Join-Path $PSScriptRoot 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$genSources = @(Get-ChildItem (Join-Path $work 'gen') -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
$classOut = Join-Path $work 'classes'
& $javac @javacFlags -cp $stubOut -d $classOut @srcSources @genSources
if ($LASTEXITCODE -ne 0) { Fail 'module compilation failed' }

$stubLeak = Get-ChildItem $classOut -Recurse -Filter *.class |
    Where-Object { $_.FullName -match '\\de\\robv\\' -or $_.FullName -match '\\android\\util\\' }
if ($stubLeak) { Fail "stub classes leaked into the module output: $($stubLeak[0].FullName)" }

# --------------------------------------------------------------------------
# 4. dex
# --------------------------------------------------------------------------
Step 'd8 (java bytecode -> dex)'
# Launched through the main class rather than build-tools' d8.bat: that wrapper
# passes -Djava.ext.dirs=<build-tools>/lib, an option JDK 9 removed and JDK 24
# rejects outright ("Could not create the Java Virtual Machine"). d8.jar is
# self-contained, so naming the entry point directly is both simpler and
# JDK-version independent. apksigner.bat does not use that flag and is fine.
$classFiles = @(Get-ChildItem $classOut -Recurse -Filter *.class | ForEach-Object { $_.FullName })
$dexOut = Join-Path $work 'dex'
$d8Jar = Join-Path $bt 'lib\d8.jar'
if (-not (Test-Path $d8Jar)) { $d8Jar = Join-Path $bt 'd8.jar' }
& java -Xmx1g -cp $d8Jar com.android.tools.r8.D8 --release --min-api 24 --lib $androidJar --output $dexOut @classFiles
if ($LASTEXITCODE -ne 0) { Fail 'd8 failed' }
$classesDex = Join-Path $dexOut 'classes.dex'
if (-not (Test-Path $classesDex)) { Fail "d8 produced no classes.dex in $dexOut" }
Step ("classes.dex = {0:n0} bytes" -f (Get-Item $classesDex).Length)

# --------------------------------------------------------------------------
# 4b. link-check every Xposed reference against the real framework dex
# --------------------------------------------------------------------------
# This is the step that stops a module which compiles, installs, loads, and then
# hooks nothing because a stub descriptor was one word off. See
# scripts/verify_xposed_api.py for the failure that motivated it.
Step 'verify Xposed API references'
if (-not (Test-Path $FrameworkDex)) {
    $device = $DeviceSerial
    if (-not $device) { $device = (adb devices | Select-String 'device$' | Select-Object -First 1) -replace '\s+device.*$', '' }
    if ($device) {
        Write-Host "    pulling framework dex from $device ..."
        & adb -s $device pull '/data/adb/modules/zygisk_lsposed/framework/lspd.dex' $FrameworkDex 2>&1 | Out-Null
    }
}
if (-not (Test-Path $FrameworkDex)) {
    Fail "framework dex not found at $FrameworkDex -- pull it with:`n    adb pull /data/adb/modules/zygisk_lsposed/framework/lspd.dex <path>`nor pass -FrameworkDex"
}
& python (Join-Path $PSScriptRoot '..\scripts\verify_xposed_api.py') $classesDex $FrameworkDex
if ($LASTEXITCODE -ne 0) { Fail 'module references Xposed members that do not exist in the framework' }

# --------------------------------------------------------------------------
# 5. pack classes.dex into the apk
# --------------------------------------------------------------------------
Step 'pack classes.dex'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($baseApk, 'Update')
try {
    $existing = $zip.Entries | Where-Object { $_.FullName -eq 'classes.dex' }
    if ($existing) { $zip.Entries.Remove($existing) | Out-Null }
    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, $classesDex, 'classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
} finally { $zip.Dispose() }

# --------------------------------------------------------------------------
# 6. align + sign
# --------------------------------------------------------------------------
$aligned = Join-Path $work 'aligned.apk'
Step 'zipalign'
& $zipalign -f -p 4 $baseApk $aligned
if ($LASTEXITCODE -ne 0) { Fail 'zipalign failed' }

$finalApk = Join-Path $OutDir 'FanQieNovelCrack-lsposed-v1.0.apk'
Copy-Item $aligned $finalApk -Force

if (-not $NoSign) {
    if (-not (Test-Path $Keystore)) {
        Step 'keytool (creating debug keystore)'
        & $keytool -genkeypair -v `
            -keystore $Keystore -alias $KeyAlias `
            -keyalg RSA -keysize 2048 -validity 10000 `
            -storepass $StorePass -keypass $StorePass `
            -dname 'CN=FanQieNovelCrack, OU=Lesson2, O=deathbook, L=NA, ST=NA, C=CN'
        if ($LASTEXITCODE -ne 0) { Fail 'keytool failed' }
    }
    Step 'apksigner'
    & $apksigner sign --ks $Keystore --ks-key-alias $KeyAlias `
        --ks-pass "pass:$StorePass" --key-pass "pass:$StorePass" `
        --v1-signing-enabled true --v2-signing-enabled true `
        $finalApk
    if ($LASTEXITCODE -ne 0) { Fail 'apksigner sign failed' }

    Step 'apksigner verify'
    & $apksigner verify --print-certs $finalApk
    if ($LASTEXITCODE -ne 0) { Fail 'apksigner verify failed' }
}

$item = Get-Item $finalApk
Step ("built {0}  ({1:n0} bytes, sha256 {2})" -f $item.Name, $item.Length,
      (Get-FileHash $finalApk -Algorithm SHA256).Hash)
