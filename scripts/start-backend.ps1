$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot

foreach ($fileName in @('.env', '.env.workflows')) {
    $envFile = Join-Path $ProjectRoot $fileName
    if (-not (Test-Path -LiteralPath $envFile)) { continue }
    Get-Content -LiteralPath $envFile | ForEach-Object {
        if ($_ -match '^\s*([^#=\s]+)\s*=\s*(.*)\s*$') {
            $key = $matches[1]
            $value = $matches[2].Trim('"').Trim("'")
            [Environment]::SetEnvironmentVariable($key, $value, 'Process')
        }
    }
}

docker compose -f (Join-Path $ProjectRoot 'docker-compose.yml') up -d
Set-Location (Join-Path $ProjectRoot 'backend')
mvn spring-boot:run

