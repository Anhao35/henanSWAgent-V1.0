# 河南省教育科研计算机网络中心 - 网络安全垂域智能体

这是一个可本地运行的 Web App 原型，已经包含：

- 顶部标题与河南教育科研网安全风格
- 中间聊天面板（分析进度）
- 右侧会话记录
- 底部四个按钮：`URL查询 / 域名查询 / IP查询 / Hash查询`
- 本地后端接口：`/api/chat`
- Dify 接口预留

## 1. 安装 Node.js

先确认电脑安装的是 **Node.js 18 或更高版本**。

在项目目录运行：

```powershell
node -v
```

## 2. 启动本地项目

在项目目录运行：

```powershell
npm start
```

启动后浏览器打开：

```text
http://localhost:3000
```

如果暂时没有配置 Dify，系统会进入“本地模拟模式”，这一步的目的是先确认：

- 页面已经能打开
- 前端已经能调用后端
- 聊天消息已经能正常显示

## 3. 接入 Dify

把 `.env.example` 复制为 `.env`，然后填写你自己的参数：

```env
PORT=3000
DIFY_BASE_URL=http://127.0.0.1:8080/v1
DIFY_API_KEY=app-xxxxxxxxxxxxxxxx
DIFY_USER=henan-sec-user
```

如果你是通过 SSH 隧道把服务器上的 Dify 转发到本机 `8080`，建议这里明确写成：

```env
DIFY_BASE_URL=http://127.0.0.1:8080/v1
```

不要写 `localhost`。Windows 下 Node.js 可能优先走 IPv6 的 `::1`，而 SSH 隧道通常只绑定在 IPv4 `127.0.0.1`，结果就是浏览器看起来后端在线，但后端调用 Dify 会一直超时。

然后重启：

```powershell
npm start
```

## 4. 当前调用逻辑

前端发送请求到：

```text
POST /api/chat
```

后端再转发给 Dify：

```text
POST {DIFY_BASE_URL}/chat-messages
```

这样做的好处是：

- 前端不直接暴露 API Key
- 后面换接口地址更方便
- 以后接内网也只改后端

## 5. 建议的开发顺序

1. 先跑通页面
2. 再验证本地模拟对话
3. 再填 Dify 参数
4. 最后再处理内网、鉴权、部署
