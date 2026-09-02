# 河南省教育科研网网络安全垂域智能体

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

## 数据库迁移

Flyway 脚本位于 `backend/src/main/resources/db/migration/`。禁止手工修改已经上线执行过
的迁移；后续变更应新增 `V3__...sql`、`V4__...sql`。
