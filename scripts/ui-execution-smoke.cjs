// UI contract smoke test: all API responses are mocked, no account/model changes.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright');
const assert = require('node:assert/strict');
const path = require('node:path');
let activeBrowser;
(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  activeBrowser = browser;
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let conversation = false, submitted;
  const history = Array.from({ length: 35 }, (_, i) => ({ id: 'history-' + i, title: '历史研判记录 ' + (i + 1), createdAt: '2026-09-04T08:00:00Z' }));
  const runId = '00000000-0000-0000-0000-000000000123';
  const convId = '00000000-0000-0000-0000-000000000456';
  const reportId = '00000000-0000-0000-0000-000000000789';
  const report = { id: reportId, rootId: reportId, version: 1, title: '安全研判报告', status: 'DRAFT', content: '# 安全研判报告\n\n## 证据快照\n\n仅为界面验收数据。', templateName: '详细技术报告', templateVersion: 1, snapshotSha256: 'a'.repeat(64) };
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()); let data = {};
    if (url.pathname === '/api/auth/me') data = { id: 'test-user', username: 'tester', displayName: '界面验收', role: 'SUPER_ADMIN', organizationName: '隔离测试' };
    else if (url.pathname.endsWith('/auth/csrf')) data = { token: 'test-csrf' };
    else if (url.pathname === '/api/conversations') {
      if (req.method() === 'POST') { conversation = true; data = { id: convId, title: '新会话' }; }
      else data = [...(conversation ? [{ id: convId, title: '界面验收任务' }] : []), ...history];
    } else if (url.pathname === '/api/conversations/history-0/messages') data = [
      { id: 'old-user', role: 'USER', content: '请展示长报告以检查滚动布局。', status: 'COMPLETED' },
      { id: 'old-answer', role: 'ASSISTANT', content: Array.from({ length: 30 }, (_, i) => `## 验收章节 ${i + 1}\n\n这段文字仅用于界面布局验收。长回答滚动时，校园背景和输入框应保持原位。\n\n`).join(''), status: 'COMPLETED' }
    ];
    else if (url.pathname.endsWith('/messages')) data = [];
    else if (url.pathname.endsWith('/messages/stream')) {
      submitted = req.postDataJSON();
      await route.fulfill({ status: 200, contentType: 'application/x-ndjson', body: [
        { type: 'accepted', runId, messageId: 'message-test', taskMode: submitted.taskMode },
        { type: 'done', answer: '这是资料解读结果，链接未提交 IOC 研判。', status: 'COMPLETED' }
      ].map(x => JSON.stringify(x)).join('\n') + '\n' }); return;
    } else if (url.pathname === '/api/runs') data = [];
    else if (url.pathname === '/api/runs/' + runId) data = { id: runId, status: 'COMPLETED', startedAt: new Date().toISOString(), taskMode: 'READ', answer: '这是资料解读结果，链接未提交 IOC 研判。', nextSequence: 2, hasMore: false, events: [
      { sequence: 1, nodeExecutionId: 'one', displayName: '资料与文本解读', status: 'COMPLETED', timestamp: new Date().toISOString(), elapsedMs: 2300 },
      { sequence: 2, nodeExecutionId: 'two', displayName: '分析完成', status: 'COMPLETED', timestamp: new Date().toISOString() }
    ] };
    else if (url.pathname === '/api/reports/templates') data = [{ id: 'builtin-detailed-v1', key: 'detailed', name: '详细技术报告', version: 1, sections: ['scope', 'answer', 'evidence', 'limitations'] }];
    else if (url.pathname === '/api/reports') data = req.method() === 'POST' ? report : [];
    else if (url.pathname === '/api/reports/' + reportId) data = report;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(data) });
  });
  await page.goto('http://127.0.0.1:5173/workspace');
  await page.getByRole('button', { name: '新建对话', exact: true }).waitFor();
  assert.equal(await page.locator('.workspace-heading').count(), 0);
  assert.equal(await page.locator('.sidebar-group').count(), 3);
  assert.equal(await page.locator('.sidebar-group[open]').count(), 0);
  const groups = page.locator('.sidebar-group');
  for (let i = 0; i < 3; i++) {
    const summary = groups.nth(i).locator('summary');
    await summary.focus(); await page.keyboard.press('Enter');
    assert.equal(await groups.nth(i).evaluate(e => e.open), true);
    await summary.click(); assert.equal(await groups.nth(i).evaluate(e => e.open), false);
  }
  await groups.nth(0).locator('summary').click();
  const queryButtons = groups.nth(0).locator('nav button');
  assert.equal(await queryButtons.count(), 5);
  for (let i = 0; i < 5; i++) {
    await queryButtons.nth(i).click(); await page.getByRole('dialog').waitFor();
    await page.getByRole('button', { name: '关闭', exact: true }).click();
  }
  await groups.nth(0).locator('summary').click();
  await groups.nth(1).locator('summary').click();
  assert.deepEqual(await groups.nth(1).locator('a').evaluateAll(items => items.map(a => a.getAttribute('href'))), ['/research', '/mitre-mapper', '/reports']);
  await groups.nth(1).locator('summary').click();
  await groups.nth(2).locator('summary').click();
  assert.equal(await groups.nth(2).locator('a[target="_blank"]').count(), 2);
  await groups.nth(2).locator('summary').click();
  for (let i = 0; i < 3; i++) await groups.nth(i).locator('summary').click();
  const accountBox = await page.locator('.account-zone').boundingBox();
  assert.ok(accountBox.y + accountBox.height <= 1000, 'account clipped with expanded groups');
  assert.ok(await page.locator('.sidebar-scroll').evaluate(e => e.scrollHeight > e.clientHeight));
  assert.equal(await page.locator('.conversation-list').evaluate(e => getComputedStyle(e).overflowY), 'visible');
  await page.locator('.sidebar-scroll').evaluate(e => { e.scrollTop = e.scrollHeight; });
  assert.ok(await page.getByText('历史研判记录 35', { exact: true }).isVisible());
  await page.locator('.sidebar-scroll').evaluate(e => { e.scrollTop = 0; });
  for (let i = 0; i < 3; i++) await groups.nth(i).locator('summary').click();
  const backdrop = await page.locator('.campus-ambient').boundingBox();
  await page.screenshot({ path: path.resolve('ui-workspace-welcome.png'), fullPage: true });
  await page.locator('.task-mode select').selectOption('READ');
  await page.locator('textarea').fill('请解读论文摘要 https://doi.org/10.1/test');
  await page.getByRole('button', { name: '发送', exact: true }).click();
  await page.locator('.run-progress').waitFor();
  await page.getByRole('button', { name: /查看过程/ }).click();
  await page.locator('.run-progress ol').waitFor();
  assert.match(await page.locator('.run-progress ol').innerText(), /资料与文本解读/);
  assert.equal(submitted.taskMode, 'READ'); assert.match(submitted.message, /https:\/\/doi/);
  assert.deepEqual(await page.locator('.campus-ambient').boundingBox(), backdrop, 'background changes when a conversation starts');
  assert.equal(await page.locator('.campus-ambient img').isVisible(), true);
  await page.screenshot({ path: path.resolve('ui-run-trace.png'), fullPage: true });
  await page.getByRole('link', { name: '生成报告', exact: true }).click();
  await page.getByRole('button', { name: '使用现有证据生成草稿' }).click();
  await page.locator('.report-document').waitFor();
  await page.getByRole('link', { name: '证据快照 JSON' }).waitFor();
  await page.screenshot({ path: path.resolve('ui-report-center.png'), fullPage: true });
  await page.goto('http://127.0.0.1:5173/workspace/chat/history-0');
  await page.getByText('验收章节 30', { exact: true }).waitFor();
  const fixedBackdrop = await page.locator('.campus-ambient').boundingBox();
  const fixedComposer = await page.locator('.composer-zone').boundingBox();
  await page.locator('.message-area').evaluate(e => { e.style.scrollBehavior = 'auto'; e.scrollTop = 800; });
  assert.ok(await page.locator('.message-area').evaluate(e => e.scrollTop > 0));
  assert.deepEqual(await page.locator('.campus-ambient').boundingBox(), fixedBackdrop);
  assert.deepEqual(await page.locator('.composer-zone').boundingBox(), fixedComposer);
  await page.screenshot({ path: path.resolve('ui-workspace-long.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2), 'mobile report layout overflows');
  assert.deepEqual(errors, []);
  await page.goto('http://127.0.0.1:5173/workspace');
  await page.getByRole('button', { name: '展开侧栏', exact: true }).click();
  await page.locator('.workspace-sidebar .sidebar-toggle').click();
  await page.getByRole('button', { name: '展开侧栏', exact: true }).waitFor();
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2), 'mobile workspace overflows');
  await browser.close(); console.log('PASS: sidebar groups/keyboard/5 quick dialogs + shared backdrop + task mode + trace/report + mobile sidebar; mocked API only.');
})().catch(async e => { console.error(e); await activeBrowser?.close(); process.exitCode = 1; });
