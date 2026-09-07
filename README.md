# 河南省教育科研网网络安全垂域智能体

> 生产部署请阅读 [DEPLOYMENT_UBUNTU22.md](DEPLOYMENT_UBUNTU22.md)，联调用例见 [TESTING.md](TESTING.md)，上线门禁见 [DEPLOYMENT_READINESS.md](DEPLOYMENT_READINESS.md)。

正式版采用 `Vue 3 + Spring Boot 3 + MySQL + Redis + MinIO + Dify`。根目录原有的
`index.html / script.js / style.css / server.js` 作为 V1 原型保留，新版代码位于：

- `web/`：Vue 3 工作区前端
- `backend/`：Spring Boot 业务后端
- `docker-compose.yml`：隔离的 MySQL、Redis、MinIO 和开发邮件服务

## 已实现能力

- 注册、登录、退出、当前用户查询
- Redis 服务端 Session 与 CSRF 防护
- 找回密码、一次性重置令牌、开发邮件服务
- 个人资料、头像、手机号、邮箱、出生日期、性别、学历和职务
- 系统管理员、组织管理员、分析员和只读角色
- 注册审核、账号启用/停用与角色管理
- 用户会话隔离、会话软删除、消息持久化
- 本地会话 ID 与 Dify `conversation_id` 安全映射
- Dify 流式输出代理和运行状态持久化
- IP、域名、URL、CVE、Hash 快捷查询直连对应 Dify 子工作流
- Spring Boot 统一证据网关并行聚合 AbuseIPDB、abuse.ch、NVD、GreyNoise、OTX、EPSS、CISA KEV 与 CIRCL
- 快捷查询结果按当前登录用户写入会话与运行记录
- 公开学术资料聚合检索、检索历史、个人收藏与 Redis 缓存
- OpenAlex、Semantic Scholar、arXiv、Crossref、Open Library 多源去重
- 自然语言到 MITRE ATT&CK / CWE 智能映射、证据检查与用户级历史
- Codex 风格安全研判工作区

## 端口

| 服务 | 地址 |
| --- | --- |
| Vue 前端 | http://127.0.0.1:5173 |
| Spring Boot API | http://127.0.0.1:8088 |
| MySQL | 127.0.0.1:13306 |
| Redis | 127.0.0.1:16379 |
| MinIO API | http://127.0.0.1:19000 |
| MinIO Console | http://127.0.0.1:19001 |
| Mailpit | http://127.0.0.1:18025 |

这些端口与本机已有的 3306/6379 服务隔离。

## 启动

### 推荐：关机后一键启动全部依赖

先启动 Docker Desktop，等待 Docker Engine 完全就绪，然后执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "C:/Users/Lenovo/Desktop/省网dify智能体v0.1/henan-sec-agent-web-260830/scripts/start-all.ps1"
```
该脚本会按顺序启动 Dify、平台 MySQL/Redis/MinIO/Mailpit、SIR、ATT&CK Bridge、Spring Boot 和正式 Vue 前端。已运行的服务会跳过，避免重复启动。

随时检查全部服务：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "C:/Users/Lenovo/Desktop/省网dify智能体v0.1/henan-sec-agent-web-260830/scripts/status-all.ps1"
```

### 手工分步启动

1. Dify：

```powershell
cd D:/software/dify-latest/docker
docker compose up -d
```

2. 平台 MySQL、Redis、MinIO 和 Mailpit：

```powershell
cd "C:/Users/Lenovo/Desktop/省网dify智能体v0.1/henan-sec-agent-web-260830"
docker compose up -d
```

3. SIR 插件调试进程（保持窗口打开）：

```powershell
cd D:/dify-agent-dev/security_ioc_router
./.venv/Scripts/python.exe -m main
```

4. Local ATT&CK HTTP Bridge（保持窗口打开）：

```powershell
cd D:/attack-http-bridge
./.venv/Scripts/python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8011
```

5. Spring Boot 后端（保持窗口打开）：

```powershell
cd "C:/Users/Lenovo/Desktop/省网dify智能体v0.1/henan-sec-agent-web-260830"
./scripts/start-backend.ps1
```

6. Vue 前端（新开 PowerShell 并保持窗口打开）：

```powershell
cd "C:/Users/Lenovo/Desktop/省网dify智能体v0.1/henan-sec-agent-web-260830/web"
npm run dev
```

平台登录地址为 `http://127.0.0.1:5173/login`，Dify 地址为 `http://127.0.0.1:8081/signin`。

### 仅启动平台基础服务

在项目根目录执行：

```powershell
docker compose up -d
```

后端：

```powershell
.\scripts\start-backend.ps1
```

前端：

```powershell
cd web
npm install
npm run dev
```

默认开发管理员：

```text
用户名：admin
密码：Admin@123456
```

该密码仅用于首次本地启动。部署前必须通过 `BOOTSTRAP_ADMIN_PASSWORD` 修改，并将
`EXPOSE_RESET_TOKEN_IN_DEV` 设为 `false`。

## 构建检查

```powershell
cd backend
mvn clean test package

cd ..\web
npm run build
```

后端启动后可运行隔离冒烟测试：

```powershell
.\scripts\smoke-test.ps1
```

## Dify 关联规则

1. 浏览器只提交本地会话 ID。
2. Spring Security 从 Redis Session 识别当前用户。
3. 后端检查 `conversation.user_id` 与当前用户一致。
4. 后端从 MySQL 读取 Dify `conversation_id`。
5. 调用 Dify 时使用 `hnsec_{用户UUID}` 作为 `user`。
6. 首次返回的 Dify `conversation_id` 保存到 MySQL，后续由后端读取。

Dify API Key 只允许出现在服务端环境变量中，禁止进入 Vue 代码或提交 Git。

## IOC 统一证据网关

快捷查询会同时运行原有 Dify 子工作流和 Spring Boot 公开情报适配器，再把结果写入同一份
`evidence-bundle/v1` 证据快照。证据网关只调用查询接口，不提交举报、不上传文件或样本；
本地、内网、链路本地和组播目标会在外发前被拒绝。来源请求失败、额度耗尽和“未检出”是
三个不同状态，均不会被转换成“目标安全”。

在根目录 `.env` 中配置下列变量，具体值不得提交 Git：

```text
EVIDENCE_TIMEOUT_SECONDS=15
ABUSEIPDB_MAX_AGE_DAYS=90
ABUSEIPDB_API_KEY=
ABUSECH_AUTH_KEY=
NVD_API_KEY=
GREYNOISE_API_KEY=
OTX_API_KEY=
```

不需要 Key 的 FIRST EPSS、CISA KEV 和 CIRCL Hashlookup 会自动启用。CVE 查询优先保留
NVD 的基础评分、EPSS 的利用概率和 CISA KEV 的已知利用状态，不在 Spring Boot 中擅自
合成为新的风险分值。完整证据快照随运行记录保存，可由报告中心继续使用。

## 免费资料检索

登录后访问 `http://127.0.0.1:5173/research`。默认不需要任何付费 API Key，即可调用
arXiv 和 Crossref（含图书元数据）。OpenAlex 与 Semantic Scholar 填入免费 Key 后自动启用。
如果部署环境能够稳定直连 Open Library，可设置 `OPEN_LIBRARY_ENABLED=true`。

为了提升免费额度和稳定性，可以在 `.env` 中按需填写：

```text
CROSSREF_MAILTO=您的联系邮箱
OPENALEX_API_KEY=免费申请的Key
SEMANTIC_SCHOLAR_API_KEY=免费申请的Key
```

所有第三方密钥只保存在后端；前端不会接触密钥。知网、万方、Web of Science 和郑大
图书馆当前提供合规跳转入口，取得正式机构接口授权后再增加服务端连接器。

## RAG 知识库集成

当前实现采用 **Dify 统一管理知识库检索与回答编排，Spring Boot 负责身份、会话、运行追踪、
报告和知识库运维接口**。知识检索是主 Agent 的默认后台能力，不是需要用户手动选择的工具：
安全研判分支把知识库作为实时 IOC 证据之后的补充来源，普通问答分支把相关知识片段作为回答
依据，资料解读分支也会检索知识库，但仍禁止把资料中的 URL 或 IOC 自动转成检测目标。

工作台已移除独立“省网知识库问答”入口和 `KNOWLEDGE` 任务模式。用户保持“自动判断”即可；
系统每轮执行检索，但只有达到相关性要求的片段才应进入回答。旧客户端提交的 `KNOWLEDGE`
会由后端兼容转换为 `AUTO`。

项目根目录 `.env` 中的 `DIFY_DATASET_API_KEY` 和 `DIFY_RAG_PILOT_DATASET_ID` 仅供批量
导入、索引检查和离线召回测试使用，不参与在线问答分流。运行五题基准测试：

```powershell
cd "C:\Users\Lenovo\Desktop\省网dify智能体v0.1\henan-sec-agent-web-260830"
.\scripts\test-rag-pilot.ps1
```

测试任意问题，并显示 Top-5 来源与文段：

```powershell
.\scripts\test-rag-pilot.ps1 -Query "网络运营者的网络日志应至少留存多长时间？" -TopK 5
```

默认 `dual` 模式同时执行语义与全文检索。也可分别定位召回问题：

```powershell
.\scripts\test-rag-pilot.ps1 -Query "你的问题" -Mode semantic_search
.\scripts\test-rag-pilot.ps1 -Query "你的问题" -Mode full_text_search
```

“来源命中”只表示找到了正确文档；“精确证据命中”才表示回答所需的原文片段进入了
Top-K。新增知识库必须在 Dify 工作流的安全研判、兜底问答和资料解读三个知识检索节点中
完成绑定并重新发布，再以精确证据命中率、引用正确率和无依据回答率作为验收指标。

## MITRE 智能映射

登录后从工作区左侧进入“MITRE 智能映射”，或直接访问
`http://127.0.0.1:5173/mitre-mapper`。该功能独立调用 DeepSeek，不经过 Dify 主工作流，
支持攻击描述映射到 ATT&CK Enterprise 战术/技术，以及漏洞描述匹配到 CWE。

在本机 `.env` 中配置（密钥禁止提交 Git）：

```text
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_API_KEY=您的DeepSeek密钥
DEEPSEEK_MODEL=deepseek-v4-flash
```

Windows 下也可以运行 `./scripts/configure-deepseek.ps1`，在不回显密钥的输入框中完成配置。

后端会保存当前用户的映射历史、复用 24 小时内的相同请求，并检查模型引用的证据是否
确实出现在原始描述中。为降低模型输出漂移导致的偶发失败，映射链路使用零温度生成、
宽容 JSON 边界解析，并在首次结构校验失败时自动修复一次。ATT&CK/CWE 编号和名称还会
使用随项目部署的 MITRE 官方知识目录进行本地复核。模型映射属于辅助研判结果，正式报告
仍应点击编号前往 MITRE 官方页面核验。

## 数据库迁移

Flyway 脚本位于 `backend/src/main/resources/db/migration/`。禁止手工修改已经上线执行过
的迁移；后续变更应新增更高版本脚本，例如 `V5__...sql`。
