# ============================================================================
#  GFS - Linux release packaging
#
#  Builds the backend jar + frontend bundle, stages them with the Linux
#  deployment scripts and the bundled x86_64 runtimes, and produces a clean
#  distributable tarball (no runtime data, no logs).
#
#  Usage:
#      powershell -ExecutionPolicy Bypass -File script\package-release-linux.ps1 -Version 5.0.8
#      powershell -ExecutionPolicy Bypass -File script\package-release-linux.ps1 -Version 5.0.8 -SkipBuild
#
#  Notes:
#   - Linux runtimes have no portable "extracted" form we can stage from
#     Windows without losing symlinks and executable bits, so the package
#     ships the official archives under lib/runtime/ and bin/env.sh unpacks
#     them into lib/{jdk,mysql,redis} on the first run.
#   - release\deploy-package-linux is both the script template and the build
#     output directory: the runtimes are downloaded into its lib\runtime\ once
#     and then reused, and the tarball is produced straight from it. lib\ is
#     gitignored (the archives are ~310 MB), everything else is tracked.
#   - tar.gz creation is delegated to Git Bash (GNU tar + chmod), because
#     Windows tooling cannot record POSIX file modes. Git Bash must be on PATH
#     (or pass -BashExe).
#   - The tarball is meant to be attached to a GitHub Release, never committed.
# ============================================================================

[CmdletBinding()]
param(
    [string]$Version,
    [string]$MavenCmd = 'mvn',
    [switch]$SkipBuild,
    [switch]$SkipFrontend,
    [string]$RuntimeDir,
    [string]$BashExe,
    [string]$JdkUrl = 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz',
    [string]$MysqlUrl = 'https://cdn.mysql.com/Downloads/MySQL-8.4/mysql-8.4.13-linux-glibc2.28-x86_64-minimal.tar.xz',
    [string]$ValkeyUrl = 'https://download.valkey.io/releases/valkey-9.0.7-jammy-x86_64.tar.gz'
)

$ErrorActionPreference = 'Stop'
$RepoRoot   = Split-Path -Parent $PSScriptRoot
$TemplateDir = Join-Path $RepoRoot 'release\deploy-package-linux'
$PkgDir     = $TemplateDir
$JarPath    = Join-Path $RepoRoot 'fs-admin\target\fs-admin.jar'
$DistPath   = Join-Path $RepoRoot 'fs-ui\dist'
$StaticPath = Join-Path $RepoRoot 'fs-admin\src\main\resources\static'
$WinPkgDir  = Join-Path $RepoRoot 'release\deploy-package'

function Write-Step([string]$msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }

function To-UnixPath([string]$p) {
    $full = [System.IO.Path]::GetFullPath($p).Replace('\', '/')
    if ($full -match '^([A-Za-z]):/(.*)$') {
        return "/$($Matches[1].ToLower())/$($Matches[2])"
    }
    return $full
}

# ---------------------------------------------------------------- version ---
if (-not $Version) {
    $pom = Get-Content (Join-Path $RepoRoot 'pom.xml') -Raw
    if ($pom -match '<revision>([^<]+)</revision>') { $Version = $Matches[1] }
    else { throw 'Cannot resolve version: pass -Version or define <revision> in pom.xml' }
}
Write-Host "GFS Linux release $Version"
Write-Host "repo : $RepoRoot"

if (-not (Test-Path $TemplateDir)) { throw "deploy-package-linux template not found: $TemplateDir" }

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

    Write-Step 'Syncing frontend bundle into jar embedded static'
    if (-not (Test-Path $DistPath)) { throw "frontend dist not found: $DistPath" }
    $null = & robocopy $DistPath $StaticPath /MIR /NFL /NDL /NJH /NJS /NP
    if ($LASTEXITCODE -gt 7) { throw "robocopy (embedded static sync) failed ($LASTEXITCODE)" }
    $st = Get-ChildItem $StaticPath -Recurse -File
    Write-Host ("  static          : {0} files mirrored from dist (jar serves classpath:/static/)" -f $st.Count)

    Write-Step 'Building backend'
    & $MavenCmd clean package -DskipTests -q
    if ($LASTEXITCODE -ne 0) { throw "maven package failed ($LASTEXITCODE)" }
}

if (-not (Test-Path $JarPath)) { throw "backend jar not found: $JarPath (run without -SkipBuild)" }

# ------------------------------------------------- embedded frontend check ---
function Get-IndexAssetRefs([string]$htmlPath) {
    if (-not (Test-Path $htmlPath)) { return $null }
    ([regex]::Matches((Get-Content $htmlPath -Raw), 'assets/[\w.-]+\.(?:js|css)') |
        ForEach-Object { $_.Value } | Sort-Object -Unique)
}
function Get-EmbeddedIndexRefs([string]$jar) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $entry = $zip.GetEntry('BOOT-INF/classes/static/index.html')
        if (-not $entry) { return $null }
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try {
            ([regex]::Matches($reader.ReadToEnd(), 'assets/[\w.-]+\.(?:js|css)') |
                ForEach-Object { $_.Value } | Sort-Object -Unique)
        }
        finally { $reader.Dispose() }
    }
    finally { $zip.Dispose() }
}
if (Test-Path $DistPath) {
    $distRefs = @(Get-IndexAssetRefs (Join-Path $DistPath 'index.html'))
    $jarRefs  = @(Get-EmbeddedIndexRefs $JarPath)
    if ($distRefs.Count -gt 0 -and $jarRefs.Count -gt 0) {
        if (Compare-Object $distRefs $jarRefs) {
            throw (@(
                'backend jar embeds a STALE frontend (BOOT-INF/classes/static/index.html != fs-ui/dist/index.html)',
                "  dist refs: $($distRefs -join ', ')",
                "  jar refs : $($jarRefs -join ', ')",
                'Rebuild without -SkipBuild so dist is mirrored into fs-admin/src/main/resources/static first.'
            ) -join "`n")
        }
        Write-Host ("  embedded frontend check: OK ({0} asset refs match dist)" -f $distRefs.Count)
    }
    else {
        Write-Warning 'Could not verify embedded frontend (no asset refs in dist or jar index.html).'
    }
}

# --------------------------------------------------------- runtime archives ---
Write-Step 'Preparing Linux runtime archives'
# they live inside the package directory itself, so the template stays self
# contained and no separate download cache has to be kept around
if (-not $RuntimeDir) { $RuntimeDir = Join-Path $PkgDir 'lib\runtime' }
if (-not (Test-Path $RuntimeDir)) { New-Item -ItemType Directory -Path $RuntimeDir -Force | Out-Null }

$runtimes = @(
    @{ Name = 'jdk.tar.gz';    Url = $JdkUrl },
    @{ Name = 'mysql.tar.xz';  Url = $MysqlUrl },
    @{ Name = 'valkey.tar.gz'; Url = $ValkeyUrl }
)

function Get-RemoteFile([string]$url, [string]$dest) {
    $tmp = "$dest.part"
    Write-Host "  downloading $url"
    & curl.exe -fL --retry 3 --retry-delay 2 -o $tmp $url
    if ($LASTEXITCODE -ne 0) {
        if (Test-Path $tmp) { Remove-Item $tmp -Force }
        throw "download failed ($LASTEXITCODE): $url"
    }
    Move-Item $tmp $dest -Force
}

foreach ($rt in $runtimes) {
    $dest = Join-Path $RuntimeDir $rt.Name
    if (Test-Path $dest) {
        Write-Host ("  {0,-16}: cached ({1:N1} MB)" -f $rt.Name, ((Get-Item $dest).Length / 1MB))
    }
    else {
        Get-RemoteFile $rt.Url $dest
        Write-Host ("  {0,-16}: {1:N1} MB" -f $rt.Name, ((Get-Item $dest).Length / 1MB))
    }
    if ((Get-Item $dest).Length -lt 1MB) { throw "runtime archive looks truncated: $dest" }
}

# ------------------------------------------------------------- package dir ---
Write-Step 'Assembling Linux deployment package'
# build straight into the package directory: what you inspect afterwards is
# exactly what the tarball contains
New-Item -ItemType Directory -Path (Join-Path $PkgDir 'conf') -Force | Out-Null
Copy-Item (Join-Path $WinPkgDir 'conf\application.yml')      (Join-Path $PkgDir 'conf\application.yml') -Force
Copy-Item (Join-Path $WinPkgDir 'conf\application-prod.yml') (Join-Path $PkgDir 'conf\application-prod.yml') -Force
New-Item -ItemType Directory -Path (Join-Path $PkgDir 'sql') -Force | Out-Null
Copy-Item (Join-Path $WinPkgDir 'sql\init.sql') (Join-Path $PkgDir 'sql\init.sql') -Force

Copy-Item $JarPath (Join-Path $PkgDir 'lib\fs-admin.jar') -Force
New-Item -ItemType Directory -Path (Join-Path $PkgDir 'lib\runtime') -Force | Out-Null
foreach ($rt in $runtimes) {
    $src = Join-Path $RuntimeDir $rt.Name
    $dst = Join-Path $PkgDir "lib\runtime\$($rt.Name)"
    # identical when the archives already sit in the package (the normal case)
    if ([System.IO.Path]::GetFullPath($src) -ne [System.IO.Path]::GetFullPath($dst)) {
        Copy-Item $src $dst -Force
    }
}

$sources = foreach ($rt in $runtimes) {
    $file = Join-Path $PkgDir "lib\runtime\$($rt.Name)"
    $hash = (Get-FileHash $file -Algorithm SHA256).Hash.ToLower()
    "{0}`n  url    : {1}`n  sha256 : {2}`n" -f $rt.Name, $rt.Url, $hash
}
Set-Content -Path (Join-Path $PkgDir 'lib\runtime\SOURCES.txt') -Value $sources -Encoding UTF8

if (-not $SkipFrontend) {
    if (-not (Test-Path $DistPath)) { throw "frontend dist not found: $DistPath (run without -SkipFrontend)" }
    $null = & robocopy $DistPath (Join-Path $PkgDir 'frontend') /MIR /NFL /NDL /NJH /NJS /NP
    if ($LASTEXITCODE -gt 7) { throw "robocopy (frontend) failed ($LASTEXITCODE)" }
    $fe = Get-ChildItem (Join-Path $PkgDir 'frontend') -Recurse -File
    Write-Host ("  frontend        : {0} files, {1:N1} MB" -f $fe.Count, (($fe | Measure-Object Length -Sum).Sum / 1MB))
}

# generated on the target host by bin/env.sh / the JVM, never shipped
foreach ($f in 'my.cnf', 'redis.conf', 'jvm-xmx.conf', 'jvm-restart.flag') {
    $p = Join-Path $PkgDir "conf\$f"
    if (Test-Path $p) { Remove-Item $p -Force }
}

# empty directory skeleton the launchers expect to exist; anything already in
# there is a real run's data, so refuse instead of deleting it
$dirty = foreach ($d in 'data', 'logs', 'storage') {
    $p = Join-Path $PkgDir $d
    if ((Test-Path $p) -and (Get-ChildItem $p -Force)) { $d }
}
if ($dirty) {
    throw ("runtime data found in {0} ({1}) - move it away before packaging" -f $PkgDir, ($dirty -join ', '))
}
foreach ($d in 'data', 'data\mysql', 'data\redis', 'data\upload', 'logs', 'storage') {
    New-Item -ItemType Directory -Path (Join-Path $PkgDir $d) -Force | Out-Null
}

$jar = Get-Item (Join-Path $PkgDir 'lib\fs-admin.jar')
Write-Host ("  lib\fs-admin.jar : {0:N1} MB" -f ($jar.Length / 1MB))

# ----------------------------------------------------------------- tar.gz ----
Write-Step 'Creating clean tar.gz'
if (-not $BashExe) {
    $bashCmd = Get-Command bash.exe -ErrorAction SilentlyContinue
    if (-not $bashCmd) { throw 'bash.exe not found - install Git for Windows or pass -BashExe' }
    $BashExe = $bashCmd.Source
}

$tarPath = Join-Path $RepoRoot "release\gfs-$Version-linux-x64.tar.gz"
if (Test-Path $tarPath) { Remove-Item $tarPath -Force }

$releaseUnix   = To-UnixPath (Join-Path $RepoRoot 'release')
$pkgDirName    = Split-Path $PkgDir -Leaf
$tarUnix       = To-UnixPath $tarPath
$bashScript = @"
set -e
cd '$releaseUnix'
chmod +x '$pkgDirName'/bin/*.sh
tar --owner=0 --group=0 --numeric-owner -czf '$tarUnix' --transform 's,^$pkgDirName,gfs-$Version,' '$pkgDirName'
"@
$bashScriptPath = Join-Path $env:TEMP "gfs-linux-mktar-$PID.sh"
[System.IO.File]::WriteAllText($bashScriptPath, $bashScript.Replace("`r`n", "`n"), [System.Text.Encoding]::ASCII)
& $BashExe (To-UnixPath $bashScriptPath)
if ($LASTEXITCODE -ne 0) { throw "tar creation failed ($LASTEXITCODE)" }
Remove-Item $bashScriptPath -Force

$tar = Get-Item $tarPath
$hash = (Get-FileHash $tarPath -Algorithm SHA256).Hash
Write-Step 'Done'
Write-Host ("  {0}" -f $tar.FullName)
Write-Host ("  size   : {0:N1} MB" -f ($tar.Length / 1MB))
Write-Host ("  sha256 : {0}" -f $hash)
Write-Host ''
Write-Host 'Upload it as a GitHub Release asset (never commit the tarball):'
Write-Host "  gh release upload v$Version '$(Split-Path $tarPath -Leaf)' --clobber"
