$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot

foreach ($fileName in @('.env', '.env.workflows')) {
    $envFile = Join-Path $ProjectRoot $fileName
    if (-not (Test-Path -LiteralPath $envFile)) { continue }
    Get-Content -LiteralPath $envFile | ForEach-Object {
        if ($_ -match '^\s*([^#=\s]+)\s*=\s*(.*)\s*$') {
            $envName = $matches[1]
            $envValue = $matches[2].Trim('"').Trim("'")
            [Environment]::SetEnvironmentVariable($envName, $envValue, 'Process')
        }
    }
}

docker compose -f (Join-Path $ProjectRoot 'docker-compose.yml') up -d
Set-Location (Join-Path $ProjectRoot 'backend')
$jarPath = Join-Path (Get-Location) 'target\henan-sec-agent-server-1.0.0-SNAPSHOT.jar'
$latestSource = Get-ChildItem -LiteralPath (Join-Path (Get-Location) 'src'), (Join-Path (Get-Location) 'pom.xml') -File -Recurse |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not (Test-Path -LiteralPath $jarPath) -or $latestSource.LastWriteTime -gt (Get-Item -LiteralPath $jarPath).LastWriteTime) {
    & mvn.cmd -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw '后端构建失败' }
}

# 当前目录已经是 backend；向 Java 传递相对路径，避免 Windows PowerShell 5.1
# 将包含中文的绝对 JAR 路径按旧代码页传递后静默退出。
Write-Host '[backend] Starting Spring Boot on port 8088...'
& java.exe -jar 'target\henan-sec-agent-server-1.0.0-SNAPSHOT.jar'
