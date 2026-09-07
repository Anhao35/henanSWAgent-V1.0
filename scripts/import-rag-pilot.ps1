param(
    [string]$ManifestPath = 'D:\henanSwAgent-260904\RAG试验库-v1\rag-pilot-manifest.csv',
    [int]$MaxPriority = 2,
    [int]$MaxFiles = 20
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $ProjectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) { throw '项目根目录缺少 .env' }

$settings = @{}
Get-Content -LiteralPath $envFile -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*([^#=\s]+)\s*=\s*(.*)\s*$') { $settings[$matches[1]] = $matches[2].Trim('"').Trim("'") }
}
$baseUrl = $settings['DIFY_DATASET_BASE_URL']
$apiKey = $settings['DIFY_DATASET_API_KEY']
$datasetId = $settings['DIFY_RAG_PILOT_DATASET_ID']
if (-not $baseUrl) { $baseUrl = 'http://127.0.0.1:8081/v1' }
if (-not $apiKey) { throw '请先在 Dify“知识库 -> API”生成知识库 API Key，并写入 .env 的 DIFY_DATASET_API_KEY；不要使用应用 API Key' }
if (-not $datasetId) { throw '缺少 DIFY_RAG_PILOT_DATASET_ID' }
if (-not (Test-Path -LiteralPath $ManifestPath)) { throw "找不到试验清单：$ManifestPath" }
if ($MaxFiles -lt 1 -or $MaxFiles -gt 100) { throw 'MaxFiles 必须在 1 到 100 之间，防止误触发全量导入' }

$headers = @{ Authorization = "Bearer $apiKey" }
$existing = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$page = 1
do {
    $list = Invoke-RestMethod -Method Get -Uri ($baseUrl.TrimEnd('/') + "/datasets/$datasetId/documents?page=$page&limit=100") -Headers $headers -TimeoutSec 30
    foreach ($document in @($list.data)) { [void]$existing.Add([string]$document.name) }
    $page++
} while ($list.has_more)

$files = Import-Csv -LiteralPath $ManifestPath |
    Where-Object { [int]$_.priority -le $MaxPriority -and -not $existing.Contains($_.file_name) } |
    Sort-Object @{ Expression = { [int]$_.priority } }, collection, file_name |
    Select-Object -First $MaxFiles

$config = [ordered]@{
    indexing_technique = 'high_quality'
    doc_form = 'text_model'
    doc_language = 'Chinese'
    process_rule = @{ mode = 'automatic' }
    embedding_model = 'text-embedding-v3'
    embedding_model_provider = 'langgenius/tongyi/tongyi'
} | ConvertTo-Json -Depth 5 -Compress

$success = 0
$failed = 0
foreach ($entry in $files) {
    if (-not (Test-Path -LiteralPath $entry.file_path -PathType Leaf)) {
        Write-Warning "文件不存在，跳过：$($entry.file_path)"
        $failed++
        continue
    }
    try {
        $requestParams = @{
            Method = 'Post'
            Uri = $baseUrl.TrimEnd('/') + "/datasets/$datasetId/document/create-by-file"
            Headers = $headers
            Form = @{ data = $config; file = Get-Item -LiteralPath $entry.file_path }
            TimeoutSec = 180
        }
        $response = Invoke-RestMethod @requestParams
        $success++
        Write-Output ("UPLOADED`t" + $entry.file_name + "`tbatch=" + $response.batch)
    } catch {
        $failed++
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 'NETWORK' }
        Write-Warning ("FAILED`t" + $entry.file_name + "`tstatus=" + $status)
    }
}

Write-Output "IMPORT_SUMMARY success=$success failed=$failed skipped_existing=$($existing.Count)"
if ($failed -gt 0) { exit 2 }
