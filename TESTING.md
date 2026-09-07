# 联调与回归用例

## Windows 本机

```powershell
cd "C:\Users\Lenovo\Desktop\省网dify智能体v0.1\henan-sec-agent-web-260830"
.\scripts\start-all.ps1
.\scripts\status-all.ps1
mvn -f .\backend\pom.xml test
npm --prefix .\web run build
python .\scripts\test-attack-v25.py
Push-Location 'D:\attack-http-bridge'
& '.\.venv\Scripts\python.exe' -m unittest discover -s tests -v
Pop-Location
.\scripts\test-auth-smoke.ps1
```

## V2.5 手工回归

1. **应强制 LocalAttackMCP**
   - `攻击者通过恶意 Excel 诱导用户启用宏，随后 PowerShell 从远程服务器下载载荷并执行，请映射 MITRE ATT&CK。`
   - 期望：运行追踪出现显式 LocalAttackMCP 节点；返回有原文证据的映射，不是 content fallback。
2. **明确 T 编号**
   - `T1059.001 是什么？给出检测思路。`
   - 期望：强制调用，回答聚焦 PowerShell 技术且链接可校验。
3. **不应调用**
   - `Spring Boot 登录模块怎样设计？`
   - `这篇论文研究对抗样本攻击，请概括。`
   - 期望：直接走普通兜底回答，不出现 ATT&CK 映射工具节点。
4. **只有 CVE**
   - `请研判 CVE-2024-3400。`
   - 期望：走 IOC/CVE 主链路；没有被观测行为时不得把内容检索候选写成正式 ATT&CK 映射。

## 认证手工回归

- 用户名登录；同一已启用账号使用登记邮箱/手机登录。
- 邮箱注册码必须 6 位，本地 Mailpit `http://127.0.0.1:18025` 可查收。
- 手机注册码必须 4 位；本地在页面显示测试码，生产禁止显示。
- 错码 5 次、过期码、已使用码都必须失败。
- 找回密码使用登记渠道验证码，重置后旧密码失效、新密码可登录。
- 待审核/停用账号不能登录。
