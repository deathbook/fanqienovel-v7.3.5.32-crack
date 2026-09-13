<#
.SYNOPSIS
    Build the no-root FanQieNovelCrack APK -- the pure-smali-patch variant.
.DESCRIPTION
    No Frida, no LSPatch, no Xposed, no injected runtime. Four small smali edits
    plus one extra dex, and the result installs on any Android version.

    Why this and not the alternatives
    ---------------------------------
    * LSPatch v0.6 cannot run on Android 16 at all: `Failed to init lsplant`,
      then `NoSuchFieldError: ActivityThread$AppBindData#compatInfo` (the field
      was removed in 16). Measured, not assumed.
    * A Frida gadget loads, unloads and segfaults on the same device
      (SEGV_MAPERR at fault addr 0x24) for reasons that are not diagnosable
      without the matching Frida sources.
    * This variant has no runtime component to be incompatible with.

    What is patched
    ---------------
      classes2.dex   NsAdDependImpl.isReaderAdFree()Z / readerIsAdFree() /
                     audioIsAdFree(String)  -- the ad predicates that are
                     computed from the current book rather than from the
                     privilege map
      classes3.dex   PrivilegeManager.getInstance(): the single `new-instance`
                     of the singleton is retargeted at CrackPrivilegeManager
      classes13.dex  NsAdImpl.inspireAdDisable() -> true, the app's own switch
                     for "no rewarded video during auto-read"
      classes22.dex  NEW: CrackPrivilegeManager + its compile-time stubs are
                     NOT included (see the leak check below)

    Retargeting one `new-instance` is what makes this small: the singleton is
    also stored in the static field `k`, so every entry point -- the factory and
    the field -- hands out the subclass.

.PARAMETER OriginalApk
    The unmodified Lesson-2 sample.
#>
[CmdletBinding()]
param(
    [string] $OriginalApk = "$PSScriptRoot\..\work\original.apk",
    [string] $SmaliRoot   = "$PSScriptRoot\..\work\apktool",
    [string] $NorootSrc   = "$PSScriptRoot\..\module\noroot",
    [string] $SdkRoot     = "$PSScriptRoot\..\work\android-sdk",
    [string] $BuildToolsVersion = '33.0.2',
    [int]    $ApiLevel    = 33,
    [string] $OutDir      = "$PSScriptRoot\..\dist",
    [string] $Keystore    = "$PSScriptRoot\..\work\fanqiecrack.jks",
    [string] $StorePass   = 'fanqiecrack',
    [string] $KeyAlias    = 'fanqiecrack',
    [string] $Apktool     = 'C:\Users\O5-3\Documents\VibeCoding\Crack\REVERSE_KIT\tools_core\apktool_3.0.3.jar',
    [switch] $SkipSign
)

$ErrorActionPreference = 'Continue'
Set-StrictMode -Version Latest
function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Fail($m) { Write-Host "!!! $m" -ForegroundColor Red; exit 1 }

# --------------------------------------------------------------------------
# smali surgery helpers
# --------------------------------------------------------------------------

<#
Replace the entire body of one method, matched by its `.method` line.
Everything between that line and the matching `.end method` is discarded, which
is the point: the replacement is a constant return, so nothing of the original
computation is wanted.
#>
function Set-SmaliMethodBody {
    param(
        [string]   $File,
        [string]   $MethodHeadRegex,
        [string[]] $Body
    )
    $lines = [IO.File]::ReadAllLines($File)
    $out = New-Object System.Collections.Generic.List[string]
    $i = 0; $found = $false
    while ($i -lt $lines.Count) {
        if (-not $found -and $lines[$i] -match $MethodHeadRegex) {
            $found = $true
            $out.Add($lines[$i])
            foreach ($b in $Body) { $out.Add($b) }
            $out.Add('.end method')
            while ($i -lt $lines.Count -and $lines[$i] -notmatch '^\.end method') { $i++ }
            $i++
            continue
        }
        $out.Add($lines[$i]); $i++
    }
    if (-not $found) { Fail "method not found in $File : $MethodHeadRegex" }
    [IO.File]::WriteAllLines($File, $out, (New-Object Text.UTF8Encoding($false)))
}

<#
Rebuild one smali directory into a .dex using apktool.

Each affected dex gets its own throwaway project containing only that smali
tree. A full `apktool b` of the whole sample would re-assemble 280k smali files
across 21 dexes plus 22k resources to apply four edits; one dex at a time takes
~15 s and is exactly the granularity the edits need.
#>
function Rebuild-Dex {
    param([string] $SmaliDir, [string] $OutDex)
    $proj = Join-Path $work 'proj'
    if (Test-Path $proj) { Remove-Item $proj -Recurse -Force }
    New-Item -ItemType Directory -Force -Path "$proj\res\values" | Out-Null
    Move-Item $SmaliDir "$proj\smali"
    @'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.deathbook.dexcontainer" android:versionCode="1" android:versionName="1.0">
    <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="35" />
    <application android:hasCode="true" />
</manifest>
'@ | Set-Content "$proj\AndroidManifest.xml" -Encoding UTF8
    @'
<?xml version="1.0" encoding="utf-8"?>
<resources><string name="unused">x</string></resources>
'@ | Set-Content "$proj\res\values\strings.xml" -Encoding UTF8
    @'
version: 3.0.3
apkFileName: container.apk
usesFramework:
  ids:
  - 1
sdkInfo:
  minSdkVersion: 21
  targetSdkVersion: 35
'@ | Set-Content "$proj\apktool.yml" -Encoding UTF8

    $container = Join-Path $work 'container.apk'
    & java -Xmx4g -jar $Apktool b $proj -o $container 2>&1 |
        Select-String -Pattern 'Built|Exception|error' | ForEach-Object { Write-Host "    $_" }
    if (-not (Test-Path $container)) { Fail "apktool failed to build $SmaliDir" }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $z = [System.IO.Compression.ZipFile]::OpenRead($container)
    $e = $z.Entries | Where-Object { $_.FullName -eq 'classes.dex' }
    if (-not $e) { $z.Dispose(); Fail "no classes.dex produced for $SmaliDir" }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($e, $OutDex, $true)
    $z.Dispose()
    Remove-Item $container -Force
    Step ("  {0,-26} -> {1,-18} {2,12:n0} bytes" -f (Split-Path $SmaliDir -Leaf),
          (Split-Path $OutDex -Leaf), (Get-Item $OutDex).Length)
}

# --------------------------------------------------------------------------
foreach ($p in @($OriginalApk, $Apktool)) {
    if (-not (Test-Path $p)) { Fail "missing input: $p" }
}
if (-not (Test-Path $SmaliRoot)) { Fail "smali tree not found at $SmaliRoot -- decode the sample with apktool first" }

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

$work = Join-Path $PSScriptRoot '..\work\norootpatch'
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Force -Path $work, $OutDir | Out-Null

# --------------------------------------------------------------------------
# 1. CrackPrivilegeManager -> classes22.dex
# --------------------------------------------------------------------------
Step 'javac (stubs -- never dexed)'
$stubSrc = @(Get-ChildItem (Join-Path $NorootSrc 'stubs') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$stubOut = Join-Path $work 'stubs'
& $javac @javacFlags -d $stubOut @stubSrc
if ($LASTEXITCODE -ne 0) { Fail 'stub compilation failed' }

Step 'javac (CrackPrivilegeManager)'
$src = @(Get-ChildItem (Join-Path $NorootSrc 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$classesDir = Join-Path $work 'classes'
& $javac @javacFlags -cp $stubOut -d $classesDir @src
if ($LASTEXITCODE -ne 0) { Fail 'CrackPrivilegeManager compilation failed' }

# The stubs exist only to satisfy javac. If one leaked into the dex it would
# SHADOW the app's real class of the same name and the whole crack would become
# a no-op -- so this is a hard build failure, not a warning.
$leak = @(Get-ChildItem $classesDir -Recurse -Filter *.class |
          Where-Object { $_.FullName -match '\\com\\dragon\\read\\' })
if ($leak.Count -gt 0) { Fail "stub class leaked into the output: $($leak[0].FullName)" }

Step 'd8 (CrackPrivilegeManager -> classes.dex)'
$classFiles = @(Get-ChildItem $classesDir -Recurse -Filter *.class | ForEach-Object { $_.FullName })
$dexOut = Join-Path $work 'dex'
New-Item -ItemType Directory -Force -Path $dexOut | Out-Null
# Start-Process, not the call operator: d8 needs each .class named individually,
# and PowerShell 5.1 mangles a single-element array splat whose element is a
# Windows path ("Error in program input 'C'").
$d8Args = @('-Xmx1g', '-cp', $d8Jar, 'com.android.tools.r8.D8',
            '--release', '--min-api', '24', '--lib', $androidJar,
            '--output', $dexOut) + $classFiles
$proc = Start-Process -FilePath 'java' -ArgumentList $d8Args -Wait -NoNewWindow -PassThru
if ($proc.ExitCode -ne 0) { Fail "d8 failed (exit $($proc.ExitCode))" }
$newDex = Join-Path $dexOut 'classes.dex'
if (-not (Test-Path $newDex)) { Fail 'd8 produced no classes.dex' }
Step ("  classes22.dex = {0:n0} bytes" -f (Get-Item $newDex).Length)

# --------------------------------------------------------------------------
# 2. smali patches
# --------------------------------------------------------------------------
Step 'patch classes3: PrivilegeManager.getInstance() -> CrackPrivilegeManager'

$pmFile = Join-Path $SmaliRoot 'smali_classes3\com\dragon\read\component\biz\impl\privilege\PrivilegeManager.smali'
if (-not (Test-Path $pmFile)) { Fail "not found: $pmFile" }
$t = [IO.File]::ReadAllText($pmFile)
$origInst = 'new-instance v1, Lcom/dragon/read/component/biz/impl/privilege/PrivilegeManager;'
$newInst  = 'new-instance v1, Lcom/deathbook/fanqie/crack/noroot/CrackPrivilegeManager;'
$origCtor = 'invoke-direct {v1}, Lcom/dragon/read/component/biz/impl/privilege/PrivilegeManager;-><init>()V'
$newCtor  = 'invoke-direct {v1}, Lcom/deathbook/fanqie/crack/noroot/CrackPrivilegeManager;-><init>()V'
if (-not $t.Contains($origInst)) { Fail 'getInstance() anchor not found -- already patched, or a different build' }
# [regex]::Matches, not String.Split: PowerShell coerces a string argument to
# char[] for Split(), which then splits on every character in the pattern and
# reports a false "more than one". Counting the literal occurrences is what was
# meant.
$hits = [regex]::Matches($t, [regex]::Escape($origInst)).Count
if ($hits -ne 1) { Fail "expected exactly 1 PrivilegeManager new-instance, found $hits" }
$t = $t.Replace($origInst, $newInst).Replace($origCtor, $newCtor)
[IO.File]::WriteAllText($pmFile, $t, (New-Object Text.UTF8Encoding($false)))
Write-Host '    retargeted the singleton allocation'

# --------------------------------------------------------------------------
Step 'patch classes2: NsAdDependImpl ad predicates'

$adFile = Join-Path $SmaliRoot 'smali_classes2\com\dragon\read\component\NsAdDependImpl.smali'
if (-not (Test-Path $adFile)) { Fail "not found: $adFile" }
# These three are computed from the current book's own ad configuration, so they
# never consult the privilege map -- a subclass of PrivilegeManager cannot reach
# them. Note isReaderAdFree returns int (0/1), not boolean: readerIsAdFree() is
# literally isReaderAdFree() != 0.
Set-SmaliMethodBody -File $adFile -MethodHeadRegex '^\.method public isReaderAdFree\(\)I' -Body @(
    '    .locals 1', '', '    const/4 v0, 0x1', '', '    return v0'
)
Set-SmaliMethodBody -File $adFile -MethodHeadRegex '^\.method public readerIsAdFree\(\)Z' -Body @(
    '    .locals 1', '', '    const/4 v0, 0x1', '', '    return v0'
)
Set-SmaliMethodBody -File $adFile -MethodHeadRegex '^\.method public audioIsAdFree\(Ljava/lang/String;\)Z' -Body @(
    '    .locals 1', '', '    const/4 v0, 0x1', '', '    return v0'
)
Write-Host '    isReaderAdFree/readerIsAdFree/audioIsAdFree -> ad-free'

# --------------------------------------------------------------------------
Step 'patch classes13: NsAdImpl.inspireAdDisable() -> true'

$adImpl = Join-Path $SmaliRoot 'smali_classes13\com\dragon\read\component\biz\impl\NsAdImpl.smali'
if (-not (Test-Path $adImpl)) { Fail "not found: $adImpl" }
# The app's own switch for "do not show the auto-read rewarded video", read by
# both ad sites (the expiry listener and zh6.m) before they do anything. It is
# the intended way to turn this ad off for a cohort, unlike isGoogleMarket(),
# which would change which features the app thinks it has.
Set-SmaliMethodBody -File $adImpl -MethodHeadRegex '^\.method public inspireAdDisable\(\)Z' -Body @(
    '    .locals 1', '', '    const/4 v0, 0x1', '', '    return v0'
)
Write-Host '    inspireAdDisable -> true'

# --------------------------------------------------------------------------
# 3. rebuild the three affected dexes
# --------------------------------------------------------------------------
Step 're-assemble patched dexes'
Rebuild-Dex -SmaliDir (Join-Path $SmaliRoot 'smali_classes2')  -OutDex (Join-Path $work 'classes2.dex')
Rebuild-Dex -SmaliDir (Join-Path $SmaliRoot 'smali_classes3')  -OutDex (Join-Path $work 'classes3.dex')
Rebuild-Dex -SmaliDir (Join-Path $SmaliRoot 'smali_classes13') -OutDex (Join-Path $work 'classes13.dex')

# --------------------------------------------------------------------------
# 4. assemble
# --------------------------------------------------------------------------
Step 'assemble APK'
$unsigned = Join-Path $work 'unsigned.apk'
Copy-Item $OriginalApk $unsigned -Force

$add = @(
    @{ file = (Join-Path $work 'classes2.dex');  entry = 'classes2.dex' },
    @{ file = (Join-Path $work 'classes3.dex');  entry = 'classes3.dex' },
    @{ file = (Join-Path $work 'classes13.dex'); entry = 'classes13.dex' },
    @{ file = $newDex;                           entry = 'classes22.dex' }
)

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, 'Update')
try {
    foreach ($a in $add) {
        $old = $zip.Entries | Where-Object { $_.FullName -eq $a.entry }
        if ($old) { $old.Delete() }
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $a.file, $a.entry, [System.IO.Compression.CompressionLevel]::Optimal)
        Step ("  {0,-18} {1,12:n0} bytes" -f $a.entry, (Get-Item $a.file).Length)
    }
    # The old signature describes the APK as it was before these edits.
    foreach ($e in @($zip.Entries | Where-Object {
                $_.FullName -like 'META-INF/*.RSA' -or
                $_.FullName -like 'META-INF/*.SF' -or
                $_.FullName -like 'META-INF/*.DSA' -or
                $_.FullName -eq 'META-INF/MANIFEST.MF' })) {
        $e.Delete()
    }
} finally { $zip.Dispose() }

$aligned = Join-Path $work 'aligned.apk'
Step 'zipalign'
& $zipalign -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { Fail 'zipalign failed' }

$finalApk = Join-Path $OutDir 'FanQieNovelCrack-noroot-v1.0.apk'
Copy-Item $aligned $finalApk -Force

if (-not $SkipSign) {
    if (-not (Test-Path $Keystore)) {
        Step 'keytool (creating keystore)'
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
    # Capture before slicing: `cmd | Select-Object -First N` stops the pipeline
    # early, which kills the native process and makes $LASTEXITCODE non-zero on
    # an otherwise successful verify.
    $verifyOut = & $apksigner verify --print-certs $finalApk 2>&1
    $verifyOut | Select-Object -First 3
    if ($LASTEXITCODE -ne 0) { Fail 'apksigner verify failed' }
}

$item = Get-Item $finalApk
Step ("built {0}  ({1:n0} bytes, sha256 {2})" -f $item.Name, $item.Length,
      (Get-FileHash $finalApk -Algorithm SHA256).Hash)
Write-Host @"

Note: signed with a different key than the official app, so uninstall first.

    adb uninstall com.dragon.read
    adb install "$finalApk"
"@
