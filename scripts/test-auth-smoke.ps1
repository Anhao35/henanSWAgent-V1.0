$ErrorActionPreference = 'Stop'
$BaseUrl = 'http://127.0.0.1:8088/api'
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
$username = "smoke_$stamp"
$phone = '139' + $stamp.Substring([Math]::Max(0, $stamp.Length - 8))
if ($phone.Length -gt 11) { $phone = $phone.Substring(0, 11) }

$csrf = Invoke-RestMethod "$BaseUrl/auth/csrf" -SessionVariable session
$headers = @{ 'X-XSRF-TOKEN' = $csrf.token }
$sendBody = @{ purpose='REGISTER'; channel='PHONE'; target=$phone } | ConvertTo-Json
$sent = Invoke-RestMethod "$BaseUrl/auth/verification-codes" -Method Post -WebSession $session -Headers $headers -ContentType 'application/json' -Body $sendBody
if (-not $sent.devCode) { throw '本地冒烟测试需要 EXPOSE_VERIFICATION_CODE_IN_DEV=true' }
if ($sent.devCode -notmatch '^\d{4}$') { throw '手机验证码不是 4 位数字' }

$register = @{
    username=$username; displayName='冒烟测试用户'; password='Smoke@Test12345';
    email=''; phone=$phone; organizationCode='HERCERT'; verificationChannel='PHONE'; verificationCode=$sent.devCode
} | ConvertTo-Json
$created = Invoke-RestMethod "$BaseUrl/auth/register" -Method Post -WebSession $session -Headers $headers -ContentType 'application/json' -Body $register
if (-not $created.user.id) { throw '注册响应缺少用户 ID' }

Write-Host "PASS: 4 位手机码发送与注册链路正常，测试账号 $username（状态 $($created.user.status)）"
Write-Host '提示：该脚本会留下一个待审核的测试账号，便于管理员页面验收。'
