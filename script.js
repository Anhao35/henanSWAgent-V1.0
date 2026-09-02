const state = {
  sessions: [],
  activeSessionId: null,
  pendingAttachments: [],
  loading: false,
};

const MAX_ATTACHMENT_COUNT = 5;
const MAX_ATTACHMENT_SIZE = 15 * 1024 * 1024;
const SUPPORTED_ATTACHMENT_EXTENSIONS = new Set([
  ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg",
  ".pdf", ".doc", ".docx", ".txt", ".md", ".csv", ".xls", ".xlsx",
  ".ppt", ".pptx", ".html", ".json", ".xml", ".yaml", ".yml",
]);

const messageList = document.getElementById("messageList");
const historyList = document.getElementById("historyList");
const historyTemplate = document.getElementById("historyItemTemplate");
const chatForm = document.getElementById("chatForm");
const messageInput = document.getElementById("messageInput");
const sendButton = document.getElementById("sendButton");
const newChatButton = document.getElementById("newChatButton");
const attachmentInput = document.getElementById("attachmentInput");
const attachmentButton = document.getElementById("attachmentButton");
const attachmentList = document.getElementById("attachmentList");
const quickQueryButtons = document.querySelectorAll(".quick-query");
const queryDialog = document.getElementById("queryDialog");
const queryDialogForm = document.getElementById("queryDialogForm");
const queryDialogTitle = document.getElementById("queryDialogTitle");
const queryDialogLabel = document.getElementById("queryDialogLabel");
const queryDialogInput = document.getElementById("queryDialogInput");
const queryDialogClose = document.getElementById("queryDialogClose");
const queryDialogCancel = document.getElementById("queryDialogCancel");

const quickQueryMeta = {
  ip: {
    label: "IP查询",
    prompt: "请输入要查询的 IP",
    placeholder: "例如：8.8.8.8",
  },
  domain: {
    label: "域名查询",
    prompt: "请输入要查询的域名",
    placeholder: "例如：baidu.com",
  },
  url: {
    label: "URL查询",
    prompt: "请输入要查询的 URL",
    placeholder: "例如：https://example.com",
  },
  cve: {
    label: "CVE查询",
    prompt: "请输入要查询的 CVE 编号",
    placeholder: "例如：CVE-2024-0001",
  },
  hash: {
    label: "Hash查询",
    prompt: "请输入要查询的文件 Hash",
    placeholder: "例如：MD5、SHA-1 或 SHA-256",
  },
};

if (window.marked) {
  marked.setOptions({
    breaks: false,
    gfm: true,
  });
}

function escapeHtml(text) {
  return String(text)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function normalizeMarkdown(text) {
  let output = String(text || "").replace(/\r\n/g, "\n");
  output = trimReasoningPrefix(output).replace(/\u200b/g, "");
  output = output
    .split("\n")
    .map((line) => line.replace(/[ \t]+$/g, ""))
    .join("\n");
  output = mergeWrappedLines(output);
  output = output.replace(/\n{3,}/g, "\n\n");
  return output.trim();
}

function trimReasoningPrefix(text) {
  const markers = [
    "根据提供的查询结果和信息汇总，以下是关于",
    "以下是关于",
    "### 一、基本信息",
    "###一、基本信息",
    "一、基本信息",
  ];

  for (const marker of markers) {
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
  const shouldAddSpace = /[A-Za-z0-9]/.test(prevTail) && /[A-Za-z0-9]/.test(currentHead);
  return shouldAddSpace ? `${prev} ${current}` : `${prev}${current}`;
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

function markdownToHtml(text) {
  const normalized = normalizeMarkdown(text);
  if (window.marked) {
    return marked.parse(normalized);
  }
  return `<p>${escapeHtml(normalized).replace(/\n/g, "<br />")}</p>`;
}

function disposeRenderedCharts() {
  if (!window.echarts) return;
  messageList.querySelectorAll(".echart-canvas").forEach((element) => {
    window.echarts.getInstanceByDom(element)?.dispose();
  });
}

function normalizeEchartsCode(code) {
  let normalized = String(code || "")
    .replace(/^\uFEFF/, "")
    .replace(/[“”]/g, '"')
    .replace(/[‘’]/g, "'")
    .trim();

  normalized = normalized
    .replace(/^(?:const|let|var)\s+option\s*=\s*/i, "")
    .replace(/^option\s*=\s*/i, "")
    .replace(/;\s*$/, "");

  normalized = normalized
    .split("\n")
    .map((line) => line
      .replace(/^(\s*)-\s+\*\*("?[\w$]+"?)\*\*\s*:\s*/, "$1$2: ")
      .replace(/\*\*/g, ""))
    .join("\n")
    .replace(/,\s*([}\]])/g, "$1");

  return normalized;
}

function parseEchartsOption(code) {
  const normalized = normalizeEchartsCode(code);
  if (!normalized.startsWith("{") || !normalized.endsWith("}")) return null;

  try {
    const option = JSON.parse(normalized);
    if (!option || typeof option !== "object" || !Array.isArray(option.series)) return null;

    const supported = option.xAxis
      || option.yAxis
      || option.radar
      || option.geo
      || option.calendar
      || option.dataset
      || option.series.some((series) => ["pie", "gauge", "funnel", "treemap", "graph", "sankey"].includes(series?.type));
    return supported ? option : null;
  } catch {
    return null;
  }
}

function renderEchartsInBubble(bubble) {
  if (!window.echarts) return;

  bubble.querySelectorAll("pre code").forEach((codeBlock) => {
    const option = parseEchartsOption(codeBlock.textContent);
    if (!option) return;

    const figure = document.createElement("div");
    figure.className = "echart-figure";

    const canvas = document.createElement("div");
    canvas.className = "echart-canvas";
    canvas.setAttribute("role", "img");
    canvas.setAttribute("aria-label", option.title?.text || "风险可视化图表");
    figure.append(canvas);

    codeBlock.closest("pre")?.replaceWith(figure);
    bubble.closest(".message")?.classList.add("message--has-chart");
    const chart = window.echarts.init(canvas, null, { renderer: "canvas" });
    chart.setOption({
      animationDuration: 500,
      color: ["#2165d6", "#27b2f3", "#25b983", "#f2a93b", "#e35d6a"],
      ...option,
    });
  });
}

function classifyTableStatus(text) {
  if (/高风险|恶意|命中|危险|失败|阻断/i.test(text)) return "high";
  if (/中风险|可疑|警告|关注/i.test(text)) return "medium";
  if (/无风险|无明显风险|低风险|安全|正常|成功|未发现|未见风险|未命中|白名单|可信/i.test(text)) return "safe";
  return "unknown";
}

function enhanceMarkdownTables(bubble) {
  const tables = bubble.querySelectorAll(".message-markdown table");
  if (tables.length) bubble.closest(".message")?.classList.add("message--has-table");

  tables.forEach((table) => {
    if (table.parentElement?.classList.contains("markdown-table-wrap")) return;

    const headers = Array.from(table.querySelectorAll("thead th"));
    const headerTexts = headers.map((header) => header.textContent.replace(/\s+/g, "").trim());
    const columnClasses = headerTexts.map((text) => {
      if (/来源/.test(text)) return "column-source";
      if (/工具/.test(text)) return "column-tool";
      if (/查询状态|状态/.test(text)) return "column-status";
      if (/主体风险/.test(text)) return "column-subject-risk";
      if (/上下文风险/.test(text)) return "column-context-risk";
      if (/关键事实|证据|详情/.test(text)) return "column-facts";
      return "";
    });
    const isEvidenceTable = columnClasses.includes("column-source")
      && columnClasses.includes("column-tool")
      && columnClasses.includes("column-facts");

    table.classList.add("enhanced-markdown-table");
    if (isEvidenceTable) table.classList.add("evidence-table");

    headers.forEach((header, index) => {
      if (columnClasses[index]) header.classList.add(columnClasses[index]);
    });

    table.querySelectorAll("tbody tr").forEach((row) => {
      Array.from(row.cells).forEach((cell, index) => {
        const columnClass = columnClasses[index];
        if (columnClass) cell.classList.add(columnClass);

        if (["column-status", "column-subject-risk", "column-context-risk"].includes(columnClass)) {
          const value = cell.textContent.replace(/\s+/g, " ").trim();
          const pill = document.createElement("span");
          pill.className = `table-status-pill table-status-pill--${classifyTableStatus(value)}`;
          pill.textContent = value || "—";
          cell.replaceChildren(pill);
        }
      });
    });

    const wrapper = document.createElement("div");
    wrapper.className = "markdown-table-wrap";
    wrapper.tabIndex = 0;
    wrapper.setAttribute("role", "region");
    wrapper.setAttribute("aria-label", isEvidenceTable ? "证据来源表，可横向滚动" : "数据表，可横向滚动");
    table.parentNode.insertBefore(wrapper, table);
    wrapper.append(table);
  });
}

window.addEventListener("resize", () => {
  if (!window.echarts) return;
  messageList.querySelectorAll(".echart-canvas").forEach((element) => {
    window.echarts.getInstanceByDom(element)?.resize();
  });
});

function formatFileSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function fileToDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ""));
    reader.onerror = () => reject(new Error(`无法读取文件：${file.name}`));
    reader.readAsDataURL(file);
  });
}

function renderAttachmentList() {
  attachmentList.innerHTML = "";

  state.pendingAttachments.forEach((attachment) => {
    const chip = document.createElement("div");
    chip.className = "attachment-chip";

    const name = document.createElement("span");
    name.className = "attachment-chip__name";
    name.textContent = attachment.name;
    name.title = attachment.name;

    const size = document.createElement("span");
    size.className = "attachment-chip__size";
    size.textContent = formatFileSize(attachment.size);

    const remove = document.createElement("button");
    remove.className = "attachment-chip__remove";
    remove.type = "button";
    remove.setAttribute("aria-label", `移除 ${attachment.name}`);
    remove.textContent = "×";
    remove.addEventListener("click", () => {
      state.pendingAttachments = state.pendingAttachments.filter((item) => item.id !== attachment.id);
      renderAttachmentList();
    });

    chip.append(name, size, remove);
    attachmentList.append(chip);
  });
}

function isSupportedAttachment(file) {
  const name = String(file.name || "").toLowerCase();
  const extensionIndex = name.lastIndexOf(".");
  const extension = extensionIndex >= 0 ? name.slice(extensionIndex) : "";
  const mimeType = String(file.type || "").toLowerCase();
  return mimeType.startsWith("image/")
    || mimeType.startsWith("text/")
    || mimeType === "application/pdf"
    || SUPPORTED_ATTACHMENT_EXTENSIONS.has(extension);
}

async function addAttachments(fileList) {
  if (state.loading) return;

  const files = Array.from(fileList || []);
  if (!files.length) return;
  attachmentInput.value = "";

  if (state.pendingAttachments.length + files.length > MAX_ATTACHMENT_COUNT) {
    window.alert("当前上传文件数量超过5个，请重新上传！");
    return;
  }

  if (files.some((file) => !isSupportedAttachment(file))) {
    window.alert("当前上传的文件不是当前后端支持的类型，请重新上传！");
    return;
  }

  if (files.some((file) => file.size > MAX_ATTACHMENT_SIZE)) {
    window.alert("单个文件大小超过15MB，请重新上传！");
    return;
  }

  try {
    const attachments = await Promise.all(files.map(async (file) => ({
      id: crypto.randomUUID(),
      name: file.name || `粘贴的文件-${Date.now()}`,
      type: file.type || "application/octet-stream",
      size: file.size,
      dataUrl: await fileToDataUrl(file),
    })));

    state.pendingAttachments.push(...attachments);
    renderAttachmentList();
  } catch (error) {
    console.error(error);
  }
}

function createSession(title = "新会话") {
  const session = {
    id: crypto.randomUUID(),
    conversationId: "",
    title,
    createdAt: new Date(),
    messages: [
      {
        role: "assistant",
        content: "## 准备就绪\n\n请输入分析目标，或使用左侧快捷查询。",
      },
    ],
  };

  state.sessions.unshift(session);
  state.activeSessionId = session.id;
  state.pendingAttachments = [];
  renderAttachmentList();
  render();
}

function getActiveSession() {
  return state.sessions.find((item) => item.id === state.activeSessionId) || null;
}

function formatTime(date) {
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

function buildSessionTitle(text) {
  const clean = text.replace(/\s+/g, " ").trim();
  return clean.length > 24 ? `${clean.slice(0, 24)}...` : clean || "新会话";
}

function renderMessageMeta(message) {
  if (message.role !== "assistant") return "";

  const badges = [];
  if (/风险|恶意|威胁|漏洞|CVE/i.test(message.content)) {
    badges.push('<span class="message-badge"><i class="badge-icon badge-icon--risk"></i>风险研判</span>');
  }
  if (/\|.+\|/.test(message.content)) {
    badges.push('<span class="message-badge"><i class="badge-icon badge-icon--table"></i>表格输出</span>');
  }
  if (!badges.length) {
    badges.push('<span class="message-badge"><i class="badge-icon badge-icon--report"></i>分析报告</span>');
  }

  return `<div class="message__meta">${badges.join("")}</div>`;
}

function findPreviousUserMessage(messages, startIndex) {
  for (let index = startIndex - 1; index >= 0; index -= 1) {
    if (messages[index].role === "user") return messages[index];
  }
  return null;
}

function renderMessages() {
  const session = getActiveSession();
  if (!session) {
    messageList.innerHTML = "";
    return;
  }

  disposeRenderedCharts();
  messageList.innerHTML = "";

  session.messages.forEach((message, messageIndex) => {
    const article = document.createElement("article");
    article.className = `message message--${message.role}`;
    if (message.pending) article.classList.add("message--pending");

    const avatar = document.createElement("div");
    avatar.className = "message__avatar";
    avatar.innerHTML = message.role === "assistant"
      ? '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2 4 5v6c0 5.2 3.4 10 8 11 4.6-1 8-5.8 8-11V5l-8-3Z" fill="none" stroke="currentColor" stroke-width="1.8"/><path d="M9 12.2 11 14l4-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>'
      : '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm0 2c-4.4 0-8 2.1-8 4.7V22h16v-3.3C20 16.1 16.4 14 12 14Z" fill="currentColor"/></svg>';

    const content = document.createElement("div");
    content.className = "message__content";

    const name = document.createElement("p");
    name.className = "message__name";
    name.textContent = message.role === "assistant" ? "网络安全垂域智能体" : "当前用户";

    const bubble = document.createElement("div");
    bubble.className = "message__bubble";

    if (message.role === "assistant") {
      bubble.classList.add("message__bubble--markdown");
      bubble.innerHTML = `${renderMessageMeta(message)}<div class="message-markdown">${markdownToHtml(message.content)}</div>`;
    } else {
      const text = document.createElement("div");
      text.textContent = message.content;
      bubble.append(text);

      if (message.attachments?.length) {
        const attachments = document.createElement("div");
        attachments.className = "message-attachments";
        message.attachments.forEach((attachment) => {
          const item = document.createElement("span");
          item.className = "message-attachment";

          const itemName = document.createElement("strong");
          itemName.textContent = attachment.name;
          itemName.title = attachment.name;

          const itemSize = document.createElement("span");
          itemSize.textContent = formatFileSize(attachment.size);

          item.append(itemName, itemSize);
          attachments.append(item);
        });
        bubble.append(attachments);
      }
    }

    content.append(name, bubble);

    const previousUserMessage = findPreviousUserMessage(session.messages, messageIndex);
    if (message.role === "assistant" && !message.pending && previousUserMessage) {
      const actions = document.createElement("div");
      actions.className = "message__actions";

      const copyButton = document.createElement("button");
      copyButton.className = "message-action-button";
      copyButton.type = "button";
      copyButton.title = "复制回答";
      copyButton.setAttribute("aria-label", "复制回答");
      copyButton.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="8" y="8" width="11" height="11" rx="2" fill="none" stroke="currentColor" stroke-width="1.8"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2" fill="none" stroke="currentColor" stroke-width="1.8"/></svg>';
      copyButton.addEventListener("click", async () => {
        try {
          await copyText(message.content);
          copyButton.classList.add("is-success");
          copyButton.title = "已复制";
          window.setTimeout(() => {
            copyButton.classList.remove("is-success");
            copyButton.title = "复制回答";
          }, 1600);
        } catch {
          copyButton.title = "复制失败";
        }
      });

      const regenerateButton = document.createElement("button");
      regenerateButton.className = "message-action-button regenerate-button";
      regenerateButton.type = "button";
      regenerateButton.title = "重新回答";
      regenerateButton.setAttribute("aria-label", "重新回答");
      regenerateButton.disabled = state.loading;
      regenerateButton.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 7v5h-5M4 17v-5h5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/><path d="M18.2 9A7 7 0 0 0 6.4 6.4L4 9m16 6-2.4 2.6A7 7 0 0 1 5.8 15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>';
      regenerateButton.addEventListener("click", () => {
        regenerateAnswer(messageIndex, previousUserMessage);
      });

      actions.append(copyButton, regenerateButton);
      content.append(actions);
    }

    article.append(avatar, content);
    messageList.append(article);
    if (message.role === "assistant") {
      enhanceMarkdownTables(bubble);
      renderEchartsInBubble(bubble);
    }
  });

  messageList.scrollTop = messageList.scrollHeight;
}

async function copyText(text) {
  if (navigator.clipboard && window.isSecureContext) {
    await navigator.clipboard.writeText(text);
    return;
  }

  const fallback = document.createElement("textarea");
  fallback.value = text;
  fallback.setAttribute("readonly", "");
  fallback.style.position = "fixed";
  fallback.style.opacity = "0";
  document.body.append(fallback);
  fallback.select();
  const copied = document.execCommand("copy");
  fallback.remove();
  if (!copied) throw new Error("copy failed");
}

function renderHistory() {
  historyList.innerHTML = "";

  if (!state.sessions.length) {
    const empty = document.createElement("div");
    empty.className = "empty-history";
    empty.textContent = "当前还没有会话记录，发送一条消息后会自动保存。";
    historyList.append(empty);
    return;
  }

  state.sessions.forEach((session) => {
    const node = historyTemplate.content.firstElementChild.cloneNode(true);
    node.dataset.id = session.id;
    node.querySelector(".history-item__title").textContent = session.title;
    node.querySelector(".history-item__time").textContent = formatTime(session.createdAt);

    if (session.id === state.activeSessionId) {
      node.classList.add("history-item--active");
    }

    node.addEventListener("click", () => {
      state.activeSessionId = session.id;
      render();
    });

    historyList.append(node);
  });
}

function render() {
  renderMessages();
  renderHistory();
}

function pushMessage(role, content, attachments = []) {
  const session = getActiveSession();
  if (!session) return;

  if (session.title === "新会话" && role === "user") {
    session.title = buildSessionTitle(content);
  }

  session.messages.push({ role, content, attachments });
  render();
}

function setLoading(loading) {
  state.loading = loading;
  sendButton.disabled = loading;
  sendButton.textContent = loading ? "分析中，请耐心等待..." : "发送分析请求";
  attachmentButton.disabled = loading;
  attachmentInput.disabled = loading;
  document.querySelectorAll(".regenerate-button").forEach((button) => {
    button.disabled = loading;
  });
}

async function detectBackend() {
  try {
    const response = await fetch("/api/health");
    if (!response.ok) throw new Error("health check failed");

    const data = await response.json();
    quickQueryButtons.forEach((button) => {
      const available = Boolean(data.workflows?.[button.dataset.type]);
      button.disabled = !available;
      button.title = available ? "" : "尚未配置该工作流的 API Key";
    });

  } catch {
    quickQueryButtons.forEach((button) => {
      button.disabled = true;
      button.title = "后端服务未连接";
    });
  }
}

async function streamIntoMessage(endpoint, payload, typingMessage, session) {
  const response = await fetch(endpoint, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(payload),
  });

  if (!response.ok || !response.body) {
    let errorMessage = `请求失败，状态码 ${response.status}`;
    try {
      const errorData = await response.json();
      if (errorData.error) errorMessage = errorData.error;
    } catch {
      // ignore parse error
    }
    throw new Error(errorMessage);
  }

  typingMessage.content = "";
  render();

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  let hasAnswer = false;

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split("\n");
    buffer = lines.pop() || "";

    for (const line of lines) {
      if (!line.trim()) continue;
      const event = JSON.parse(line);

      if (event.type === "replace" && typeof event.answer === "string") {
        hasAnswer = true;
        typingMessage.content = event.answer;
      } else if (event.type === "chunk") {
        hasAnswer = true;
        typingMessage.content += event.delta;
      } else if (event.type === "conversation") {
        session.conversationId = event.conversationId || session.conversationId;
      } else if (event.type === "done" && event.answer) {
        hasAnswer = true;
        typingMessage.content = event.answer;
      } else if (event.type === "status" && !hasAnswer) {
        typingMessage.content = `## 正在处理\n\n- ${event.message}\n- 复杂查询可能需要数分钟，请勿关闭页面`;
      } else if (event.type === "error") {
        throw new Error(event.error);
      }

      render();
    }
  }
}

async function sendMessage(content, attachments = []) {
  pushMessage("user", content, attachments);

  const session = getActiveSession();
  const typingMessage = {
    role: "assistant",
    pending: true,
    content: [
      "## 正在分析",
      "",
      "- 已接收请求",
      "- 正在调用后端流式接口",
      "- 请稍候...",
    ].join("\n"),
  };

  session.messages.push(typingMessage);
  render();
  setLoading(true);

  try {
    await streamIntoMessage(
      "/api/chat/stream",
      {
        message: content,
        sessionId: session.id,
        conversationId: session.conversationId,
        attachments,
      },
      typingMessage,
      session,
    );

    if (!typingMessage.content.trim()) {
      typingMessage.content = "后端已返回，但没有拿到有效内容。";
    }
    typingMessage.pending = false;
    render();
  } catch (error) {
    typingMessage.pending = false;
    typingMessage.content = [
      "## 调用后端失败",
      "",
      `> ${error.message}`,
      "",
      "- 请确认本地服务已启动",
      "- 请确认 SSH 隧道可用",
      "- 请确认 Dify 配置正确",
    ].join("\n");
    render();
  } finally {
    setLoading(false);
  }
}

async function regenerateAnswer(messageIndex, userMessage) {
  if (state.loading) return;

  const session = getActiveSession();
  const answerMessage = session?.messages[messageIndex];
  if (!session || !answerMessage || answerMessage.role !== "assistant") return;

  answerMessage.pending = true;
  answerMessage.content = [
    "## 正在重新分析",
    "",
    "- 已重新提交当前问题",
    "- 正在等待后端返回",
  ].join("\n");
  render();
  setLoading(true);

  try {
    const isolatedSession = { conversationId: "" };
    await streamIntoMessage(
      "/api/chat/stream",
      {
        message: userMessage.content,
        sessionId: session.id,
        conversationId: "",
        attachments: userMessage.attachments || [],
      },
      answerMessage,
      isolatedSession,
    );

    if (!answerMessage.content.trim()) {
      answerMessage.content = "后端已返回，但没有拿到有效内容。";
    }
    answerMessage.pending = false;
    render();
  } catch (error) {
    answerMessage.pending = false;
    answerMessage.content = [
      "## 重新回答失败",
      "",
      `> ${error.message}`,
      "",
      "- 请确认本地服务与 Dify 服务正常",
    ].join("\n");
    render();
  } finally {
    setLoading(false);
  }
}

async function runQuickQuery(type, rawValue) {
  const meta = quickQueryMeta[type];
  const value = rawValue.trim();
  if (!value) return;

  pushMessage("user", `${meta.label}：${value}`);

  const session = getActiveSession();
  const typingMessage = {
    role: "assistant",
    pending: true,
    content: `## ${meta.label}进行中\n\n- 已提交到对应 workflow\n- 正在等待流式结果`,
  };

  session.messages.push(typingMessage);
  render();
  setLoading(true);

  try {
    await streamIntoMessage("/api/query/stream", { type, value }, typingMessage, session);

    if (!typingMessage.content.trim()) {
      typingMessage.content = `## ${meta.label}\n\n未获取到有效结果。`;
    }
    typingMessage.pending = false;
    render();
  } catch (error) {
    typingMessage.pending = false;
    typingMessage.content = [
      `## ${meta.label}失败`,
      "",
      `> ${error.message}`,
      "",
      "- 请确认该 workflow 的 API Key 已配置",
      "- 请确认 Dify 服务正常",
    ].join("\n");
    render();
  } finally {
    setLoading(false);
  }
}

chatForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const typedContent = messageInput.value.trim();
  const attachments = state.pendingAttachments;
  if (!typedContent && !attachments.length) return;

  const content = typedContent || "请分析已上传的附件。";
  messageInput.value = "";
  state.pendingAttachments = [];
  renderAttachmentList();
  await sendMessage(content, attachments);
});

newChatButton.addEventListener("click", () => {
  createSession();
});

attachmentButton.addEventListener("click", () => {
  attachmentInput.click();
});

attachmentInput.addEventListener("change", () => {
  addAttachments(attachmentInput.files);
});

messageInput.addEventListener("paste", (event) => {
  const files = Array.from(event.clipboardData?.files || []);
  if (files.length) addAttachments(files);
});

["dragenter", "dragover"].forEach((eventName) => {
  messageInput.addEventListener(eventName, (event) => {
    event.preventDefault();
    messageInput.classList.add("is-dragging");
  });
});

["dragleave", "drop"].forEach((eventName) => {
  messageInput.addEventListener(eventName, (event) => {
    event.preventDefault();
    messageInput.classList.remove("is-dragging");
    if (eventName === "drop" && event.dataTransfer?.files?.length) {
      addAttachments(event.dataTransfer.files);
    }
  });
});

quickQueryButtons.forEach((button) => {
  button.addEventListener("click", () => {
    const type = button.dataset.type;
    const meta = quickQueryMeta[type];
    queryDialog.dataset.type = type;
    queryDialogTitle.textContent = meta.label;
    queryDialogLabel.textContent = meta.prompt;
    queryDialogInput.placeholder = meta.placeholder;
    queryDialogInput.value = "";
    queryDialog.showModal();
    window.setTimeout(() => queryDialogInput.focus(), 0);
  });
});

queryDialogForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const type = queryDialog.dataset.type;
  const value = queryDialogInput.value.trim();
  if (!value || !quickQueryMeta[type]) return;
  queryDialog.close();
  await runQuickQuery(type, value);
});

[queryDialogClose, queryDialogCancel].forEach((button) => {
  button.addEventListener("click", () => queryDialog.close());
});

queryDialog.addEventListener("click", (event) => {
  if (event.target === queryDialog) queryDialog.close();
});

createSession();
detectBackend();
