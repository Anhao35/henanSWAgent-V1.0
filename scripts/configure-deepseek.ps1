$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$EnvFile = Join-Path $ProjectRoot '.env'

$secureKey = Read-Host '请输入 DeepSeek API Key（输入内容不会回显）' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
try {
    $apiKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
}
if ([string]::IsNullOrWhiteSpace($apiKey)) { throw 'API Key 不能为空' }

$lines = if (Test-Path -LiteralPath $EnvFile) { [Collections.Generic.List[string]](Get-Content -LiteralPath $EnvFile) } else { [Collections.Generic.List[string]]::new() }
function Set-EnvValue([string]$Name, [string]$Value) {
    for ($index = 0; $index -lt $lines.Count; $index++) {
        if ($lines[$index] -match "^\s*$([regex]::Escape($Name))=") {
            $lines[$index] = "$Name=$Value"
            return
        }
    }
    $lines.Add("$Name=$Value")
}

Set-EnvValue 'DEEPSEEK_BASE_URL' 'https://api.deepseek.com'
Set-EnvValue 'DEEPSEEK_API_KEY' $apiKey
Set-EnvValue 'DEEPSEEK_MODEL' 'deepseek-v4-flash'
Set-Content -LiteralPath $EnvFile -Value $lines -Encoding utf8
$apiKey = $null
Write-Host 'DeepSeek 配置已安全写入本机 .env。请重启 Spring Boot 后端。' -ForegroundColor Green
