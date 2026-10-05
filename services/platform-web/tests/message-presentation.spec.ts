import { expect, test, type Page, type WebSocketRoute } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import type { ChatMessage } from "../lib/api";

const timestamp = "2026-10-05T10:00:00Z";
const thread = { threadId: "presentation", title: "消息展示", traceId: "trace", status: "open", createdAt: timestamp, updatedAt: timestamp };
function message(id: string, content: string, overrides: Partial<ChatMessage> = {}): ChatMessage {
  return { messageId: id, content, threadId: thread.threadId, role: "assistant", agentId: "claudecode", status: "completed",
    invocationId: "inv", taskId: null, createdAt: overrides.role === "user" ? "2026-10-05T09:59:00Z" : timestamp, updatedAt: timestamp, ...overrides };
}
async function setup(page: Page, messages: ChatMessage[]) {
  let socket: WebSocketRoute | undefined;
  await page.routeWebSocket(/\/ws\/chat/, (next) => { socket = next; });
  await page.route("**/api/**", (route) => {
    const path = new URL(route.request().url()).pathname;
    const data = path === "/api/chat/threads" ? [thread] : path.endsWith("/messages") ? messages : [];
    return route.fulfill({ json: { success: true, data } });
  });
  await page.goto("/");
  await expect(page.getByRole("textbox", { name: "消息内容" })).toBeVisible();
  return async (next: ChatMessage) => {
    await expect.poll(() => Boolean(socket)).toBe(true);
    socket!.send(JSON.stringify({ eventId: `event-${next.messageId}`, threadId: thread.threadId, type: "chatMessage", payload: next, createdAt: timestamp }));
  };
}

const markdown = "## 实现建议\n\n**优先保证可读性**，保留 `sessionId`。\n\n- 统一消息排版\n- 保留上下文\n\n> 流式输出不会打断阅读。\n\n```ts\nconst result = 1 < 2;\nconsole.log(result);\n```\n\n| 模块 | 实现策略 | 验收方式 | 风险说明 |\n| --- | --- | --- | --- |\n| 消息正文 | CommonMark与GFM表格支持 | 桌面端与移动端浏览器验证 | 不执行模型输出中的HTML |";

test("renders safe Markdown, right-aligned user bubbles and copyable code/messages", async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: {
      writeText: async (value: string) => { (window as Window & { copied?: string }).copied = value; }
    } });
  });
  await setup(page, [message("user", "请给出实现建议", { role: "user" }), message("answer", markdown)]);
  const answer = page.locator(".message.assistant");
  await expect(answer.getByRole("heading", { name: "实现建议" })).toBeVisible();
  await expect(answer.locator("strong")).toHaveText("优先保证可读性");
  await expect(answer.locator("li")).toHaveCount(2);
  await expect(answer.locator("blockquote")).toContainText("流式输出");
  await expect(answer.locator(".agent-id-tag")).toHaveCount(0);
  await answer.getByRole("button", { name: "复制代码", exact: true }).click();
  await expect.poll(() => page.evaluate(() => (window as Window & { copied?: string }).copied)).toBe("const result = 1 < 2;\nconsole.log(result);\n");
  await expect(answer.getByRole("button", { name: "代码已复制" })).toBeVisible();
  await answer.getByRole("button", { name: "复制消息", exact: true }).click();
  await expect.poll(() => page.evaluate(() => (window as Window & { copied?: string }).copied)).toBe(markdown);
  const user = page.locator(".message.user .message-body");
  const rail = page.locator(".message-rail");
  expect(await user.evaluate((node) => node.getBoundingClientRect().right)).toBeCloseTo(await rail.evaluate((node) => node.getBoundingClientRect().right), 0);
});

test("untrusted Markdown cannot execute HTML, dangerous links or automatic image requests", async ({ page }) => {
  const imageRequests: string[] = [];
  await page.route("https://tracker.invalid/**", (route) => { imageRequests.push(route.request().url()); return route.abort(); });
  await setup(page, [message("unsafe", '<script>alert(1)</script>\n\n<img src=x onerror="alert(1)">\n\n[危险](javascript:alert%281%29)\n\n![外部图片](https://tracker.invalid/pixel)\n\n[安全链接](https://example.com/docs)')]);
  const body = page.locator(".message.assistant");
  await expect(body.locator("script, iframe, img")).toHaveCount(0);
  await expect(body.locator('a[href^="javascript:"]')).toHaveCount(0);
  await expect(body.getByRole("link", { name: "安全链接" })).toHaveAttribute("rel", /noreferrer/);
  expect(imageRequests).toEqual([]);
});

test("copy rejection is visible and can be retried", async ({ page }) => {
  await page.addInitScript(() => Object.defineProperty(navigator, "clipboard", {
    value: { writeText: async () => { throw new Error("NotAllowedError"); } }
  }));
  await setup(page, [message("copy", "可手动选择的正文")]);
  await page.getByRole("button", { name: "复制消息", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("复制失败");
  await expect(page.getByRole("button", { name: "复制消息", exact: true })).toBeEnabled();
});

test("streaming Markdown survives partial fences and has explicit terminal states", async ({ page }) => {
  const emit = await setup(page, []);
  await emit(message("stream", "## 正在分析\n\n```ts\nconst a =", { status: "streaming" }));
  await expect(page.locator(".message.assistant").getByRole("status")).toContainText("生成中");
  await expect(page.locator("pre code")).toContainText("const a =");
  await emit(message("stream", "## 分析完成\n\n```ts\nconst a = 1;\n```", { updatedAt: "2026-10-05T10:00:01Z" }));
  await expect(page.getByRole("heading", { name: "分析完成" })).toBeVisible();
  await expect(page.locator(".message.assistant").getByText("生成中", { exact: true })).toHaveCount(0);
  await emit(message("stopped", "保留已生成内容", { status: "canceled" }));
  await emit(message("failed", "", { status: "failed" }));
  await expect(page.locator(".message.assistant").getByText("已停止", { exact: true })).toBeVisible();
  await expect(page.locator(".message.assistant").getByText("生成失败", { exact: true })).toBeVisible();
  await expect(page.getByText("保留已生成内容", { exact: true })).toBeVisible();
});

test("incoming chunks do not pull a reader down; jump to latest restores following", async ({ page }) => {
  const history = Array.from({ length: 24 }, (_, index) => message(`old-${index}`, `第 ${index} 条历史\n\n${"历史说明。".repeat(40)}`));
  const emit = await setup(page, history);
  const viewport = page.locator(".message-viewport");
  await viewport.hover();
  await page.mouse.wheel(0, -900);
  await expect(page.getByRole("button", { name: "回到最新消息" })).toBeVisible();
  const position = await viewport.evaluate((node) => node.scrollTop);
  await emit(message("new", "新回复\n\n" + "新增内容。".repeat(100), { status: "streaming", createdAt: "2026-10-05T10:00:01Z" }));
  await expect(page.locator(".message.assistant").last()).toContainText("新回复");
  expect(Math.abs(await viewport.evaluate((node) => node.scrollTop) - position)).toBeLessThan(3);
  await page.getByRole("button", { name: "回到最新消息" }).click();
  await expect.poll(() => viewport.evaluate((node) => node.scrollHeight - node.clientHeight - node.scrollTop)).toBeLessThan(3);
  await emit(message("new", "新回复\n\n" + "完成内容。".repeat(160), { createdAt: "2026-10-05T10:00:01Z", updatedAt: "2026-10-05T10:00:02Z" }));
  await expect(page.locator(".message-content").last()).toContainText("完成内容");
  await expect.poll(() => viewport.evaluate((node) => node.scrollHeight - node.clientHeight - node.scrollTop)).toBeLessThan(3);
});

for (const theme of ["light", "dark"] as const) {
  test(`Markdown tables and code stay within the viewport in ${theme}`, async ({ page }, testInfo) => {
    const errors: string[] = [];
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("console", (entry) => { if (["error", "warning"].includes(entry.type())) errors.push(entry.text()); });
    await page.emulateMedia({ colorScheme: theme, reducedMotion: "reduce" });
    await setup(page, [message("user", "请给出实现建议", { role: "user" }), message("answer", markdown)]);
    for (const width of [1440, 768, 320]) {
      await page.setViewportSize({ width, height: 1100 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(width);
      await expect(page.getByRole("region", { name: "表格（可横向滚动）" })).toHaveCSS("overflow-x", "auto");
      await page.screenshot({ path: testInfo.outputPath(`messages-${theme}-${width}.png`) });
    }
    const table = page.getByRole("region", { name: "表格（可横向滚动）" });
    expect(await table.evaluate((node) => node.scrollWidth > node.clientWidth)).toBe(true);
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
    expect(errors).toEqual([]);
  });
}
