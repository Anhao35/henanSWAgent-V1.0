param(
    [string]$Query,
    [ValidateSet('dual', 'semantic_search', 'full_text_search')]
    [string]$Mode = 'dual',
    [ValidateRange(1, 20)]
    [int]$TopK = 5
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $ProjectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) { throw '项目根目录缺少 .env' }

$settings = @{}
Get-Content -LiteralPath $envFile -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*([^#=\s]+)\s*=\s*(.*)\s*$') {
        $settings[$matches[1]] = $matches[2].Trim('"').Trim("'")
    }
}

$baseUrl = $settings['DIFY_DATASET_BASE_URL']
$apiKey = $settings['DIFY_DATASET_API_KEY']
$datasetId = $settings['DIFY_RAG_PILOT_DATASET_ID']
if (-not $baseUrl) { $baseUrl = 'http://127.0.0.1:8081/v1' }
if (-not $apiKey) { throw '缺少 DIFY_DATASET_API_KEY' }
if (-not $datasetId) { throw '缺少 DIFY_RAG_PILOT_DATASET_ID' }

$headers = @{
    Authorization = "Bearer $apiKey"
    'Content-Type' = 'application/json'
}

$documentNames = @{}
$page = 1
do {
    $documentPage = Invoke-RestMethod -Method Get `
        -Uri ($baseUrl.TrimEnd('/') + "/datasets/$datasetId/documents?page=$page&limit=100") `
        -Headers $headers -TimeoutSec 30
    foreach ($document in @($documentPage.data)) {
        $documentNames[[string]$document.id] = [string]$document.name
    }
    $page++
} while ($documentPage.has_more)

function Invoke-Retrieval {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$SearchMethod,
        [int]$Limit = 10
    )

    $body = @{
        query = $Text
        retrieval_model = @{
            search_method = $SearchMethod
            reranking_enable = $false
            top_k = $Limit
            score_threshold_enabled = $false
        }
    } | ConvertTo-Json -Depth 6

    $response = Invoke-RestMethod -Method Post `
        -Uri ($baseUrl.TrimEnd('/') + "/datasets/$datasetId/retrieve") `
        -Headers $headers -Body $body -TimeoutSec 60

    $rank = 0
    return @($response.records) | ForEach-Object {
        $rank++
        [pscustomobject]@{
            Key = "$($_.segment.document_id):$($_.segment.id)"
            Rank = $rank
            SearchMethod = $SearchMethod
            Score = [double]$_.score
            Document = $documentNames[[string]$_.segment.document_id]
            Content = [string]$_.segment.content
        }
    }
}

function ConvertTo-FullTextQuery {
    param([Parameter(Mandatory)][string]$Text)

    # Dify 全文检索更适合关键词而不是完整问句。这里只做确定性的轻量清洗，
    # 正式接入主流程后可由意图层生成更完整的检索词与同义词。
    $keywordText = $Text -replace '[，。！？；：、,.!?;:()（）\[\]【】]', ' '
    $keywordText = $keywordText -replace '应当|应该|需要|可以|哪些|什么|如何|怎么|是否|至少|多长时间|多少|请问|有关|相关|规定|要求', ' '
    $keywordText = $keywordText -replace '的', ' '
    $keywordText = ($keywordText -replace '\s+', ' ').Trim()
    if (-not $keywordText) { return $Text }
    return $keywordText
}

function Get-Results {
    param([Parameter(Mandatory)][string]$Text)

    if ($Mode -ne 'dual') {
        $retrievalText = if ($Mode -eq 'full_text_search') { ConvertTo-FullTextQuery -Text $Text } else { $Text }
        return @(Invoke-Retrieval -Text $retrievalText -SearchMethod $Mode -Limit $TopK)
    }

    # 双路召回并用 RRF 合并；法规条款偏向全文检索，概念性问题偏向语义检索。
    $all = @()
    $all += Invoke-Retrieval -Text $Text -SearchMethod 'semantic_search' -Limit ([Math]::Max(10, $TopK))
    $fullTextQuery = ConvertTo-FullTextQuery -Text $Text
    $all += Invoke-Retrieval -Text $fullTextQuery -SearchMethod 'full_text_search' -Limit ([Math]::Max(10, $TopK))

    $mergedChunks = @($all | Group-Object Key | ForEach-Object {
        $best = $_.Group | Sort-Object Score -Descending | Select-Object -First 1
        $rrf = ($_.Group | ForEach-Object { 1.0 / (60 + $_.Rank) } | Measure-Object -Sum).Sum
        [pscustomobject]@{
            RrfScore = [double]$rrf
            Score = [double]$best.Score
            SearchMethod = ($_.Group.SearchMethod | Sort-Object -Unique) -join '+'
            Document = $best.Document
            Content = $best.Content
        }
    })

    # 同一长文档通常会返回多个相邻分段。测试时保留每个来源的最佳分段，
    # 防止一个大 PDF 挤占全部 TopK，也更方便人工核对来源覆盖率。
    return @($mergedChunks | Group-Object Document | ForEach-Object {
        $_.Group | Sort-Object RrfScore -Descending | Select-Object -First 1
    } | Sort-Object RrfScore -Descending | Select-Object -First $TopK)
}

if ($Query) {
    Write-Host "`n问题：$Query" -ForegroundColor Cyan
    Write-Host "模式：$Mode；TopK：$TopK；知识库文档数：$($documentNames.Count)`n"
    $position = 0
    Get-Results -Text $Query | ForEach-Object {
        $position++
        $snippet = ($_.Content -replace '\s+', ' ').Trim()
        if ($snippet.Length -gt 150) { $snippet = $snippet.Substring(0, 150) + '...' }
        [pscustomobject]@{
            Rank = $position
            Document = $_.Document
            Route = $_.SearchMethod
            Score = [Math]::Round($_.Score, 4)
            Snippet = $snippet
        }
    } | Format-Table -Wrap -AutoSize
    exit 0
}

$cases = @(
    @{ Question = '关键信息基础设施运营者应当履行哪些网络安全保护义务？'; Expected = '关键信息基础设施'; Evidence = '建立健全网络安全保护制度' },
    @{ Question = '处理敏感个人信息需要满足什么条件？'; Expected = '个人信息保护法'; Evidence = '特定的目的和充分的必要性' },
    @{ Question = '数据处理者应当建立哪些数据安全管理制度？'; Expected = '数据安全法'; Evidence = '全流程数据安全管理制度' },
    @{ Question = '网络运营者的网络日志应至少留存多长时间？'; Expected = '网络安全法'; Evidence = '留存不少于六个月' },
    @{ Question = '如何部署抗钓鱼的多因素认证？'; Expected = 'phishing-resistant-mfa'; Evidence = 'phishing resistant multi-factor authentication' }
)

$summary = foreach ($case in $cases) {
    $results = @(Get-Results -Text $case.Question)
    $match = $results | Where-Object { $_.Document -like "*$($case.Expected)*" } | Select-Object -First 1
    $evidenceMatch = $results | Where-Object { $_.Content -like "*$($case.Evidence)*" } | Select-Object -First 1
    $rank = if ($match) { [array]::IndexOf($results, $match) + 1 } else { $null }
    [pscustomobject]@{
        Question = $case.Question
        ExpectedSource = $case.Expected
        SourceHit = [bool]$match
        SourceRank = $rank
        EvidenceHit = [bool]$evidenceMatch
        TopSource = if ($results.Count -gt 0) { $results[0].Document } else { '' }
    }
}

$sourceHitCount = @($summary | Where-Object SourceHit).Count
$evidenceHitCount = @($summary | Where-Object EvidenceHit).Count
$summary | Format-Table -Wrap -AutoSize
Write-Host "`n基准结果：来源命中 $sourceHitCount/$($summary.Count)，精确证据命中 $evidenceHitCount/$($summary.Count)，Top-$TopK（模式：$Mode）" -ForegroundColor $(if ($evidenceHitCount -eq $summary.Count) { 'Green' } else { 'Yellow' })
if ($evidenceHitCount -lt $summary.Count) {
    Write-Host '来源已找到但精确条款未必进入返回分段，代表召回参数或文档切分仍需优化。' -ForegroundColor Yellow
    exit 2
}
