$ErrorActionPreference = 'Continue'

function Show-UrlStatus {
    param([string]$Name, [string]$Url)
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
        Write-Host "[UP]   $Name ($($response.StatusCode)) $Url"
    }
    catch {
        Write-Host "[DOWN] $Name $Url -- $($_.Exception.Message)"
    }
}

Show-UrlStatus -Name 'Dify' -Url 'http://127.0.0.1:8081/signin'
Show-UrlStatus -Name 'ATT&CK Bridge' -Url 'http://127.0.0.1:8011/health'
Show-UrlStatus -Name 'Spring Boot backend' -Url 'http://127.0.0.1:8088/actuator/health'
Show-UrlStatus -Name 'Vite frontend' -Url 'http://127.0.0.1:5173/'
Show-UrlStatus -Name 'Frontend auth proxy' -Url 'http://127.0.0.1:5173/api/auth/csrf'

Write-Host ''
Write-Host 'Platform infrastructure containers:'
foreach ($name in @(
    'henan-sec-agent-mysql',
    'henan-sec-agent-redis',
    'henan-sec-agent-minio',
    'henan-sec-agent-mailpit'
)) {
    $state = docker inspect $name --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "[container] $name -> $state"
    }
    else {
        Write-Host "[missing]   $name"
    }
}

$sirRoot = 'D:\dify-agent-dev\security_ioc_router'
$sir = Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^python(\.exe)?$' -and
    $_.CommandLine -like "*$sirRoot*" -and
    $_.CommandLine -match '-m\s+main'
} | Select-Object -First 1
if ($sir) {
    Write-Host "[UP]   SIR plugin process PID $($sir.ProcessId)"
}
else {
    Write-Host '[DOWN] SIR plugin process'
}
