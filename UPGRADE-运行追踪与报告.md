# 运行追踪、任务意图与报告中心：升级交接

## ATT&CK 精确映射 V2.4（2026-09-07）

本次修复不是继续增加关键词，而是修正三层数据链路：

1. `attack-http-bridge v0.2`：把 `formal_mapping`、`potential_only`、`content_candidates_only` 和 `no_mapping` 分开；模型候选必须通过 ATT&CK 编号、置信度和输入原文证据三重校验。内容检索默认关闭，即使人工开启也不会进入正式映射。
2. `dify-generated/LocalAttackMCP-V2.yml`：增加“ATT&CK 行为证据提取”节点，将英文检索词、候选 T 编号、原文引用、置信度和 observed/potential 类型传给 Bridge；同时保持已发布工作流工具名 `LocalAttackMCP` 不变，便于覆盖升级。
3. `dify-generated/省网智能体-平台意图分流-V2.4.yml`：不再只把 Evidence Bus 摘要直接传给 ATT&CK 工具，而是合并“用户原始输入/附件行为上下文 + Evidence Bus”。最终总结 LLM 也增加了四类映射状态的强制语义边界。

### Dify 导入与发布顺序

1. 先重启 `D:\attack-http-bridge` 使 v0.2 代码生效，并确认 `http://127.0.0.1:8011/health` 返回 `status=ok`。
2. 在 Dify 中导出当前 `LocalAttackMCP` 备份，再用 `LocalAttackMCP-V2.yml` **覆盖导入同一应用**。检查 `deepseek-v4-flash` 绑定和 HTTP 节点的 Bridge 地址/API Key，测试运行后发布。
3. 打开当前平台主工作流，确认“本地ATT&CK感知分析MCP”仍绑定上一步的工作流工具。如 Dify 提示参数结构已变更，重新选择一次该工具。
4. 导出当前主工作流备份，用 `省网智能体-平台意图分流-V2.4.yml` **覆盖导入同一应用**，检查模型、SIR、知识库和子工具绑定后发布。覆盖同一应用不需要更换平台 Dify API Key。
5. 在子工作流和主工作流都发布后，再进行前端验收。

### 固定回归测试

Bridge 层现有 6 项自动化用例，覆盖恶意附件、PowerShell、载荷下载、纯 CVE 元数据、模型原文引用校验、潜在映射和内容候选隔离：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File 'C:\Users\Lenovo\Desktop\省网dify智能体v0.1\henan-sec-agent-web-260830\scripts\test-attack-v24-all.ps1'
```

前端固定验收输入：

- 行为正例：`攻击者向财务人员发送恶意 Excel 附件。用户启用宏后，PowerShell 从远程服务器下载载荷并执行。` 应形成 T1566.001、T1059.001 和 T1105 等有原文证据的正式映射。
- 纯 CVE 反例：`请研判 CVE-2024-3400，CVSS 10.0，已列入 CISA KEV，请给出修复建议。` 不应产生“已发生的正式 ATT&CK 映射”。
- 无行为 IOC 反例：`请查询 8.8.8.8 的情报和风险。` 不应因为 ATT&CK 描述中的偶然字符匹配返回固定五个技法。

## 真实联调更新（2026-09-04）

用户已更新 SIR 调试 Key，新应用 Key 已配置到平台 `DIFY_ROUTED_API_KEY`，后端已重启且健康检查 UP。新应用 `/parameters` 返回 task_mode 与 platform_run_id。真实 READ 测试中 SIR、route_plan 解析和意图门均成功，但 V2 阅读 LLM 因无附件时引用 `1779195036236.multimodal_context_text` 失败；没有执行 IOC 查询工具。

已生成 `dify-generated/省网智能体-平台意图分流-V2.3.yml`：在 V2.2 自动知识检索的基础上，为主安全研判增加“精确检索词构造”和“无关片段硬过滤”，并为资料解读分支增加同类过滤。CVE、CWE、ATT&CK 编号和 Hash 等显式标识符必须在候选片段中精确出现；若没有直接命中，工作流只向最终模型传递“知识库未直接命中”，不会再传入其他编号的相似资料。知识检索同时调整为关键词 0.45、向量 0.55 的混合检索。SIR、IOC 工具、证据标准化和 Evidence Bus 均未改动。V2.2 的 94 个节点保留，新增 3 个（共 97 个），8 项图结构与过滤回归测试通过，修订尚未在 Dify 发布。

下一步请进入**当前平台正在使用的 V2.2 分流应用**编辑器，先导出备份，再使用导入 DSL 的“覆盖并导入”更新为 V2.3。导入后逐一打开“知识检索”“兜底分支-知识检索”“资料解读-知识检索”，确认都绑定需要使用的省网知识库和可用的 embedding/rerank 模型，然后发布。更新同一应用不需要重新创建 API Key，也不需要修改 `DIFY_ROUTED_API_KEY`。现有服务 API Key 只能运行应用，不能替代 Dify 控制台登录权限修改和发布工作流。发布后优先复测 `CVE-2024-3400`：如果知识库没有该编号，报告应明确“未直接命中”，且不得再出现 CVE-2024-36401、CVE-2024-55591。随后分别执行 AUTO、SECURITY、READ 三类真实验收。以下为此前交接记录，当前状态以本节为准。

## 当前状态（2026-09-04）

平台前后端代码已经落地，本机后端已执行 V6 增量迁移。原账号、会话、旧工作流和 SIR 源码没有删除或替换。前端访问 http://127.0.0.1:5173，后端健康检查 http://127.0.0.1:8088/actuator/health。

真实上游尚有两个启用步骤，不能把当前状态称为全部联调完成：

1. 启动 SIR 时收到 `handshake failed, invalid key`。在 Dify 插件调试页面获取最新调试 Key，更新 `D:\dify-agent-dev\security_ioc_router\.env` 的对应配置后重新启动。此次失败启动的进程已停止，避免反复重连。不要将 Key 发到聊天或 Git。
2. 导入 `dify-generated/省网智能体-平台意图分流-V2.yml` 为**新应用**，检查模型、SIR 和子工具绑定后发布。在平台根目录 `.env` 新增 `DIFY_ROUTED_API_KEY=新应用的APIKey`，保留原 `DIFY_API_KEY`。重启平台后端。

SIR 插件调试 Key 与 Dify 应用 API Key 是两种不同凭据，不能混用。新导出沿用原工作流依赖标识，导入时若提示 SIR 依赖缺失，需要在 Dify 重新绑定当前可用插件。导出文件可能继承原工作流内的敏感配置，已经忽略 Git。

未设置新应用 Key 时，AUTO/SECURITY 仍使用原应用；READ/EXPLAIN 明确提示未配置，不会悄悄把论文 URL 交给旧 SIR 去检测。

## 开机启动顺序

先启动 Docker Desktop，等待 Docker 引擎就绪。以下服务分别使用独立 PowerShell 窗口，不要重复启动已经占用端口的服务。

### 1. Dify

```powershell
cd D:\software\dify-latest\docker
docker compose up -d
```

浏览器打开 http://localhost:8081/signin 。

### 2. SIR 插件调试进程

确认插件调试 Key 有效后：

```powershell
cd D:\dify-agent-dev\security_ioc_router
.\.venv\Scripts\python.exe -m main
```

直接使用虚拟环境 Python，无需先 Activate。当前为调试接入，进程退出可能导致插件不可用；服务器正式部署应安装打包插件并重新绑定应用，不应依赖个人电脑临时调试进程。

### 3. 本地 ATT&CK 服务

```powershell
cd D:\attack-http-bridge
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8011
```

本次检查 8011 已在监听，没有重复启动。

### 4. 平台 Spring Boot 后端

```powershell
cd 'C:\Users\Lenovo\Desktop\省网dify智能体v0.1\henan-sec-agent-web-260830'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-backend.ps1
```

脚本读取根目录 `.env`、`.env.workflows`，启动平台 MySQL/Redis/MinIO/Mailpit 容器，再启动 8088 后端。Flyway 自动增量升级数据库。请勿在已有后端运行时重复执行；Windows 下运行中的 JAR 也可能阻止重新打包。

### 5. 前端开发服务

```powershell
cd 'C:\Users\Lenovo\Desktop\省网dify智能体v0.1\henan-sec-agent-web-260830\web'
npm run dev -- --host 127.0.0.1
```

访问 http://127.0.0.1:5173，已有页面建议刷新。修改 `.env` 后需重启后端，仅刷新浏览器不会更新服务端配置。Ubuntu 正式部署应使用构建后的静态前端与反向代理，而不是 Vite 开发服务器。

## 功能和边界

### 运行追踪

- 主聊天与五类快捷查询使用真实 Dify SSE 事件，过程面板独立于最终答案，记录运行编号、节点执行标识、状态和耗时。
- 断开浏览器连接不会自动重提任务；回到同一会话可查看已持久化进度和已收到的部分回答。刷新后恢复的是查看，不是重新执行。
- 心跳只证明平台连接仍在，不伪造百分比或第三方执行进度。长时间没有新事件会明确提示等待。
- 单个情报源失败时保留其他来源，整体标记部分成功；上游提前断流不会标记成功。
- 停止会记录请求并尝试停止 Dify；已经提交给第三方的任务可能继续，不能承诺撤销外部操作。
- 后端重启把未结束的新追踪任务标记中断，保留部分结果。此恢复机制针对单实例，未来多实例部署必须改成任务租约/分布式恢复，不能直接沿用。
- 历史旧记录无法补造从未保存的节点事件。本版覆盖聊天和 IOC 快捷查询，资料搜索和 MITRE 独立模块尚未统一接入。
- Dify 未向 SSE 透传的 SIR 内部步骤、嵌套工具细节无法凭空展示；本版不安装内部回调或展示模型推理内容。

### 意图控制与 SIR

保留原 SIR 确定性提取能力。新增每轮模式 AUTO、SECURITY、READ、EXPLAIN；明确阅读意图可进入阅读模式，单独链接和相互冲突的指令会要求选择模式，避免默认检测 URL。

新图在解析 route_plan 后增加执行门：READ/EXPLAIN 进入无工具的文本解读分支，其余进入原研判路由。新增 3 个节点，原 89 个节点除开始节点新增输入变量外保持不变。**由于门在 SIR 后面，阅读分支同样需要 SIR 插件可用。**

平台每轮附带 task_mode、platform_run_id；不同模式保存独立 Dify 会话映射，避免旧研判上下文污染阅读任务。因此跨模式不会自动共享全部上游历史，必要背景应在本轮提供。

这是规则门控第一版，不是完整语义分类器，也不支持一句话中只检测 A、不检测 B 的细粒度目标授权。复杂混合任务应拆分。阅读分支仅解释已提供的题录、摘要、正文或附件，不会自动访问 URL，不应声称读过未获取的论文全文。

### 报告中心

成功或部分成功的带追踪回答可以点击生成报告，也可从侧栏进入报告中心。内置详细技术报告、简报、漏洞报告三个模板系列。

- 详细报告保留原分析全文，追加实际获得的 IOC/证据字段和限制；其他模板改变组织方式，不额外调用模型，不编造未执行的验证。
- READ/EXPLAIN 仅允许原结果详细归档，不伪装成威胁研判结论。
- 保存原始运行快照、模板版本和 SHA-256 摘要。摘要用于一致性校验，不是事实真实性证明。
- 编辑和人工确认均保存新版本，旧版不覆盖；人工确认是当前操作者的标记，不代表独立双人审批或电子签章。
- 超级管理员可发布共享模板的新版本，普通用户不能修改共享模板。证据和限制章节不能被移除。
- 导出 Markdown、证据 JSON、可打印 HTML；HTML 可在浏览器打印为 PDF。本版没有原生 Word 或专门的 PDF 排版引擎。
- 运行、报告、导出均校验所属用户与未删除会话，不因知道 ID 就能读取他人资料。

本版是运行证据快照与报告归档，不是完备的取证库：还没有采集链签名、跨部门审批、自动保留期限清理、批量档案迁移。报告和事件会占用数据库空间，上线前需制定保留期限、访问权限和备份策略。删除/隐藏会话不等于数据库物理擦除。

## 验收与待办

已执行：14 项 Java 测试通过（5 项接口验收、6 项协议/规则测试、3 项原有 MITRE 测试；真实隔离 MySQL 与 Redis、模拟 Dify，不调用付费模型），Vue 类型检查与构建，Edge 模拟接口页面验收（模式、链接保留、过程面板、报告生成/导出入口、移动端无横向溢出），4 项原 YAML 图结构检查。验收数据库 `henan_agent_acceptance_20260904` 与生产库分离，测试数据留在隔离库，未删除。

生产本机检查：健康状态 UP；直接访问 8088 或经过 5173 代理访问未登录运行接口均为 401；V6 迁移成功。新 Key 尚未启用前，不能声称真实阅读分流已经成功。

真实联调顺序：

1. 更新 SIR 调试 Key，确认插件连接成功。
2. 发布新 Dify 应用并配置 DIFY_ROUTED_API_KEY，重启平台后端。
3. 安全模式检测明确的测试 IOC，确认 SIR 仍提取、查询，界面显示真实节点进度。
4. 阅读模式发送论文摘要与 DOI URL，确认进入阅读节点，不调用 IOC 工具，不宣称已读取全文。
5. 快捷查询确认直连子工作流，测试停止、浏览器刷新恢复查看和失败状态。
6. 用两个不同账号检查运行/报告隔离，生成并编辑报告，确认旧版本不变、导出可读。

## 回退

升级前备份位于项目同级 `implementation-backup-20260904`：`backend-before-tracing.jar`、`database-before-tracing.sql`、`backend-src`、`web-src`、`scripts`。数据库备份含敏感数据，不能公开。

先停止平台后端，再按需恢复配套旧代码与旧 JAR；只清空 DIFY_ROUTED_API_KEY 可回退到原 Dify 路径，但 READ/EXPLAIN 将拒绝执行。V6 为增量表，优先保留，**不要直接删除表或覆盖当前数据库**。若旧 JAR 的 Flyway 因较新迁移拒绝启动，应在另一个空数据库恢复升级前备份并切换连接进行回退验证；先额外备份当前库，避免丢失升级后产生的记录。不要对生产库盲目执行 Flyway clean/repair。
