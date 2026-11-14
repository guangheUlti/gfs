#requires -Version 5.1
<#
.SYNOPSIS
  Remote helper for the GFS production Windows server 172.19.10.153.

.DESCRIPTION
  Channels used:
    * SMB admin shares (C$, D$)        - file listing / download / upload
    * DCOM Win32_Process.Create        - run commands without WinRM/SSH

  Credentials are never stored in the repository. Resolution order:
    1. $env:GFS_PROD_USER / $env:GFS_PROD_PASS
    2. %USERPROFILE%\.gfs\prod-153.cred  (lines: user=... / password=...)

.EXAMPLES
  powershell -ExecutionPolicy Bypass -File .agent/scripts/prod-153.ps1 status
  powershell -ExecutionPolicy Bypass -File .agent/scripts/prod-153.ps1 ls "D:\application\gfs\logs"
  powershell -ExecutionPolicy Bypass -File .agent/scripts/prod-153.ps1 get "D:\application\gfs\logs\gfs.2026-11-14.0.log" ".agent/tmp/"
  powershell -ExecutionPolicy Bypass -File .agent/scripts/prod-153.ps1 put "fs-admin\target\fs-admin.jar" "D:\application\gfs\lib\fs-admin.jar"
  powershell -ExecutionPolicy Bypass -File .agent/scripts/prod-153.ps1 exec "bin\status.bat"
  powershell -ExecutionPolicy Bypass -File .agent/scripts/prod-153.ps1 logs -Tail 120
  powershell -ExecutionPolicy Bypass -File .agent/scripts/prod-153.ps1 restart -Yes
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('status', 'ls', 'get', 'put', 'exec', 'logs', 'restart', 'deploy-jar', 'deploy-frontend')]
    [string]$Action,

    [Parameter(Position = 1)][string]$A,
    [Parameter(Position = 2)][string]$B,

    [string]$Date = (Get-Date -Format 'yyyy-MM-dd'),
    [int]$Tail = 120,
    [int]$TimeoutSec = 300,
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'
$script:HostIp = '172.19.10.153'
$script:PackageRoot = 'D:\application\gfs'

function Get-ProdCredential {
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
    if (-not $pass) {
        throw "Missing credential: set `$env:GFS_PROD_PASS or create $HOME\.gfs\prod-153.cred"
    }
    return New-Object System.Management.Automation.PSCredential(
        $user, (ConvertTo-SecureString $pass -AsPlainText -Force))
}

$script:cred = Get-ProdCredential

function Connect-Share {
    param([string]$Letter)
    $name = "GFS_$Letter"
    if (-not (Get-PSDrive -Name $name -ErrorAction SilentlyContinue)) {
        $root = "\\$($script:HostIp)\$Letter`$"
        New-PSDrive -Name $name -PSProvider FileSystem -Root $root -Credential $script:cred -Scope Global | Out-Null
    }
    return "${name}:"
}

function ConvertTo-RemoteUnc {
    param([string]$RemotePath)
    if ($RemotePath -notmatch '^([A-Za-z]):[\\/](.*)$') {
        throw "Remote path must start with a drive letter, e.g. D:\application\gfs\logs"
    }
    $letter = $Matches[1].ToUpper()
    $rest = $Matches[2]
    return "$(Connect-Share $letter)\$rest"
}

function Test-RemotePort {
    param([int]$Port, [int]$TimeoutMs = 2000)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect($script:HostIp, $Port, $null, $null)
        if ($iar.AsyncWaitHandle.WaitOne($TimeoutMs)) {
            $client.EndConnect($iar)
            return $true
        }
        return $false
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function New-CimConn {
    $option = New-CimSessionOption -Protocol Dcom
    return New-CimSession -ComputerName $script:HostIp -Credential $script:cred -SessionOption $option
}

function Invoke-RemoteCommand {
    param([string]$Command, [int]$TimeoutSec = 300)

    $id = [guid]::NewGuid().ToString('N').Substring(0, 8)
    $batRemote = "C:\Windows\Temp\gfs-agent-$id.bat"
    $outRemote = "C:\Windows\Temp\gfs-agent-$id.log"
    $batLocal = Join-Path $env:TEMP "gfs-agent-$id.bat"
    $outLocal = ConvertTo-RemoteUnc $outRemote

    $body = @(
        '@echo off',
        'set "GFS_QUIET=1"',
        "cd /d $($script:PackageRoot)",
        # cmd /c so a child .bat (bin\stop.bat, bin\status.bat ...) returns here;
        # calling it directly would replace this script and skip the exit marker.
        "cmd /c `"$Command`" > `"$outRemote`" 2>&1 < NUL",
        "echo __EXIT__%ERRORLEVEL%>>`"$outRemote`""
    )
    Set-Content -LiteralPath $batLocal -Value $body -Encoding Default
    Copy-Item -LiteralPath $batLocal -Destination (ConvertTo-RemoteUnc $batRemote) -Force
    Remove-Item -LiteralPath $batLocal -Force

    $session = New-CimConn
    try {
        $r = Invoke-CimMethod -CimSession $session -ClassName Win32_Process -MethodName Create -Arguments @{
            CommandLine = "cmd.exe /c `"$batRemote`""
        }
        if ($r.ReturnValue -ne 0) {
            throw "Win32_Process.Create failed, ReturnValue=$($r.ReturnValue)"
        }
    } finally {
        Remove-CimSession $session
    }

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $text = ''
    $exitCode = $null
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 700
        try {
            if (Test-Path -LiteralPath $outLocal) {
                $text = Get-Content -LiteralPath $outLocal -Raw
                if ($text -match '__EXIT__(-?\d+)') {
                    $exitCode = [int]$Matches[1]
                    $text = $text -replace '(?m)^__EXIT__-?\d+\s*$', ''
                    break
                }
            }
        } catch {
            # file may be locked while cmd writes to it
        }
    }

    if ($null -eq $exitCode) {
        Remove-Item -LiteralPath (ConvertTo-RemoteUnc $batRemote) -Force -ErrorAction SilentlyContinue
        throw "Remote command did not finish within ${TimeoutSec}s: $Command`n--- partial output ---`n$text"
    }
    Remove-Item -LiteralPath (ConvertTo-RemoteUnc $batRemote) -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $outLocal -Force -ErrorAction SilentlyContinue
    return [pscustomobject]@{ ExitCode = $exitCode; Output = $text.TrimEnd() }
}

function Assert-Confirmed {
    param([string]$What)
    if (-not $Yes) {
        throw "Refusing to $What without -Yes (production server)."
    }
}

switch ($Action) {

    'status' {
        $os = $null
        $java = $null
        $session = New-CimConn
        try {
            $os = Get-CimInstance -CimSession $session -ClassName Win32_OperatingSystem |
                Select-Object Caption, Version, LastBootUpTime
            $java = Get-CimInstance -CimSession $session -ClassName Win32_Process -Filter "Name='java.exe'" |
                Select-Object ProcessId, CreationDate, CommandLine
        } finally {
            Remove-CimSession $session
        }
        $code = try {
            (Invoke-WebRequest -Uri "http://$($script:HostIp)/" -UseBasicParsing -TimeoutSec 8).StatusCode
        } catch { "unreachable: $($_.Exception.Message)" }

        [pscustomobject]@{
            Host = $script:HostIp
            OS = $os.Caption
            Version = $os.Version
            BootTime = $os.LastBootUpTime
            AppHttpStatus = $code
            Port80 = Test-RemotePort 80
            Port445 = Test-RemotePort 445
            Port3389 = Test-RemotePort 3389
            JavaPid = ($java.ProcessId -join ',')
            JavaStarted = ($java.CreationDate -join ',')
        } | Format-List
        if ($java) { $java.CommandLine }
    }

    'ls' {
        if (-not $A) { throw "Usage: prod-153.ps1 ls <remoteDir>" }
        Get-ChildItem -LiteralPath (ConvertTo-RemoteUnc $A) |
            ForEach-Object {
                '{0}  {1,12}  {2}' -f $_.LastWriteTime.ToString('yyyy-MM-dd HH:mm'), $_.Length, $_.Name
            }
    }

    'get' {
        if (-not $A -or -not $B) { throw "Usage: prod-153.ps1 get <remoteFile> <localPath>" }
        $source = ConvertTo-RemoteUnc $A
        $target = $B
        if ((Test-Path -LiteralPath $target -PathType Container) -or $target.EndsWith('\') -or $target.EndsWith('/')) {
            $target = Join-Path $target (Split-Path -Leaf $A)
        }
        Copy-Item -LiteralPath $source -Destination $target -Force
        "downloaded: $source -> $target"
    }

    'put' {
        if (-not $A -or -not $B) { throw "Usage: prod-153.ps1 put <localFile> <remoteFile>" }
        $target = ConvertTo-RemoteUnc $B
        $parent = Split-Path -Parent $target
        if (-not (Test-Path -LiteralPath $parent)) {
            throw "Remote directory does not exist: $parent"
        }
        Copy-Item -LiteralPath $A -Destination $target -Force
        "uploaded: $A -> $target"
    }

    'exec' {
        if (-not $A) { throw "Usage: prod-153.ps1 exec ""<command>"" (runs in $($script:PackageRoot))" }
        $r = Invoke-RemoteCommand -Command $A -TimeoutSec $TimeoutSec
        $r.Output
        "exit code: $($r.ExitCode)"
        if ($r.ExitCode -ne 0) { exit $r.ExitCode }
    }

    'logs' {
        $dir = ConvertTo-RemoteUnc "$($script:PackageRoot)\logs"
        $item = Get-ChildItem -LiteralPath $dir -Filter "gfs.$Date*.log" -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $item) { throw "Log not found under $($script:PackageRoot)\logs for $Date" }
        "== $($item.Name) ($([math]::Round($item.Length / 1MB, 1)) MB, last write $($item.LastWriteTime)) =="
        Get-Content -LiteralPath $item.FullName -Tail $Tail
    }

    'restart' {
        Assert-Confirmed 'restart the whole GFS stack (app + Redis + MySQL)'
        $stop = Invoke-RemoteCommand -Command 'bin\stop.bat' -TimeoutSec 300
        $stop.Output
        if ($stop.ExitCode -ne 0) { throw "stop.bat failed with exit code $($stop.ExitCode)" }
        $start = Invoke-RemoteCommand -Command 'bin\start.bat' -TimeoutSec $TimeoutSec
        $start.Output
        if ($start.ExitCode -ne 0) { throw "start.bat failed with exit code $($start.ExitCode)" }
        Start-Sleep -Seconds 5
        "port 80 listening: $(Test-RemotePort 80)"
        $r = Invoke-RemoteCommand -Command 'bin\status.bat' -TimeoutSec 120
        $r.Output
    }

    'deploy-jar' {
        if (-not $A) { throw "Usage: prod-153.ps1 deploy-jar <localJar> [-Yes]" }
        Assert-Confirmed 'replace lib\fs-admin.jar'
        if (-not (Test-Path -LiteralPath $A)) { throw "Local jar not found: $A" }
        $target = ConvertTo-RemoteUnc "$($script:PackageRoot)\lib\fs-admin.jar"
        Copy-Item -LiteralPath $A -Destination $target -Force
        "deployed: $A -> $target (no production backup kept)"
        "next   : prod-153.ps1 restart -Yes"
    }

    'deploy-frontend' {
        if (-not $A) { throw "Usage: prod-153.ps1 deploy-frontend <localDistDir> [-Yes]" }
        Assert-Confirmed 'replace the external frontend directory'
        if (-not (Test-Path -LiteralPath $A -PathType Container)) { throw "Local directory not found: $A" }
        $remoteDir = ConvertTo-RemoteUnc "$($script:PackageRoot)\frontend"
        Remove-Item -LiteralPath (Join-Path $remoteDir '*') -Recurse -Force
        Copy-Item -Path (Join-Path $A '*') -Destination $remoteDir -Recurse -Force
        "deployed: $A -> $remoteDir (no production backup kept)"
        "check   : index.html present = $(Test-Path -LiteralPath (Join-Path $remoteDir 'index.html'))"
    }
}
