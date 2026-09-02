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
$jarPath = Join-Path (Get-Location) 'target\henan-sec-agent-server-1.0.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jarPath)) {
    mvn -q test package
    if ($LASTEXITCODE -ne 0) { throw '后端构建失败' }
}

# 直接运行可执行 JAR，避免 Maven spring-boot:run 在包含中文的 Windows
# 路径下生成错误的派生 JVM classpath。
java -jar $jarPath
