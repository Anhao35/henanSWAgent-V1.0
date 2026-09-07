$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BridgeRoot = 'D:\attack-http-bridge'
$BridgePython = Join-Path $BridgeRoot '.venv\Scripts\python.exe'

Push-Location $BridgeRoot
try {
    & $BridgePython -m unittest discover -s tests -v
    if ($LASTEXITCODE -ne 0) { throw 'Bridge unit regression failed.' }

    & $BridgePython (Join-Path $BridgeRoot 'tests\live_smoke.py')
    if ($LASTEXITCODE -ne 0) { throw 'Bridge live smoke failed.' }
}
finally {
    Pop-Location
}

& python (Join-Path $PSScriptRoot 'test-local-attack-v2.py')
if ($LASTEXITCODE -ne 0) { throw 'LocalAttackMCP V2 DSL check failed.' }

& python (Join-Path $PSScriptRoot 'test-attack-v24.py')
if ($LASTEXITCODE -ne 0) { throw 'Main V2.4 DSL check failed.' }

Write-Host 'PASS: all ATT&CK V2.4 regressions'
