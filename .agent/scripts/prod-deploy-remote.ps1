# Runs ON the production server (invoked through WinRM by prod-deploy.ps1).
#
# Order matters: the app is stopped before files are replaced, and the restart is
# done through an /IT scheduled task so the minimized "GFS App (80)" watchdog
# window lands in the logged-on user session instead of session 0.
#
# ASCII only. PowerShell 5.1 decodes UTF-8 without BOM as ANSI, so any non-ASCII
# character here can break parsing once the file is sent by remoting.
#
# Layout expected in $StagingDir:
#     fs-admin.jar        required
#     frontend\           unless -SkipFrontend 1
#     conf\               optional
#     sql\init.sql        optional (copied, never executed)
param(
    [Parameter(Mandatory = $true)][string]$StagingDir,
    [Parameter(Mandatory = $true)][string]$JarHash,
    [string]$Account = 'administrator',
    [Parameter(Mandatory = $true)][string]$Password,
    [string]$SkipFrontend = 'false'
)

$ErrorActionPreference = 'Stop'
$skip = ($SkipFrontend -eq 'true' -or $SkipFrontend -eq '1')
$root = 'D:\application\gfs'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$task = 'GFS-Deploy-Start'
$stageJar = Join-Path $StagingDir 'fs-admin.jar'
Start-Transcript -Path "C:\Windows\Temp\gfs-deploy-$stamp.log" -Force | Out-Null

function Get-Port80Pid {
    $c = Get-NetTCPConnection -State Listen -LocalPort 80 -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { return [int]$c.OwningProcess }
    return 0
}

$ok = $false
try {
    Write-Output "== deploy start $stamp =="
    if (-not (Test-Path $stageJar)) { throw "staging jar missing: $stageJar" }
    $h = (Get-FileHash $stageJar -Algorithm SHA256).Hash
    if ($h -ne $JarHash.ToUpper()) { throw "staging jar hash mismatch: $h" }
    if (-not $skip -and -not (Test-Path (Join-Path $StagingDir 'frontend\index.html'))) {
        throw 'staging frontend\index.html missing'
    }
    Write-Output "staging verified: $h"

    $appPid = Get-Port80Pid
    if ($appPid -gt 0) {
        Write-Output "stopping app pid $appPid (mysql/redis untouched)"
        Stop-Process -Id $appPid -Force -ErrorAction SilentlyContinue
        $deadline = (Get-Date).AddSeconds(30)
        while ((Get-Date) -lt $deadline -and (Get-Port80Pid) -gt 0) { Start-Sleep -Milliseconds 500 }
    }
    if ((Get-Port80Pid) -gt 0) { throw 'port 80 still in use after stop' }

    Copy-Item $stageJar "$root\lib\fs-admin.jar" -Force
    if (-not $skip) {
        if (Test-Path "$root\frontend") { Remove-Item "$root\frontend" -Recurse -Force }
        Move-Item (Join-Path $StagingDir 'frontend') "$root\frontend"
    }
    if (Test-Path (Join-Path $StagingDir 'conf')) {
        Get-ChildItem (Join-Path $StagingDir 'conf') -File | ForEach-Object { Copy-Item $_.FullName "$root\conf\$($_.Name)" -Force }
    }
    if (Test-Path (Join-Path $StagingDir 'sql\init.sql')) { Copy-Item (Join-Path $StagingDir 'sql\init.sql') "$root\sql\init.sql" -Force }

    $placed = (Get-FileHash "$root\lib\fs-admin.jar" -Algorithm SHA256).Hash
    if ($placed -ne $JarHash.ToUpper()) { throw "deployed jar hash mismatch: $placed" }
    Write-Output "jar in place: $placed"
    if (-not $skip) {
        Write-Output ("frontend in place: {0} files" -f (Get-ChildItem "$root\frontend" -Recurse -File).Count)
    }

    Write-Output "creating interactive task '$task'"
    $ErrorActionPreference = 'Continue'
    & schtasks.exe /create /f /tn $task /tr 'cmd.exe /c D:\application\gfs\bin\start.bat' /sc once /st 00:00 /ru $Account /rp $Password /it /rl HIGHEST 2>&1 | ForEach-Object { "  $_" }
    $rc = $LASTEXITCODE
    Write-Output "create exit: $rc"
    if ($rc -ne 0) { throw 'schtasks create failed' }
    & schtasks.exe /run /tn $task 2>&1 | ForEach-Object { "  $_" }
    Write-Output "run exit: $LASTEXITCODE"
    $ErrorActionPreference = 'Stop'

    $deadline = (Get-Date).AddSeconds(240)
    while ((Get-Date) -lt $deadline -and (Get-Port80Pid) -eq 0) { Start-Sleep -Seconds 2 }
    $appPid = Get-Port80Pid
    if ($appPid -eq 0) { throw 'port 80 did not come up within 240s' }

    $java = Get-Process -Id $appPid -ErrorAction SilentlyContinue
    Write-Output "app pid: $appPid session: $($java.SessionId)"

    $logDir = Join-Path $root 'logs'
    $date = Get-Date -Format 'yyyy-MM-dd'
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        $newest = Get-ChildItem $logDir -Filter "gfs.$date*.log" -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($newest) {
            $hit = Select-String -Path $newest.FullName -Pattern 'Started FsAdminApplication' -SimpleMatch -ErrorAction SilentlyContinue |
                Select-Object -Last 1
            if ($hit) { Write-Output "log: $($hit.Line.Trim())"; break }
        }
        Start-Sleep -Seconds 2
    }
    try {
        $r = Invoke-WebRequest -Uri 'http://127.0.0.1/' -UseBasicParsing -TimeoutSec 15
        Write-Output "http: $($r.StatusCode)"
    } catch { Write-Output "http error: $($_.Exception.Message)" }

    $ok = $true
} catch {
    Write-Output "DEPLOY FAILED: $($_.Exception.Message)"
} finally {
    $ErrorActionPreference = 'Continue'
    & schtasks.exe /delete /tn $task /f 2>&1 | Out-Null
    Remove-Item $StagingDir -Recurse -Force -ErrorAction SilentlyContinue
    Stop-Transcript | Out-Null
}

if (-not $ok) {
    Write-Output 'no backup is kept on production: rebuild or redeploy the previous version if needed'
    throw 'deployment failed - see output above'
}
Write-Output 'DEPLOY OK'
