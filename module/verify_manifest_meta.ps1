<#
.SYNOPSIS
    Assert the compiled AndroidManifest carries the Xposed meta-data inside
    <application>.
.DESCRIPTION
    aapt2 compiles <meta-data> that sits next to <application> (i.e. as a child
    of <manifest>) without a single warning, and the APK installs normally. But
    PackageManager then never attaches those items to ApplicationInfo, so the
    app observes

        getPackageInfo(pkg, GET_META_DATA).applicationInfo.metaData == null

    LSPosed's manager decides "is this package a module" with

        Bundle b = pi.applicationInfo.metaData;
        if (b != null && b.containsKey("xposedminversion")) { ...it is a module... }

    so a null Bundle means the module never appears in the manager's list --
    while LSPosed's daemon will still load and run it if its database row is
    written by hand. That combination is what let the bug survive testing: the
    module provably worked, so the manifest was assumed to be fine.

    The source is not a useful place to check, because the mistake looks
    plausible. The compiled manifest is.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $Aapt2,
    [Parameter(Mandatory = $true)][string] $Apk
)

$ErrorActionPreference = 'Continue'

$tree = & $Aapt2 dump xmltree --file AndroidManifest.xml $Apk 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "!!! aapt2 dump failed on $Apk"
    exit 1
}

# xmltree indents two spaces per nesting level, so a child of <application> sits
# deeper than the <application> line itself.
$appIndent = -1
$metaIndent = -1
$seen = @{}
$lastMetaName = $null

foreach ($line in $tree) {
    if ($line -notmatch '^(\s*)(E|A):\s*(.*)$') { continue }
    $indent = $Matches[1].Length
    $kind = $Matches[2]
    $body = $Matches[3]

    if ($kind -eq 'E' -and $body -like 'application*') {
        $appIndent = $indent
        continue
    }
    if ($kind -eq 'E' -and $body -like 'meta-data*') {
        $metaIndent = if ($appIndent -ge 0 -and $indent -gt $appIndent) { $indent } else { -1 }
        $lastMetaName = $null
        continue
    }
    if ($kind -eq 'A' -and $metaIndent -gt $appIndent -and $body -match 'name\([^)]*\)="(xposed[a-z]+)"') {
        $lastMetaName = $Matches[1]
        $seen[$lastMetaName] = $true
    }
}

if ($appIndent -lt 0) {
    Write-Host '!!! no <application> element in the compiled manifest'
    exit 1
}
if (-not $seen.ContainsKey('xposedminversion')) {
    Write-Host @'
!!! xposedminversion is NOT a child of <application> in the compiled manifest.

    PackageManager will report applicationInfo.metaData == null, and LSPosed's
    manager will never list this module. The usual cause is <application ... />
    written as a self-closing tag with the <meta-data> elements after it.
'@
    exit 1
}

Write-Host ("    application > meta-data: " + (($seen.Keys | Sort-Object) -join ', '))
if (-not $seen.ContainsKey('xposedmodule')) {
    Write-Host '    note: no xposedmodule flag (LSPosed keys off xposedminversion, so this is harmless)'
}
exit 0
