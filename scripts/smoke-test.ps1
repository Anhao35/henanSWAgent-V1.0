$ErrorActionPreference = 'Stop'
$BaseUrl = 'http://127.0.0.1:8088'
$Suffix = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()

function New-ApiSession {
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $csrf = Invoke-RestMethod -Uri "$BaseUrl/api/auth/csrf" -WebSession $session
    return @{ Session = $session; Headers = @{ 'X-XSRF-TOKEN' = $csrf.token } }
}

function Invoke-PostJson($context, $path, $body) {
    return Invoke-RestMethod -Uri ($BaseUrl + $path) -Method Post -WebSession $context.Session `
        -Headers $context.Headers -ContentType 'application/json' -Body ($body | ConvertTo-Json)
}

function Invoke-PatchJson($context, $path, $body) {
    return Invoke-RestMethod -Uri ($BaseUrl + $path) -Method Patch -WebSession $context.Session `
        -Headers $context.Headers -ContentType 'application/json' -Body ($body | ConvertTo-Json)
}

$health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health"
$admin = New-ApiSession
Invoke-PostJson $admin '/api/auth/login' @{ login = 'admin'; password = 'Admin@123456' } | Out-Null

$userAName = "smoke_a_$Suffix"
$userBName = "smoke_b_$Suffix"
$userA = New-ApiSession
$userB = New-ApiSession
Invoke-PostJson $userA '/api/auth/register' @{ username = $userAName; displayName = '隔离测试甲'; password = 'Secure@12345'; email = "$userAName@test.local"; phone = ''; organizationCode = 'HERCERT' } | Out-Null
Invoke-PostJson $userB '/api/auth/register' @{ username = $userBName; displayName = '隔离测试乙'; password = 'Secure@12345'; email = "$userBName@test.local"; phone = ''; organizationCode = 'HERCERT' } | Out-Null

$users = Invoke-RestMethod -Uri "$BaseUrl/api/admin/users" -WebSession $admin.Session
$recordA = $users | Where-Object username -eq $userAName
$recordB = $users | Where-Object username -eq $userBName
Invoke-PatchJson $admin "/api/admin/users/$($recordA.id)/status" @{ status = 'ACTIVE' } | Out-Null
Invoke-PatchJson $admin "/api/admin/users/$($recordB.id)/status" @{ status = 'ACTIVE' } | Out-Null

Invoke-PostJson $userA '/api/auth/login' @{ login = $userAName; password = 'Secure@12345' } | Out-Null
Invoke-PostJson $userB '/api/auth/login' @{ login = $userBName; password = 'Secure@12345' } | Out-Null
$conversationA = Invoke-PostJson $userA '/api/conversations' @{ title = '用户甲的私有会话' }

$crossStatus = 0
try {
    Invoke-WebRequest -Uri "$BaseUrl/api/conversations/$($conversationA.id)/messages" -WebSession $userB.Session -UseBasicParsing | Out-Null
    $crossStatus = 200
} catch {
    $crossStatus = [int]$_.Exception.Response.StatusCode
}

Invoke-PostJson $userA '/api/auth/logout' @{} | Out-Null
$logoutStatus = 0
try {
    Invoke-WebRequest -Uri "$BaseUrl/api/auth/me" -WebSession $userA.Session -UseBasicParsing | Out-Null
    $logoutStatus = 200
} catch {
    $logoutStatus = [int]$_.Exception.Response.StatusCode
}

if ($health.status -ne 'UP' -or $crossStatus -ne 404 -or $logoutStatus -ne 401) {
    throw "冒烟测试失败：health=$($health.status), cross=$crossStatus, logout=$logoutStatus"
}

[pscustomobject]@{
    BackendHealth = $health.status
    CrossUserAccess = "PASS ($crossStatus)"
    LogoutInvalidation = "PASS ($logoutStatus)"
    ConversationId = $conversationA.id
} | Format-List

