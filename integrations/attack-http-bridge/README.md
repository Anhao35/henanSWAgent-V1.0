# Local ATT&CK HTTP Bridge v0.2

该服务把 `mitre-attack-mcp` 的 stdio 工具封装为 Dify 可调用的 HTTP API。原始 MCP 是 ATT&CK 结构化查询服务，不是自然语言语义映射模型；v0.2 在 Bridge 层增加候选校验和证据边界。

## v0.2 映射等级

- `formal_mapping`：显式 ATT&CK 编号、高置信规则，或经原文证据和 MCP 编号共同校验的模型候选。
- `potential_only`：漏洞能力等只说明可能相关、不能证明攻击行为已经发生的候选。
- `content_candidates_only`：ATT&CK 描述字符串搜索结果，仅供人工检索，绝不进入正式映射。
- `no_mapping`：当前上下文没有足够的行为证据。

`allow_content_fallback` 默认是 `false`。即使显式开启，结果也只写入 `content_candidates`，不会写入 `matched_techniques`。

## 启动

```powershell
cd D:\attack-http-bridge
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8011
```

## 回归测试

```powershell
cd D:\attack-http-bridge
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

测试覆盖钓鱼附件、PowerShell、下载载荷、纯 CVE、模型候选原文校验、潜在映射和内容检索隔离。

服务已启动时，可再运行真实 HTTP 冒烟验收：

```powershell
.\.venv\Scripts\python.exe .\tests\live_smoke.py
```
