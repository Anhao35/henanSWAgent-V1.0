# Ubuntu 22.04 生产部署手册

本仓库包含 Vue 前端、Spring Boot 后端、MySQL、Redis、MinIO、LocalAttack HTTP Bridge、SIR 源码和 Dify DSL。Dify 本体按官方 Compose 独立部署，避免与平台数据卷耦合。

## 1. 服务和端口

| 服务 | 宿主机端口 | 说明 |
|---|---:|---|
| 平台 Web | 80（推荐由 HTTPS 反代入口） | 唯一对用户公开的端口 |
| Dify | 8081 | 仅内网或管理网可访问 |
| LocalAttack Bridge | 127.0.0.1:8011（可通过 `ATTACK_BRIDGE_PORT` 修改） | 只允许 Dify 容器/管理网访问 |
| MinIO Console | 127.0.0.1:19001（可通过 `MINIO_CONSOLE_PORT` 修改） | 通过 SSH 隧道管理 |
| MySQL / Redis | 不映射 | 只在 Compose 内网中使用 |

## 2. 宿主机准备

```bash
sudo apt update
sudo apt install -y ca-certificates curl git openssl
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"
newgrp docker
docker version
docker compose version
```

省网服务器若不允许执行网络安装脚本，请由运维按 Docker 官方 Ubuntu 仓库流程离线/审批安装，不要绕过变更制度。

## 3. 部署 Dify

```bash
sudo mkdir -p /opt/henan-sec
sudo chown "$USER":"$USER" /opt/henan-sec
cd /opt/henan-sec
git clone https://github.com/langgenius/dify.git
cd dify/docker
cp .env.example .env
# 按省网密码规则修改 .env，并将 nginx 宿主机端口配置为 8081
docker compose up -d
docker compose ps
```

完成 Dify 初始化后：

1. 安装 DeepSeek、SIR 等已用插件。
2. 导入 `dify-generated/LocalAttackMCP-V2.yml`并发布成工作流工具。
3. 导入 `dify-generated/省网智能体-平台意图分流-V2.5.yml`，检查模型、知识库和 LocalAttackMCP 绑定后发布。
4. 把主应用 API Key 写入平台 `.env.production` 的 `DIFY_ROUTED_API_KEY`。
5. Dify 容器访问 Bridge 时，配置 `host.docker.internal:host-gateway`，工具 URL 使用 `http://host.docker.internal:8011`。

SIR 远程调试插件位于 `integrations/security_ioc_router`。生产环境更推荐将其打包为 Dify 插件安装；只在联调期间使用 `.env` 中的远程调试 Key 运行 `python -m main`。

## 4. 启动平台

如暂时没有 HTTPS 域名、仅通过内网 IP 验收，请先使用未占用端口（例如 `8080`），并在
`.env.production` 中设置 `PUBLIC_ORIGIN=http://服务器IP:8080`、`WEB_PORT=8080` 和
`SESSION_COOKIE_SECURE=false`。正式接入 HTTPS 后必须把公开地址改为 HTTPS，并将
`SESSION_COOKIE_SECURE` 恢复为 `true`。

```bash
cd /opt/henan-sec
git clone git@github.com:Anhao35/henanSWAgent-V1.0.git platform
cd platform
cp .env.production.example .env.production
openssl rand -hex 32   # 为数据库、Redis、MinIO、验证码和 Bridge 分别生成不同密钥
vi .env.production
docker compose --env-file .env.production -f docker-compose.production.yml config
docker compose --env-file .env.production -f docker-compose.production.yml build
docker compose --env-file .env.production -f docker-compose.production.yml up -d
docker compose --env-file .env.production -f docker-compose.production.yml ps
curl -fsS http://127.0.0.1/actuator/health
```

停止但保留数据：

```bash
docker compose --env-file .env.production -f docker-compose.production.yml stop
```

升级：

```bash
git pull --ff-only
docker compose --env-file .env.production -f docker-compose.production.yml build
docker compose --env-file .env.production -f docker-compose.production.yml up -d
```

## 5. HTTPS 和防火墙

由省网统一 Nginx/负载均衡器终止 TLS，只对外开放 443。将 `PUBLIC_ORIGIN` 设为真实 HTTPS 域名，保持 `SESSION_COOKIE_SECURE=true`。Dify 8081、Bridge 8011 和 MinIO 管理口仅向内网或特定管理 IP 放行。

## 6. 邮件、短信和密码找回

旧版“发送重置指引”指邮件中的一次性链接。现版已改为动态验证码：邮箱 6 位、手机 4 位，10 分钟过期、最多 5 次错误尝试、成功后立即作废。

- SMTP 直接通过 `MAIL_*` 配置。
- 短信通过 `SMS_WEBHOOK_URL` 对接省网选定的阿里云/腾讯云/校内短信适配服务，请求体为 `{phone, code, purpose, ttlMinutes}`。
- 生产强制 `EXPOSE_VERIFICATION_CODE_IN_DEV=false`，任何响应不会返回验证码。
- `MAIL_HEALTH_ENABLED` 默认关闭，避免 SMTP 短时故障把整个平台误判为不可用；请用独立监控告警验证邮件通道，若希望它参与总健康状态再设为 `true`。

## 7. 备份与恢复

每日备份 MySQL，并对 `mysql_data`、`redis_data`、`minio_data`、`mitre_attack_data` 做快照。在非生产环境每月执行一次恢复演练。不要把 Docker volume、`.env.production`、API Key 或用户上传文件提交到 Git。

## 8. 50 GB 知识库

语料不存 MySQL，也不放入本 Git 仓库。第一阶段放入 Dify 知识库（底层向量库由 Dify 管理），按「法规/漏洞/应急/学术/内部制度」拆库，先做 1–5 GB 金标试点再分批导入。检索延迟主要受 top-k、rerank 和候选库数影响，不是语料总容量线性增长。通过先分类路由到 1–2 个知识库、每库 top-k 4–6、统一 rerank 后取 4–8 段控制延迟。

## 9. 上线前门禁

```bash
mvn -f backend/pom.xml test
npm --prefix web ci
npm --prefix web run build
python scripts/test-attack-v25.py
python integrations/attack-http-bridge/tests/test_mapping.py
docker compose --env-file .env.production -f docker-compose.production.yml config
```

还必须完成：实机 SMTP/短信验收、Dify V2.5 固定回归、HTTPS 证书、防火墙、备份恢复、密钥轮换、并发压测和审批记录核验。
