const http = require("http");
const fs = require("fs");
const path = require("path");

loadEnvFiles();

const PORT = Number(process.env.PORT || 3000);
const DIFY_BASE_URL = process.env.DIFY_BASE_URL || "";
const DIFY_API_KEY = process.env.DIFY_API_KEY || "";
const DIFY_USER = process.env.DIFY_USER || "local-user";
const DIFY_TIMEOUT_MS = Number(process.env.DIFY_TIMEOUT_MS || 1800000);
const MAX_ATTACHMENT_COUNT = 5;
const MAX_ATTACHMENT_SIZE = 15 * 1024 * 1024;
const MAX_REQUEST_BODY_SIZE = 105 * 1024 * 1024;
const SUPPORTED_ATTACHMENT_EXTENSIONS = new Set([
  ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg",
  ".pdf", ".doc", ".docx", ".txt", ".md", ".csv", ".xls", ".xlsx",
  ".ppt", ".pptx", ".html", ".json", ".xml", ".yaml", ".yml",
]);

const WORKFLOW_CONFIG = {
  ip: {
    label: "IP查询",
    apps: [
      { name: "VT IP", apiKey: process.env.DIFY_WORKFLOW_IP_VT_API_KEY || "" },
      { name: "微步信誉 IP", apiKey: process.env.DIFY_WORKFLOW_IP_WEIBU_API_KEY || "" },
    ],
  },
  domain: {
    label: "域名查询",
    apps: [{ name: "域名查询", apiKey: process.env.DIFY_WORKFLOW_DOMAIN_API_KEY || "" }],
  },
  url: {
    label: "URL查询",
    apps: [{ name: "URL查询", apiKey: process.env.DIFY_WORKFLOW_URL_API_KEY || "" }],
  },
  cve: {
    label: "CVE查询",
    apps: [{ name: "CVE查询", apiKey: process.env.DIFY_WORKFLOW_CVE_API_KEY || "" }],
  },
  hash: {
    label: "Hash查询",
    apps: [{ name: "Hash查询", apiKey: process.env.DIFY_WORKFLOW_HASH_API_KEY || "" }],
  },
};

const AUXILIARY_TOOL_CONFIG = {
  iocAggregate: {
    label: "单IOC指标综合查询",
    apiKey: process.env.DIFY_WORKFLOW_IOC_AGGREGATE_API_KEY || "",
  },
  localAttack: {
    label: "LocalAttackMCP",
    apiKey: process.env.DIFY_WORKFLOW_LOCAL_ATTACK_API_KEY || "",
  },
};

const mimeTypes = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "application/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".ico": "image/x-icon",
};

function loadEnvFiles() {
  [".env", ".env.workflows"].forEach((filename) => {
    const envPath = path.join(__dirname, filename);
    if (!fs.existsSync(envPath)) return;

    const content = fs.readFileSync(envPath, "utf8");
    content.split(/\r?\n/).forEach((line) => {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith("#")) return;

      const index = trimmed.indexOf("=");
      if (index < 0) return;

      const key = trimmed.slice(0, index).trim();
      const value = trimmed.slice(index + 1).trim();
      if (key && !process.env[key]) {
        process.env[key] = value;
      }
    });
  });
}

function normalizeDifyBaseUrl(baseUrl) {
  return String(baseUrl || "").replace(/^http:\/\/localhost(?=[:/])/i, "http://127.0.0.1");
}

function isValidUuid(value) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function stripThinkBlocks(text) {
  return String(text || "").replace(/<think>[\s\S]*?<\/think>/gi, "");
}

function trimReasoningPrefix(text) {
  const candidates = [
    "### 一、综合研判",
    "### 一、基本信息",
    "一、综合研判",
    "一、基本信息",
  ];

  for (const marker of candidates) {
    const index = text.indexOf(marker);
    if (index > 0) return text.slice(index);
  }

  return text;
}

function isBlockLikeLine(line) {
  const trimmed = line.trim();
  if (!trimmed) return true;
  return /^(#{1,6}\s|[-*+]\s|\d+\.\s|>\s|\|.*\||```)/.test(trimmed);
}

function smartJoin(prev, current) {
  const prevTail = prev.slice(-1);
  const currentHead = current.slice(0, 1);
  const needSpace = /[A-Za-z0-9]/.test(prevTail) && /[A-Za-z0-9]/.test(currentHead);
  return needSpace ? `${prev} ${current}` : `${prev}${current}`;
}

function mergeWrappedLines(text) {
  const lines = text.split("\n");
  const merged = [];
  let inCodeBlock = false;

  for (const rawLine of lines) {
    const line = rawLine || "";
    const trimmed = line.trim();

    if (trimmed.startsWith("```")) {
      inCodeBlock = !inCodeBlock;
      merged.push(line);
      continue;
    }

    if (inCodeBlock || isBlockLikeLine(line)) {
      merged.push(line);
      continue;
    }

    const lastIndex = merged.length - 1;
    if (lastIndex >= 0) {
      const prev = merged[lastIndex];
      if (prev && !isBlockLikeLine(prev)) {
        merged[lastIndex] = smartJoin(prev, trimmed);
        continue;
      }
    }

    merged.push(trimmed);
  }

  return merged.join("\n");
}

function formatKeyValueLines(text) {
  const lines = text.split("\n");
  let inCodeBlock = false;

  return lines.map((line) => {
    const trimmed = line.trim();
    if (trimmed.startsWith("```")) {
      inCodeBlock = !inCodeBlock;
      return line;
    }
    if (inCodeBlock || !trimmed || /^(#{1,6}\s|[-*+]\s|\d+\.\s|>\s|\|)/.test(trimmed)) {
      return line;
    }

    const match = trimmed.match(/^([^:\uFF1A]{2,20})[:\uFF1A]\s*(.+)$/);
    if (!match) return line;

    const key = match[1].trim();
    const value = match[2].trim();
    return `- **${key}**: ${value}`;
  }).join("\n");
}

function normalizeMarkdown(text) {
  let output = String(text || "");
  output = stripThinkBlocks(output).replace(/\r\n/g, "\n");
  output = trimReasoningPrefix(output).replace(/\u200b/g, "");
  output = output
    .split("\n")
    .map((line) => line.replace(/[ \t]+$/g, ""))
    .join("\n");
  output = mergeWrappedLines(output);
  output = formatKeyValueLines(output);
  output = output.replace(/\n{3,}/g, "\n\n");
  return output.trim();
}

function composeStrictMarkdownQuery(message) {
  return [
    "请严格使用 Markdown 输出，必须满足以下要求：",
    "1. 只输出最终分析结果，不输出思考过程。",
    "2. 固定结构：`### 一、综合研判`、`### 二、建议措施`、`### 三、附加信息`。",
    "3. 关键字段使用加粗键名，例如 `- **风险等级**: 高`。",
    "4. 涉及版本、时间、指标时优先使用 Markdown 表格。",
    "5. 禁止拆词换行，尤其是 CVE 编号、版本号、时间戳。",
    "6. 列表必须使用标准 Markdown 列表语法。",
    "7. 如需风险可视化，请使用 `echarts` 代码块输出合法 JSON 格式的 ECharts option；不要输出 JavaScript 变量、函数或注释。",
    "8. Markdown 表格的每个数据行必须保持为单行，不要在单元格中手动拆字或换行；较长内容直接连续书写。",
    "",
    `用户请求：${message}`,
  ].join("\n");
}

function sendJson(response, statusCode, payload) {
  response.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
  });
  response.end(JSON.stringify(payload));
}

function serveStaticFile(filePath, response) {
  const ext = path.extname(filePath).toLowerCase();
  const contentType = mimeTypes[ext] || "application/octet-stream";

  fs.readFile(filePath, (error, data) => {
    if (error) {
      sendJson(response, 404, { error: "文件不存在" });
      return;
    }

    response.writeHead(200, { "Content-Type": contentType });
    response.end(data);
  });
}

function collectRequestBody(request) {
  return new Promise((resolve, reject) => {
    let body = "";
    let receivedBytes = 0;
    let tooLarge = false;

    request.on("data", (chunk) => {
      receivedBytes += chunk.length;
      if (receivedBytes > MAX_REQUEST_BODY_SIZE) {
        tooLarge = true;
        body = "";
        return;
      }
      body += chunk;
    });

    request.on("end", () => {
      if (tooLarge) {
        reject(new Error("请求内容过大，请减少附件数量或文件大小。"));
        return;
      }
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch {
        reject(new Error("请求体不是合法 JSON"));
      }
    });

    request.on("error", reject);
  });
}

function writeStreamChunk(response, payload) {
  if (response.writableEnded || response.destroyed) return;
  response.write(`${JSON.stringify(payload)}\n`);
}

async function fetchJsonWithTimeout(url, options) {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), DIFY_TIMEOUT_MS);

  try {
    const upstream = await fetch(url, {
      ...options,
      signal: controller.signal,
    });

    if (!upstream.ok) {
      const detail = await upstream.text();
      throw new Error(`Dify 返回 ${upstream.status}: ${detail}`);
    }

    return await upstream.json();
  } catch (error) {
    if (error.name === "AbortError") {
      throw new Error(`Dify 请求超时：${DIFY_TIMEOUT_MS / 1000} 秒内没有返回结果。`);
    }
    throw error;
  } finally {
    clearTimeout(timeoutId);
  }
}

async function probeDifyService(apiKey) {
  if (!DIFY_BASE_URL || !apiKey) return false;

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), Math.min(DIFY_TIMEOUT_MS, 3000));

  try {
    const endpoint = `${normalizeDifyBaseUrl(DIFY_BASE_URL).replace(/\/$/, "")}/parameters`;
    const response = await fetch(endpoint, {
      headers: { Authorization: `Bearer ${apiKey}` },
      signal: controller.signal,
    });
    return response.ok;
  } catch {
    return false;
  } finally {
    clearTimeout(timeoutId);
  }
}

function extractWorkflowAnswer(data) {
  const outputs = data?.data?.outputs || data?.outputs || {};
  const candidateKeys = ["answer", "text", "result", "output", "content"];

  for (const key of candidateKeys) {
    if (typeof outputs[key] === "string" && outputs[key].trim()) {
      return normalizeMarkdown(outputs[key]);
    }
  }

  const stringEntries = Object.entries(outputs).filter(([, value]) => typeof value === "string" && value.trim());

  if (stringEntries.length === 1) {
    return normalizeMarkdown(stringEntries[0][1]);
  }

  if (stringEntries.length > 1) {
    return normalizeMarkdown(stringEntries.map(([key, value]) => `- **${key}**: ${value}`).join("\n"));
  }

  if (typeof data?.answer === "string" && data.answer.trim()) {
    return normalizeMarkdown(data.answer);
  }

  return "";
}

function buildWorkflowInputs(type, value) {
  return {
    input: value,
    query: value,
    resource: value,
    target: value,
    indicator: value,
    ioc: value,
    value,
    type,
    ip: type === "ip" ? value : "",
    domain: type === "domain" ? value : "",
    url: type === "url" ? value : "",
    cve: type === "cve" ? value : "",
    hash: type === "hash" ? value : "",
    keyword: type === "cve" ? value : "",
  };
}

async function callWorkflowApp(app, type, value) {
  const baseUrl = normalizeDifyBaseUrl(DIFY_BASE_URL).replace(/\/$/, "");
  const workflowPayload = {
    inputs: buildWorkflowInputs(type, value),
    response_mode: "blocking",
    user: DIFY_USER,
  };

  const workflowData = await fetchJsonWithTimeout(`${baseUrl}/workflows/run`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${app.apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(workflowPayload),
  });

  const answer = extractWorkflowAnswer(workflowData);
  if (!answer) {
    throw new Error(`${app.name} 已执行，但未从 workflow outputs 中提取到可展示内容。`);
  }

  return { name: app.name, answer };
}

function mergeWorkflowAnswers(type, value, results) {
  const headings = {
    ip: "# IP查询结果",
    domain: "# 域名查询结果",
    url: "# URL查询结果",
    cve: "# CVE查询结果",
    hash: "# Hash查询结果",
  };

  return normalizeMarkdown(
    [
      headings[type] || "# 查询结果",
      "",
      `查询目标：\`${value}\``,
      "",
      ...results.map((item) => `## ${item.name}\n\n${item.answer}`),
    ].join("\n"),
  );
}

function parseAttachment(attachment) {
  if (!attachment || typeof attachment.dataUrl !== "string") {
    throw new Error("附件数据格式不正确。");
  }

  const match = attachment.dataUrl.match(/^data:([^;,]+)?;base64,([\s\S]+)$/);
  if (!match) {
    throw new Error(`附件 ${attachment.name || ""} 不是有效的 Base64 文件。`);
  }

  const buffer = Buffer.from(match[2], "base64");
  if (!buffer.length) {
    throw new Error(`附件 ${attachment.name || ""} 内容为空。`);
  }
  if (buffer.length > MAX_ATTACHMENT_SIZE) {
    throw new Error(`附件 ${attachment.name || ""} 超过单个文件 15 MB 的限制。`);
  }

  const rawName = String(attachment.name || "attachment").replace(/[\\/:*?"<>|\u0000-\u001f]/g, "_");
  const name = rawName.slice(0, 180) || "attachment";
  const mimeType = match[1] || attachment.type || "application/octet-stream";
  const extension = path.extname(name).toLowerCase();
  const supported = mimeType.startsWith("image/")
    || mimeType.startsWith("text/")
    || mimeType === "application/pdf"
    || SUPPORTED_ATTACHMENT_EXTENSIONS.has(extension);
  if (!supported) {
    throw new Error(`附件 ${name} 不是当前后端支持的文件类型。`);
  }
  return { buffer, name, mimeType };
}

function inferDifyFileType(name, mimeType) {
  if (mimeType.startsWith("image/")) return "image";
  if (mimeType.startsWith("audio/")) return "audio";
  if (mimeType.startsWith("video/")) return "video";

  const extension = path.extname(name).toLowerCase();
  if ([".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg"].includes(extension)) {
    return "image";
  }
  return SUPPORTED_ATTACHMENT_EXTENSIONS.has(extension) ? "document" : "custom";
}

function describeDifyProgress(event) {
  const data = event?.data || {};
  const nodeName = data.title || data.node_title || data.node_type || "";

  if (event.event === "workflow_started") return "Dify 工作流已启动";
  if (event.event === "node_started") return nodeName ? `正在执行工作流节点：${nodeName}` : "正在执行工作流节点";
  if (event.event === "node_finished") return nodeName ? `工作流节点已完成：${nodeName}` : "一个工作流节点已完成";
  if (event.event === "agent_thought") {
    const toolName = event.tool || data.tool || event.tool_name || "";
    return toolName ? `正在调用安全分析工具：${toolName}` : "Agent 正在调用安全分析工具";
  }
  return "";
}

async function uploadAttachmentToDify(attachment) {
  const { buffer, name, mimeType } = parseAttachment(attachment);
  const baseUrl = normalizeDifyBaseUrl(DIFY_BASE_URL).replace(/\/$/, "");
  const formData = new FormData();
  formData.append("user", DIFY_USER);
  formData.append("file", new Blob([buffer], { type: mimeType }), name);

  const data = await fetchJsonWithTimeout(`${baseUrl}/files/upload`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${DIFY_API_KEY}`,
    },
    body: formData,
  });

  if (!data?.id) {
    throw new Error(`附件 ${name} 已上传，但 Dify 未返回文件 ID。`);
  }

  return {
    type: inferDifyFileType(name, mimeType),
    transfer_method: "local_file",
    upload_file_id: data.id,
  };
}

async function uploadAttachmentsToDify(attachments, response) {
  if (!Array.isArray(attachments) || !attachments.length) return [];
  if (attachments.length > MAX_ATTACHMENT_COUNT) {
    throw new Error(`一次最多上传 ${MAX_ATTACHMENT_COUNT} 个附件。`);
  }

  const parsed = attachments.map(parseAttachment);

  const files = [];
  for (let index = 0; index < attachments.length; index += 1) {
    writeStreamChunk(response, {
      type: "status",
      message: `正在上传附件 ${index + 1}/${attachments.length}：${parsed[index].name}`,
    });
    files.push(await uploadAttachmentToDify(attachments[index]));
  }
  return files;
}

async function callDifyBlocking(message, conversationId, files = []) {
  const endpoint = `${normalizeDifyBaseUrl(DIFY_BASE_URL).replace(/\/$/, "")}/chat-messages`;
  const payload = {
    inputs: {},
    query: composeStrictMarkdownQuery(message),
    response_mode: "blocking",
    user: DIFY_USER,
  };

  if (files.length) {
    payload.files = files;
  }

  if (conversationId && isValidUuid(conversationId)) {
    payload.conversation_id = conversationId;
  }

  const data = await fetchJsonWithTimeout(endpoint, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${DIFY_API_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(payload),
  });

  return {
    answer: normalizeMarkdown(data.answer) || "Dify 已返回，但 answer 为空。",
    conversationId: data.conversation_id || "",
  };
}

async function callDifyStreaming(message, conversationId, response, attachments = []) {
  const endpoint = `${normalizeDifyBaseUrl(DIFY_BASE_URL).replace(/\/$/, "")}/chat-messages`;
  const files = await uploadAttachmentsToDify(attachments, response);
  const payload = {
    inputs: {},
    query: composeStrictMarkdownQuery(message),
    response_mode: "streaming",
    user: DIFY_USER,
  };

  if (files.length) {
    payload.files = files;
  }

  if (conversationId && isValidUuid(conversationId)) {
    payload.conversation_id = conversationId;
  }

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), DIFY_TIMEOUT_MS);

  try {
    const upstream = await fetch(endpoint, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${DIFY_API_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(payload),
      signal: controller.signal,
    });

    if (!upstream.ok || !upstream.body) {
      const detail = await upstream.text();
      throw new Error(`Dify 返回 ${upstream.status}: ${detail}`);
    }

    const reader = upstream.body.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";
    let latestConversationId = conversationId || "";
    let rawAnswer = "";
    let emittedAnswer = "";

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const segments = buffer.split(/\r?\n\r?\n/);
      buffer = segments.pop() || "";

      for (const segment of segments) {
        const line = segment.split(/\r?\n/).find((item) => item.trimStart().startsWith("data:"));
        if (!line) continue;

        const raw = line.trimStart().slice(5).trim();
        if (!raw) continue;
        if (raw === "[DONE]") {
          const finalAnswer = normalizeMarkdown(rawAnswer || emittedAnswer)
            || "Dify 工作流已完成，但没有返回可展示内容。";
          writeStreamChunk(response, {
            type: "done",
            answer: finalAnswer,
            conversationId: latestConversationId,
          });
          await reader.cancel();
          return;
        }

        const event = JSON.parse(raw);
        const progressMessage = describeDifyProgress(event);
        if (progressMessage) {
          writeStreamChunk(response, { type: "status", message: progressMessage });
        }

        if (event.conversation_id && event.conversation_id !== latestConversationId) {
          latestConversationId = event.conversation_id;
          writeStreamChunk(response, {
            type: "conversation",
            conversationId: latestConversationId,
          });
        }

        if ((event.event === "message" || event.event === "agent_message") && event.answer) {
          rawAnswer += event.answer;
          const cleaned = normalizeMarkdown(rawAnswer);
          if (cleaned !== emittedAnswer) {
            emittedAnswer = cleaned;
            writeStreamChunk(response, {
              type: "replace",
              answer: cleaned,
            });
          }
        }

        if (event.event === "workflow_finished") {
          const workflowAnswer = extractWorkflowAnswer(event);
          if (workflowAnswer) {
            rawAnswer = workflowAnswer;
            emittedAnswer = workflowAnswer;
            writeStreamChunk(response, {
              type: "replace",
              answer: workflowAnswer,
            });
          }
        }

        if (event.event === "message_end") {
          const finalAnswer = normalizeMarkdown(rawAnswer || emittedAnswer || event.answer || extractWorkflowAnswer(event))
            || "Dify 工作流已完成，但没有返回可展示内容。";
          writeStreamChunk(response, {
            type: "done",
            answer: finalAnswer,
            conversationId: latestConversationId,
          });
          await reader.cancel();
          return;
        }
      }
    }

    if (!rawAnswer.trim() && !emittedAnswer.trim()) {
      const fallback = await callDifyBlocking(message, conversationId, files);
      writeStreamChunk(response, {
        type: "done",
        answer: fallback.answer,
        conversationId: fallback.conversationId,
      });
    }
  } catch (error) {
    if (error.name === "AbortError") {
      throw new Error(`Dify 请求超时：${DIFY_TIMEOUT_MS / 1000} 秒内没有返回结果。请检查 SSH 隧道和 Dify 服务状态。`);
    }
    throw error;
  } finally {
    clearTimeout(timeoutId);
  }
}

async function runWorkflowQuery(type, value) {
  const config = WORKFLOW_CONFIG[type];
  if (!config) {
    throw new Error(`未识别的查询类型：${type}`);
  }

  const availableApps = config.apps.filter((app) => app.apiKey);
  if (!availableApps.length) {
    throw new Error(`${config.label}尚未配置可用的 workflow API Key`);
  }

  const results = [];
  for (const app of availableApps) {
    results.push(await callWorkflowApp(app, type, value));
  }

  return mergeWorkflowAnswers(type, value, results);
}

function mockAnswer(message) {
  return normalizeMarkdown(
    [
      "## 本地模拟模式",
      "",
      `你刚才提交的内容：${message}`,
      "",
      "- 当前未配置真实 Dify 接口",
      "- 可先验证前端流式显示是否正常",
    ].join("\n"),
  );
}

const server = http.createServer(async (request, response) => {
  const requestUrl = new URL(request.url, `http://${request.headers.host}`);

  if (request.method === "GET" && requestUrl.pathname === "/api/health") {
    const agentConfigured = Boolean(DIFY_BASE_URL && DIFY_API_KEY);
    const configuredTools = {
      ipVt: Boolean(process.env.DIFY_WORKFLOW_IP_VT_API_KEY),
      ipWeibu: Boolean(process.env.DIFY_WORKFLOW_IP_WEIBU_API_KEY),
      domain: Boolean(process.env.DIFY_WORKFLOW_DOMAIN_API_KEY),
      url: Boolean(process.env.DIFY_WORKFLOW_URL_API_KEY),
      cve: Boolean(process.env.DIFY_WORKFLOW_CVE_API_KEY),
      hash: Boolean(process.env.DIFY_WORKFLOW_HASH_API_KEY),
      iocAggregate: Boolean(AUXILIARY_TOOL_CONFIG.iocAggregate.apiKey),
      localAttack: Boolean(AUXILIARY_TOOL_CONFIG.localAttack.apiKey),
    };
    const probeTargets = {
      agent: DIFY_API_KEY,
      ipVt: process.env.DIFY_WORKFLOW_IP_VT_API_KEY,
      ipWeibu: process.env.DIFY_WORKFLOW_IP_WEIBU_API_KEY,
      domain: process.env.DIFY_WORKFLOW_DOMAIN_API_KEY,
      url: process.env.DIFY_WORKFLOW_URL_API_KEY,
      cve: process.env.DIFY_WORKFLOW_CVE_API_KEY,
      hash: process.env.DIFY_WORKFLOW_HASH_API_KEY,
      iocAggregate: AUXILIARY_TOOL_CONFIG.iocAggregate.apiKey,
      localAttack: AUXILIARY_TOOL_CONFIG.localAttack.apiKey,
    };
    const probeEntries = await Promise.all(
      Object.entries(probeTargets).map(async ([key, apiKey]) => [key, await probeDifyService(apiKey)]),
    );
    const readiness = Object.fromEntries(probeEntries);
    const difyReachable = Object.values(readiness).some(Boolean);
    const agentReady = readiness.agent;
    const tools = Object.fromEntries(Object.entries(readiness).filter(([key]) => key !== "agent"));
    const configuredWorkflows = {
      ip: configuredTools.ipVt || configuredTools.ipWeibu,
      domain: configuredTools.domain,
      url: configuredTools.url,
      cve: configuredTools.cve,
      hash: configuredTools.hash,
    };
    const workflows = {
      ip: tools.ipVt || tools.ipWeibu,
      domain: tools.domain,
      url: tools.url,
      cve: tools.cve,
      hash: tools.hash,
    };

    sendJson(response, 200, {
      ok: true,
      mode: !agentConfigured ? "mock" : agentReady ? "dify" : "offline",
      difyReachable,
      agent: {
        configured: agentConfigured,
        ready: agentReady,
        name: "多模态自研Agent策略的省网智能体v1.0（不含缓存）",
      },
      streaming: true,
      difyBaseUrl: normalizeDifyBaseUrl(DIFY_BASE_URL),
      timeoutMs: DIFY_TIMEOUT_MS,
      configuredWorkflows,
      workflows,
      configuredTools,
      tools,
    });
    return;
  }

  if (request.method === "POST" && requestUrl.pathname === "/api/chat/stream") {
    response.writeHead(200, {
      "Content-Type": "application/x-ndjson; charset=utf-8",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
      "Transfer-Encoding": "chunked",
    });

    let heartbeatId;
    try {
      const body = await collectRequestBody(request);
      if (!body.message) {
        writeStreamChunk(response, { type: "error", error: "message 不能为空" });
        response.end();
        return;
      }

      writeStreamChunk(response, { type: "status", message: "请求已接收，正在准备分析" });
      const startedAt = Date.now();
      heartbeatId = setInterval(() => {
        const elapsedSeconds = Math.floor((Date.now() - startedAt) / 1000);
        writeStreamChunk(response, {
          type: "status",
          message: `后端仍在处理中，已等待 ${elapsedSeconds} 秒`,
        });
      }, 10000);

      if (DIFY_BASE_URL && DIFY_API_KEY) {
        await callDifyStreaming(
          body.message,
          body.conversationId || "",
          response,
          body.attachments || [],
        );
      } else {
        const answer = mockAnswer(body.message);
        writeStreamChunk(response, { type: "replace", answer });
        writeStreamChunk(response, { type: "done", answer, conversationId: "" });
      }
    } catch (error) {
      writeStreamChunk(response, { type: "error", error: error.message });
    } finally {
      clearInterval(heartbeatId);
    }

    response.end();
    return;
  }

  if (request.method === "POST" && requestUrl.pathname === "/api/query/stream") {
    response.writeHead(200, {
      "Content-Type": "application/x-ndjson; charset=utf-8",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
      "Transfer-Encoding": "chunked",
    });

    let heartbeatId;
    try {
      const body = await collectRequestBody(request);
      if (!body.type || !body.value) {
        writeStreamChunk(response, { type: "error", error: "type 和 value 不能为空" });
        response.end();
        return;
      }

      const config = WORKFLOW_CONFIG[body.type];
      writeStreamChunk(response, { type: "status", message: `正在调用${config?.label || body.type}` });
      const startedAt = Date.now();
      heartbeatId = setInterval(() => {
        const elapsedSeconds = Math.floor((Date.now() - startedAt) / 1000);
        writeStreamChunk(response, {
          type: "status",
          message: `查询仍在处理中，已等待 ${elapsedSeconds} 秒`,
        });
      }, 10000);
      const answer = await runWorkflowQuery(body.type, body.value);
      writeStreamChunk(response, { type: "replace", answer });
      writeStreamChunk(response, { type: "done", answer });
    } catch (error) {
      writeStreamChunk(response, { type: "error", error: error.message });
    } finally {
      clearInterval(heartbeatId);
    }

    response.end();
    return;
  }

  if (request.method === "GET" && requestUrl.pathname === "/favicon.ico") {
    response.writeHead(204);
    response.end();
    return;
  }

  const safePath = requestUrl.pathname === "/" ? "/index.html" : requestUrl.pathname;
  const filePath = path.normalize(path.join(__dirname, safePath));

  if (!filePath.startsWith(__dirname)) {
    sendJson(response, 403, { error: "禁止访问该路径" });
    return;
  }

  serveStaticFile(filePath, response);
});

server.listen(PORT, () => {
  console.log(`Server running at http://localhost:${PORT}`);
});
