param(
    [string]$SourceRoot = 'D:\henanSwAgent-260904\RAG知识库',
    [string]$OutputRoot = 'D:\henanSwAgent-260904\RAG试验库-v1',
    [int]$MaxArxivAbstracts = 600,
    [int]$AbstractsPerFile = 100
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $SourceRoot)) { throw "找不到原始知识库：$SourceRoot" }
if ($MaxArxivAbstracts -lt 1 -or $MaxArxivAbstracts -gt 3000) { throw 'MaxArxivAbstracts 必须在 1 到 3000 之间' }
if ($AbstractsPerFile -lt 20 -or $AbstractsPerFile -gt 200) { throw 'AbstractsPerFile 必须在 20 到 200 之间' }

New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
$generatedRoot = Join-Path $OutputRoot 'generated'
New-Item -ItemType Directory -Path $generatedRoot -Force | Out-Null

$manifest = [System.Collections.Generic.List[object]]::new()
function Add-ManifestFile([System.IO.FileInfo]$File, [string]$Collection, [string]$Source, [int]$Priority) {
    $manifest.Add([pscustomobject]@{
        file_path = $File.FullName
        file_name = $File.Name
        collection = $Collection
        source = $Source
        priority = $Priority
        size_bytes = $File.Length
        sha256 = (Get-FileHash -LiteralPath $File.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    })
}

# 先选权威规范、官方指南、少量教材和企业报告。HTML/JSON 与同源 PDF 重复时暂不进入试验库。
$curated = @(
    @{ dir = '05_教材与书籍'; collection = '安全基础与教材'; source = '公开教材'; priority = 3 },
    @{ dir = '07_官方权威指南与处置建议'; collection = '官方指南'; source = 'NIST/CISA/国家标准'; priority = 1 },
    @{ dir = '08_企业安全报告'; collection = '行业报告'; source = '安全厂商与行业组织'; priority = 3 },
    @{ dir = '10_法律法规与政策文件'; collection = '法律法规'; source = '政府及人大公开文件'; priority = 1 }
)
foreach ($entry in $curated) {
    $dir = Join-Path $SourceRoot $entry.dir
    if (-not (Test-Path -LiteralPath $dir)) { continue }
    Get-ChildItem -LiteralPath $dir -Recurse -File |
        Where-Object { $_.Extension.ToLowerInvariant() -in @('.pdf', '.txt', '.md', '.docx') } |
        ForEach-Object { Add-ManifestFile $_ $entry.collection $entry.source $entry.priority }
}

# RFC 全库有九千余份，试验阶段只选网络安全协议核心文档。
$rfcNumbers = @('4251','4253','5246','5280','6749','6819','6962','7519','7636','8446','8555','8705','8725','9000','9110','9112','9113','9207','9325')
$rfcDir = Join-Path $SourceRoot '06_标准与规范\IETF_RFC'
foreach ($number in $rfcNumbers) {
    $candidate = Join-Path $rfcDir ("rfc$number.txt")
    if (Test-Path -LiteralPath $candidate) { Add-ManifestFile (Get-Item -LiteralPath $candidate) '网络安全协议标准' 'IETF RFC Editor' 2 }
}

# arXiv 先使用元数据和摘要，避免本机解析 23 GB PDF。按安全运营相关词筛选，保持原索引的新旧顺序。
$arxivIndex = Join-Path $SourceRoot '00_下载日志与索引\arxiv_index.jsonl'
$pattern = '(?i)(security operations|\bSOC\b|incident response|threat intelligence|malware|phishing|ransomware|intrusion detection|advanced persistent threat|\bAPT\b|network attack|vulnerability detection|attack detection|threat detection|digital forensics|cybersecurity|CVE|ATT&CK)'
$selected = [System.Collections.Generic.List[object]]::new()
if (Test-Path -LiteralPath $arxivIndex) {
    foreach ($line in [System.IO.File]::ReadLines($arxivIndex)) {
        if ($selected.Count -ge $MaxArxivAbstracts) { break }
        try { $item = $line | ConvertFrom-Json } catch { continue }
        if (([string]$item.title + ' ' + [string]$item.abstract) -match $pattern) { $selected.Add($item) }
    }
}

$batchCount = [Math]::Ceiling($selected.Count / [double]$AbstractsPerFile)
for ($batch = 0; $batch -lt $batchCount; $batch++) {
    $start = $batch * $AbstractsPerFile
    $end = [Math]::Min($selected.Count - 1, $start + $AbstractsPerFile - 1)
    $builder = [System.Text.StringBuilder]::new()
    [void]$builder.AppendLine('# arXiv 网络安全研究摘要（试验索引）')
    [void]$builder.AppendLine()
    [void]$builder.AppendLine('> 本文件由公开 arXiv 元数据生成，仅用于主题检索。回答时应注明论文属于预印本，并通过原文复核。')
    [void]$builder.AppendLine()
    for ($i = $start; $i -le $end; $i++) {
        $item = $selected[$i]
        [void]$builder.AppendLine("## $($item.title)")
        [void]$builder.AppendLine()
        [void]$builder.AppendLine("- arXiv ID：$($item.id)")
        [void]$builder.AppendLine("- 作者：$([string]::Join(', ', @($item.authors)))")
        [void]$builder.AppendLine("- 发布时间：$($item.published)")
        [void]$builder.AppendLine("- 分类：$([string]::Join(', ', @($item.categories)))")
        [void]$builder.AppendLine("- 原文：$($item.pdf_url)")
        [void]$builder.AppendLine()
        [void]$builder.AppendLine([string]$item.abstract)
        [void]$builder.AppendLine()
    }
    $output = Join-Path $generatedRoot ('arxiv-security-abstracts-{0:d2}.md' -f ($batch + 1))
    [System.IO.File]::WriteAllText($output, $builder.ToString(), [System.Text.UTF8Encoding]::new($false))
    Add-ManifestFile (Get-Item -LiteralPath $output) '安全研究摘要' 'arXiv cs.CR metadata' 4
}

$manifestPath = Join-Path $OutputRoot 'rag-pilot-manifest.csv'
$manifest | Sort-Object priority, collection, file_name | Export-Csv -LiteralPath $manifestPath -NoTypeInformation -Encoding UTF8
$summary = [ordered]@{
    schema_version = 'rag-pilot-manifest/v1'
    generated_at = (Get-Date).ToUniversalTime().ToString('o')
    source_root = $SourceRoot
    file_count = $manifest.Count
    total_bytes = ($manifest | Measure-Object size_bytes -Sum).Sum
    arxiv_abstract_count = $selected.Count
    collections = @($manifest | Group-Object collection | Sort-Object Name | ForEach-Object { [ordered]@{ name = $_.Name; files = $_.Count } })
    note = '清单只引用原始文件；未复制 PDF。结构化 CVE/ATT&CK/CWE/KEV 数据不进入本试验向量库。'
}
$summaryPath = Join-Path $OutputRoot 'rag-pilot-summary.json'
[System.IO.File]::WriteAllText($summaryPath, ($summary | ConvertTo-Json -Depth 5), [System.Text.UTF8Encoding]::new($false))

Write-Output "MANIFEST=$manifestPath"
Write-Output "SUMMARY=$summaryPath"
Write-Output "FILES=$($manifest.Count)"
Write-Output "TOTAL_BYTES=$(($manifest | Measure-Object size_bytes -Sum).Sum)"
Write-Output "ARXIV_ABSTRACTS=$($selected.Count)"
