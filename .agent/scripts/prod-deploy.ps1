#requires -Version 5.1
<#
Deploy a GFS build to production 172.19.10.153 (Windows).

  jar + frontend           -> replaces lib\fs-admin.jar and frontend\
  -Conf fs-admin\...\conf  -> copies application*.yml into conf\ (optional)
  -Sql sql\mysql\gfs.sql   -> copies to sql\init.sql (never executed, optional)

What it does: uploads to C:\Windows\Temp\gfs-deploy, verifies hashes, then runs
prod-deploy-remote.ps1 on the server (stop app only -> replace files ->
start through an /IT scheduled task so the watchdog console stays in the user's
taskbar -> verify). MySQL/Redis are never stopped. No backup is kept on
production by policy; rollback means redeploying the previous build.

Requires -Yes. Credentials: $env:GFS_PROD_USER / $env:GFS_PROD_PASS or
%USERPROFILE%\.gfs\prod-153.cred.

Example:
  powershell -ExecutionPolicy Bypass -File .agent\scripts\prod-deploy.ps1 `
    -Jar fs-admin\target\fs-admin.jar -Frontend fs-ui\dist -Yes
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [string]$Frontend,
    [string]$Conf,
    [string]$Sql,
    [switch]$SkipFrontend,
    [switch]$Yes,
    [int]$TimeoutSec = 900
)

$ErrorActionPreference = 'Stop'
if (-not $Yes) { throw 'This replaces production files. Re-run with -Yes.' }
if (-not (Test-Path $Jar)) { throw "jar not found: $Jar" }
if ($Frontend -and -not (Test-Path (Join-Path $Frontend 'index.html'))) { throw "frontend dir must contain index.html: $Frontend" }
if ($Frontend -and $SkipFrontend) { throw 'Use either -Frontend or -SkipFrontend.' }

$ip = '172.19.10.153'
$stagingRemote = 'C:\Windows\Temp\gfs-deploy'

$user = if ($env:GFS_PROD_USER) { $env:GFS_PROD_USER } else { 'administrator' }
$pass = $env:GFS_PROD_PASS
if (-not $pass) {
    $file = Join-Path $HOME '.gfs\prod-153.cred'
    if (Test-Path -LiteralPath $file) {
        foreach ($line in Get-Content -LiteralPath $file) {
            if ($line -match '^\s*(user|username)\s*=\s*(.+?)\s*$') { $user = $Matches[2] }
            elseif ($line -match '^\s*(pass|password)\s*=\s*(.+?)\s*$') { $pass = $Matches[2] }
        }
    }
}
if (-not $pass) { throw 'Missing credential: set $env:GFS_PROD_PASS or create $HOME\.gfs\prod-153.cred' }
$cred = New-Object System.Management.Automation.PSCredential($user, (ConvertTo-SecureString $pass -AsPlainText -Force))

foreach ($letter in 'C$', 'D$') {
    $name = 'GFS_' + $letter.Substring(0, 1)
    if (-not (Get-PSDrive -Name $name -ErrorAction SilentlyContinue)) {
        New-PSDrive -Name $name -PSProvider FileSystem -Root "\\$ip\$letter" -Credential $cred -Scope Global | Out-Null
    }
}
$stageUnc = "\\$ip\C$\Windows\Temp\gfs-deploy"

Write-Output '== upload to staging =='
if (Test-Path -LiteralPath $stageUnc) { Remove-Item -LiteralPath $stageUnc -Recurse -Force }
New-Item -ItemType Directory -Path $stageUnc | Out-Null
Copy-Item -LiteralPath $Jar -Destination (Join-Path $stageUnc 'fs-admin.jar') -Force
if ($Frontend) {
    Copy-Item -LiteralPath $Frontend -Destination (Join-Path $stageUnc 'frontend') -Recurse -Force
    Write-Output ("frontend files: " + (Get-ChildItem (Join-Path $stageUnc 'frontend') -Recurse -File).Count)
}
if ($Conf) { Copy-Item -LiteralPath $Conf -Destination (Join-Path $stageUnc 'conf') -Recurse -Force }
if ($Sql) {
    New-Item -ItemType Directory -Path (Join-Path $stageUnc 'sql') -Force | Out-Null
    Copy-Item -LiteralPath $Sql -Destination (Join-Path $stageUnc 'sql\init.sql') -Force
}

$localHash = (Get-FileHash -LiteralPath $Jar -Algorithm SHA256).Hash
$remoteHash = (Get-FileHash -LiteralPath (Join-Path $stageUnc 'fs-admin.jar') -Algorithm SHA256).Hash
if ($localHash -ne $remoteHash) { throw "upload hash mismatch: local=$localHash remote=$remoteHash" }
Write-Output "uploaded jar: $remoteHash"

$session = New-PSSession -ComputerName $ip -UseSSL -Port 5986 -Credential $cred `
    -SessionOption (New-PSSessionOption -SkipCACheck -SkipCNCheck -SkipRevocationCheck -OperationTimeout ($TimeoutSec * 1000))
try {
    Invoke-Command -Session $session -FilePath (Join-Path $PSScriptRoot 'prod-deploy-remote.ps1') `
        -ArgumentList $stagingRemote, $remoteHash, $user, $pass, $(if ($SkipFrontend) { 'true' } else { 'false' }) |
        ForEach-Object { "$_" }
} finally {
    Remove-PSSession $session
}
