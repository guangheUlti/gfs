# ============================================================================
#  GFS - release packaging
#
#  Builds the backend jar + frontend bundle, stages them into
#  release\deploy-package, and produces a *clean* distributable zip (no local
#  runtime data, no logs).
#
#  Usage:
#      powershell -ExecutionPolicy Bypass -File script\package-release.ps1
#      powershell -ExecutionPolicy Bypass -File script\package-release.ps1 -SkipBuild
#      powershell -ExecutionPolicy Bypass -File script\package-release.ps1 -Version 2.3.2
#
#  Notes:
#   - release\deploy-package\lib\{jdk,mysql,redis} are third-party runtimes that
#     are NOT tracked by git (see .gitignore). They must already exist locally for
#     the zip to be self-contained; the script only warns when one is missing.
#   - The zip is meant to be attached to a GitHub Release, never committed.
# ============================================================================

[CmdletBinding()]
param(
    [string]$Version,
    [string]$MavenCmd = 'mvn',
    [switch]$SkipBuild,
    [switch]$SkipFrontend
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$PkgDir   = Join-Path $RepoRoot 'release\deploy-package'
$JarPath  = Join-Path $RepoRoot 'fs-admin\target\fs-admin.jar'
$DistPath = Join-Path $RepoRoot 'fs-ui\dist'

function Write-Step([string]$msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }

# ---------------------------------------------------------------- version ---
if (-not $Version) {
    $pom = Get-Content (Join-Path $RepoRoot 'pom.xml') -Raw
    if ($pom -match '<revision>([^<]+)</revision>') { $Version = $Matches[1] }
    else { throw 'Cannot resolve version: pass -Version or define <revision> in pom.xml' }
}
Write-Host "GFS release $Version"
Write-Host "repo : $RepoRoot"

if (-not (Test-Path $PkgDir)) { throw "deploy-package template not found: $PkgDir" }

# ----------------------------------------------------------------- build ----
if (-not $SkipBuild) {
    Write-Step 'Building frontend'
    if (-not $SkipFrontend) {
        Push-Location (Join-Path $RepoRoot 'fs-ui')
        try {
            & pnpm install --frozen-lockfile
            if ($LASTEXITCODE -ne 0) { throw "pnpm install failed ($LASTEXITCODE)" }
            & pnpm build
            if ($LASTEXITCODE -ne 0) { throw "pnpm build failed ($LASTEXITCODE)" }
        }
        finally { Pop-Location }
    }

    Write-Step 'Building backend'
    & $MavenCmd clean package -DskipTests -q
    if ($LASTEXITCODE -ne 0) { throw "maven package failed ($LASTEXITCODE)" }
}

if (-not (Test-Path $JarPath)) { throw "backend jar not found: $JarPath (run without -SkipBuild)" }

# ----------------------------------------------------------- stage output ---
Write-Step 'Staging jar and frontend into deploy-package'
Copy-Item $JarPath (Join-Path $PkgDir 'lib\fs-admin.jar') -Force
$jar = Get-Item (Join-Path $PkgDir 'lib\fs-admin.jar')
Write-Host ("  lib\fs-admin.jar : {0:N1} MB" -f ($jar.Length / 1MB))

if (-not $SkipFrontend) {
    if (-not (Test-Path $DistPath)) { throw "frontend dist not found: $DistPath (run without -SkipFrontend)" }
    $null = & robocopy $DistPath (Join-Path $PkgDir 'frontend') /MIR /NFL /NDL /NJH /NJS /NP
    # robocopy reports 0..7 as success
    if ($LASTEXITCODE -gt 7) { throw "robocopy failed ($LASTEXITCODE)" }
    $fe = Get-ChildItem (Join-Path $PkgDir 'frontend') -Recurse -File
    Write-Host ("  frontend        : {0} files, {1:N1} MB" -f $fe.Count, (($fe | Measure-Object Length -Sum).Sum / 1MB))
}

foreach ($runtime in 'jdk', 'mysql', 'redis') {
    if (-not (Test-Path (Join-Path $PkgDir "lib\$runtime"))) {
        Write-Warning "lib\$runtime is missing - the zip will NOT be self-contained (see release\deploy-package\README.md)"
    }
}

# ------------------------------------------------------------------- zip ----
Write-Step 'Creating clean zip'
# Runtime state must never ship: MySQL/Redis data dirs, app logs, and the two
# config files bin\env.bat regenerates on first run (redis.conf embeds absolute
# machine-local paths).
$excludePrefixes = @(
    'data\mysql\',
    'data\redis\',
    'data\upload\',
    'logs\'
)
$excludeFiles = @(
    'conf\my.ini',
    'conf\redis.conf'
)
# Empty directory skeleton that the launchers expect to exist.
$keepEmptyDirs = @('data', 'data\mysql', 'data\redis', 'data\upload', 'logs', 'storage')

$zipPath = Join-Path $RepoRoot "release\gfs-$Version-windows-x64.zip"

$prefix = $PkgDir.TrimEnd('\') + '\'
function Test-Excluded([string]$rel) {
    foreach ($p in $excludePrefixes) { if ($rel.StartsWith($p, 'OrdinalIgnoreCase')) { return $true } }
    foreach ($f in $excludeFiles) { if ($rel -ieq $f) { return $true } }
    return $false
}

# ZIP entries must use '/' as the path separator (APPNOTE 4.4.17); Windows
# Explorer tolerates '\' but unzip/7-zip on POSIX would produce one flat file
# literally named "bin\start.bat".
function Get-ZipPath([string]$rel) { return "gfs-$Version/" + $rel.Replace('\', '/') }

$toPack = Get-ChildItem $PkgDir -Recurse -File | Where-Object {
    $_.Extension -ne '.log' -and (Test-Excluded $_.FullName.Substring($prefix.Length)) -eq $false
}

if (Test-Path $zipPath) { Remove-Item $zipPath -Force }

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$fs   = [System.IO.File]::Open($zipPath, [System.IO.FileMode]::CreateNew)
$arch = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in $toPack) {
        $rel = $file.FullName.Substring($prefix.Length)
        $entry = $arch.CreateEntry((Get-ZipPath $rel), [System.IO.Compression.CompressionLevel]::Optimal)
        $stream = $entry.Open()
        try {
            $src = [System.IO.File]::OpenRead($file.FullName)
            try { $src.CopyTo($stream) } finally { $src.Dispose() }
        }
        finally { $stream.Dispose() }
    }

    # directory markers (zip has no real dirs; these keep the skeleton intact on extract)
    foreach ($d in $keepEmptyDirs) {
        if (-not (Test-Path (Join-Path $PkgDir $d))) { New-Item -ItemType Directory -Path (Join-Path $PkgDir $d) -Force | Out-Null }
        $null = $arch.CreateEntry((Get-ZipPath "$d/"))
    }
    Write-Host ("  {0} files, {1:N1} MB uncompressed (runtime data and logs excluded)" -f $toPack.Count, (($toPack | Measure-Object Length -Sum).Sum / 1MB))
}
finally {
    $arch.Dispose()
    $fs.Dispose()
}

$zip = Get-Item $zipPath
$hash = (Get-FileHash $zipPath -Algorithm SHA256).Hash
Write-Step 'Done'
Write-Host ("  {0}" -f $zip.FullName)
Write-Host ("  size   : {0:N1} MB" -f ($zip.Length / 1MB))
Write-Host ("  sha256 : {0}" -f $hash)
Write-Host ''
Write-Host 'Upload it as a GitHub Release asset (never commit the zip):'
Write-Host "  gh release upload v$Version '$(Split-Path $zip -Leaf)' --clobber"
