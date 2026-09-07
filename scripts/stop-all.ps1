$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$DifyRoot = 'D:\software\dify-latest\docker'
$SirRoot = 'D:\dify-agent-dev\security_ioc_router'
$AttackRoot = 'D:\attack-http-bridge'

function Stop-ExpectedPortProcess {
    param([int]$Port, [string]$Name, [string]$CommandPattern)
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $listener) { return }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    if ($process.CommandLine -notmatch $CommandPattern) {
        Write-Warning "$Name 端口 $Port 由其他进程占用，未停止：$($process.CommandLine)"
        return
    }
    Stop-Process -Id $process.ProcessId
    Write-Host "[stopped] $Name (PID $($process.ProcessId))"
}

Stop-ExpectedPortProcess -Port 5173 -Name 'Vite frontend' -CommandPattern ([regex]::Escape((Join-Path $ProjectRoot 'web')))
Stop-ExpectedPortProcess -Port 8088 -Name 'Spring Boot backend' -CommandPattern 'henan-sec-agent-server'
Stop-ExpectedPortProcess -Port 8011 -Name 'ATT&CK Bridge' -CommandPattern 'uvicorn.*app\.main:app'

Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^python(\.exe)?$' -and $_.CommandLine -like "*$SirRoot*" -and $_.CommandLine -match '-m\s+main'
} | ForEach-Object { Stop-Process -Id $_.ProcessId; Write-Host "[stopped] SIR plugin (PID $($_.ProcessId))" }

docker compose -f (Join-Path $ProjectRoot 'docker-compose.yml') stop
if (Test-Path $DifyRoot) {
    $compose = if (Test-Path (Join-Path $DifyRoot 'docker-compose.yaml')) { 'docker-compose.yaml' } else { 'docker-compose.yml' }
    docker compose -f (Join-Path $DifyRoot $compose) stop
}
Write-Host '已停止所有项目服务；Docker 数据卷已保留。'
