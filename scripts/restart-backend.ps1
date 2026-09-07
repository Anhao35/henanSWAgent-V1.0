$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$listener = Get-NetTCPConnection -LocalPort 8088 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listener) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    if ($process.Name -ne 'java.exe' -or $process.CommandLine -notmatch 'henan-sec-agent-server') {
        throw "端口 8088 被非本项目进程占用，拒绝停止：PID $($process.ProcessId) $($process.CommandLine)"
    }
    Stop-Process -Id $process.ProcessId
    for ($i = 0; $i -lt 20 -and (Get-NetTCPConnection -LocalPort 8088 -State Listen -ErrorAction SilentlyContinue); $i++) {
        Start-Sleep -Milliseconds 250
    }
}

Set-Location (Join-Path $ProjectRoot 'backend')
& mvn.cmd -q -DskipTests package
if ($LASTEXITCODE -ne 0) { throw '后端构建失败' }

Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'start-backend.ps1')) -WorkingDirectory $ProjectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $ProjectRoot 'backend-live.stdout.log') -RedirectStandardError (Join-Path $ProjectRoot 'backend-live.stderr.log') | Out-Null

for ($i = 0; $i -lt 60; $i++) {
    try {
        $health = Invoke-RestMethod 'http://127.0.0.1:8088/actuator/health' -TimeoutSec 2
        if ($health.status -eq 'UP') { Write-Host '[ready] Spring Boot backend -> http://127.0.0.1:8088'; exit 0 }
    } catch {}
    Start-Sleep -Seconds 1
}
Get-Content (Join-Path $ProjectRoot 'backend-live.stderr.log') -Tail 100 -ErrorAction SilentlyContinue
throw '后端在 60 秒内未就绪'
