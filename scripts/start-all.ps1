$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$WebRoot = Join-Path $ProjectRoot 'web'
$DifyRoot = 'D:\software\dify-latest\docker'
$SirRoot = 'D:\dify-agent-dev\security_ioc_router'
$AttackRoot = 'D:\attack-http-bridge'

function Test-HttpUrl {
    param([string]$Url, [int]$TimeoutSeconds = 4)
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSeconds
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 500
    }
    catch {
        return $false
    }
}

function Wait-HttpUrl {
    param([string]$Name, [string]$Url, [int]$Attempts = 90)
    for ($i = 0; $i -lt $Attempts; $i++) {
        if (Test-HttpUrl -Url $Url) {
            Write-Host "[ready] $Name -> $Url"
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "$Name did not become ready: $Url"
}

function Get-ListeningPid {
    param([int]$Port)
    $row = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($row) { return [int]$row.OwningProcess }
    return 0
}

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Desktop is not ready. Start Docker Desktop, wait for the engine, then run this script again.'
}

if (-not (Test-Path -LiteralPath $DifyRoot)) { throw "Missing Dify directory: $DifyRoot" }
if (-not (Test-Path -LiteralPath $SirRoot)) { throw "Missing SIR directory: $SirRoot" }
if (-not (Test-Path -LiteralPath $AttackRoot)) { throw "Missing ATT&CK Bridge directory: $AttackRoot" }

Write-Host '[1/6] Starting Dify containers...'
docker compose -f (Join-Path $DifyRoot 'docker-compose.yaml') up -d
if ($LASTEXITCODE -ne 0) {
    docker compose -f (Join-Path $DifyRoot 'docker-compose.yml') up -d
    if ($LASTEXITCODE -ne 0) { throw 'Dify docker compose failed.' }
}
Wait-HttpUrl -Name 'Dify' -Url 'http://127.0.0.1:8081/signin'

Write-Host '[2/6] Starting platform MySQL, Redis, MinIO and Mailpit...'
docker compose -f (Join-Path $ProjectRoot 'docker-compose.yml') up -d
if ($LASTEXITCODE -ne 0) { throw 'Platform infrastructure docker compose failed.' }

Write-Host '[3/6] Starting SIR plugin process when needed...'
$sirRunning = Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^python(\.exe)?$' -and
    $_.CommandLine -like "*$SirRoot*" -and
    $_.CommandLine -match '-m\s+main'
} | Select-Object -First 1
if (-not $sirRunning) {
    $sirPython = Join-Path $SirRoot '.venv\Scripts\python.exe'
    if (-not (Test-Path -LiteralPath $sirPython)) { throw "Missing SIR Python: $sirPython" }
    Start-Process -FilePath $sirPython -ArgumentList @('-m', 'main') -WorkingDirectory $SirRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $SirRoot 'sir-live.stdout.log') `
        -RedirectStandardError (Join-Path $SirRoot 'sir-live.stderr.log') | Out-Null
    Start-Sleep -Seconds 2
}
else {
    Write-Host "[skip] SIR is already running (PID $($sirRunning.ProcessId))."
}

Write-Host '[4/6] Starting ATT&CK HTTP Bridge when needed...'
if (-not (Test-HttpUrl -Url 'http://127.0.0.1:8011/health')) {
    $attackPid = Get-ListeningPid -Port 8011
    if ($attackPid -ne 0) {
        throw "Port 8011 is occupied by PID $attackPid but the ATT&CK health check failed."
    }
    $attackPython = Join-Path $AttackRoot '.venv\Scripts\python.exe'
    if (-not (Test-Path -LiteralPath $attackPython)) { throw "Missing ATT&CK Python: $attackPython" }
    Start-Process -FilePath $attackPython `
        -ArgumentList @('-m', 'uvicorn', 'app.main:app', '--host', '0.0.0.0', '--port', '8011') `
        -WorkingDirectory $AttackRoot -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $AttackRoot 'uvicorn-live.stdout.log') `
        -RedirectStandardError (Join-Path $AttackRoot 'uvicorn-live.stderr.log') | Out-Null
}
Wait-HttpUrl -Name 'ATT&CK Bridge' -Url 'http://127.0.0.1:8011/health'

Write-Host '[5/6] Starting Spring Boot backend when needed...'
if (-not (Test-HttpUrl -Url 'http://127.0.0.1:8088/actuator/health')) {
    $backendPid = Get-ListeningPid -Port 8088
    if ($backendPid -ne 0) {
        throw "Port 8088 is occupied by PID $backendPid but the backend health check failed."
    }
    Start-Process -FilePath 'powershell.exe' `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'start-backend.ps1')) `
        -WorkingDirectory $ProjectRoot -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $ProjectRoot 'backend-live.stdout.log') `
        -RedirectStandardError (Join-Path $ProjectRoot 'backend-live.stderr.log') | Out-Null
}
Wait-HttpUrl -Name 'Spring Boot backend' -Url 'http://127.0.0.1:8088/actuator/health'

Write-Host '[6/6] Starting the canonical Vite frontend when needed...'
$frontendPid = Get-ListeningPid -Port 5173
if ($frontendPid -ne 0) {
    $frontendProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$frontendPid"
    if ($frontendProcess.CommandLine -notlike "*$WebRoot*") {
        throw "Port 5173 is owned by another project (PID $frontendPid): $($frontendProcess.CommandLine)"
    }
    Write-Host "[skip] Canonical frontend is already running (PID $frontendPid)."
}
else {
    Start-Process -FilePath 'npm.cmd' -ArgumentList @('run', 'dev') -WorkingDirectory $WebRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $WebRoot 'vite-live.stdout.log') `
        -RedirectStandardError (Join-Path $WebRoot 'vite-live.stderr.log') | Out-Null
}
Wait-HttpUrl -Name 'Vite frontend' -Url 'http://127.0.0.1:5173/'
Wait-HttpUrl -Name 'Frontend auth proxy' -Url 'http://127.0.0.1:5173/api/auth/csrf'

Write-Host ''
Write-Host 'All services are ready.'
Write-Host 'Platform: http://127.0.0.1:5173/login'
Write-Host 'Dify:    http://127.0.0.1:8081/signin'
Write-Host 'Mailpit: http://127.0.0.1:18025'
Write-Host 'MinIO:   http://127.0.0.1:19001'
