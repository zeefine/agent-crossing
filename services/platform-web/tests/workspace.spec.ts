import { expect, test, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

const thread = {
  threadId: "thread-ui-test", userId: "ui-test", title: "前端组件统一",
  status: "open", traceId: "trace-ui-test",
  createdAt: "2026-10-04T10:00:00Z", updatedAt: "2026-10-04T10:00:00Z"
};

async function mockPlatform(page: Page, deleteFails = false) {
  let deleted = false;
  const mutations: string[] = [];
  await page.routeWebSocket(/\/ws\/chat/, () => {});
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    const method = route.request().method();
    if (method !== "GET") mutations.push(method + " " + url.pathname);
    if (method === "DELETE") {
      if (deleteFails) {
        await route.fulfill({ status: 500, json: { success: false, error: { message: "请稍后重试" } } });
        return;
      }
      deleted = true;
    }
    const data = url.pathname === "/api/chat/threads" ? (deleted ? [] : [thread])
      : url.pathname === "/api/agents" ? [{ agentId: "codex", displayName: "Codex", status: "idle", runningInvocations: 0, processingTasks: 0 }]
      : [];
    await route.fulfill({ json: { success: true, data, error: null } });
  });
  return mutations;
}

async function populateConversation(page: Page) {
  const task = {
    taskId: "task-ui-test", userId: "ui-test", traceId: thread.traceId,
    createdByTaskId: null, dependsOn: [], status: "completed", source: "user", depth: 0,
    agentId: "codex", context: "梳理前端组件和主题规范", createdAt: thread.createdAt, updatedAt: thread.updatedAt
  };
  const messages = [
    { messageId: "m1", role: "user", content: "请整理当前前端组件，统一按钮、输入框和弹窗的设计风格。", agentId: null, taskId: null },
    { messageId: "m2", role: "assistant", content: "建议保留现有三栏布局，统一基础组件的交互与视觉规范。\n\n按钮使用相同的尺寸和圆角；会话、消息与任务图共享主题色。删除操作应先确认，失败时在操作位置说明原因。\n\n浅色与深色模式都需要保留清晰的文字层级。", agentId: "codex", taskId: task.taskId }
  ].map((message) => ({ ...message, threadId: thread.threadId, status: "completed", invocationId: "inv-ui-test", createdAt: thread.createdAt, updatedAt: thread.updatedAt }));
  await page.route("**/api/tasks?**", (route) => route.fulfill({ json: { success: true, data: [task] } }));
  await page.route("**/api/chat/threads/*/messages", (route) => route.fulfill({ json: { success: true, data: messages } }));
}

test("composer is compact, grows with content and shrinks after editing", async ({ page }, testInfo) => {
  await mockPlatform(page);
  await page.setViewportSize({ width: 1440, height: 960 });
  await page.goto("/");
  const input = page.getByRole("textbox", { name: "消息内容" });
  await expect(input).toBeVisible();
  const height = () => input.evaluate((element) => element.getBoundingClientRect().height);
  expect(await height()).toBeLessThanOrEqual(32);
  await expect(input).toHaveCSS("resize", "none");
  await expect(page.getByRole("button", { name: "发送消息" })).toBeDisabled();
  await input.fill(Array.from({ length: 30 }, (_, index) => `第 ${index + 1} 行：梳理当前项目的实现和下一步计划。`).join("\n"));
  expect(await height()).toBeGreaterThan(120);
  expect(await height()).toBeLessThanOrEqual(240);
  expect(await input.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true);
  await input.fill("帮我梳理当前项目的实现");
  expect(await height()).toBeLessThanOrEqual(32);
  await page.locator(".composer").screenshot({ path: testInfo.outputPath("composer-desktop.png") });
  await input.fill("请帮我梳理当前项目的实现，并列出接下来需要完成的工作。");
  await page.setViewportSize({ width: 320, height: 720 });
  await expect.poll(height).toBeGreaterThan(32);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(320);
  await page.locator(".composer").screenshot({ path: testInfo.outputPath("composer-mobile.png") });
  await page.getByRole("button", { name: "切换主题" }).click();
  await page.getByRole("menuitem", { name: "深色" }).click();
  await page.locator(".composer").screenshot({ path: testInfo.outputPath("composer-dark.png") });
});

test("delete confirmation is keyboard-accessible and cancel does not delete", async ({ page }) => {
  const mutations = await mockPlatform(page);
  await page.goto("/");
  await page.getByRole("button", { name: "删除会话 前端组件统一" }).click();
  const dialog = page.getByRole("alertdialog");
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("button", { name: "取消", exact: true })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole("button", { name: "删除会话 前端组件统一" })).toBeFocused();
  expect(mutations).toHaveLength(0);
});

test("delete failure stays visible in the confirmation, then can be dismissed", async ({ page }) => {
  await mockPlatform(page, true);
  await page.goto("/");
  await page.getByRole("button", { name: "删除会话 前端组件统一" }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: "确认删除" }).click();
  await expect(page.getByRole("alertdialog").getByRole("alert")).toContainText("请稍后重试");
  await expect(page.getByRole("button", { name: "确认删除" })).toBeEnabled();
});

test("confirmed deletion removes the thread without creating a replacement", async ({ page }) => {
  const mutations = await mockPlatform(page);
  await page.goto("/");
  await page.getByRole("button", { name: "删除会话 前端组件统一" }).click();
  await page.getByRole("button", { name: "确认删除" }).click();
  await expect(page.getByRole("alertdialog")).not.toBeVisible();
  await expect(page.getByRole("button", { name: "删除会话 前端组件统一" })).toHaveCount(0);
  expect(mutations).toEqual(["DELETE /api/chat/threads/thread-ui-test"]);
});

test("mobile navigation, theme and composer remain usable", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await mockPlatform(page);
  await page.goto("/");
  await expect(page.getByRole("textbox", { name: "消息内容" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(375);
  await page.getByRole("button", { name: "打开会话列表" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "切换主题" }).click();
  await page.getByRole("menuitem", { name: "深色" }).click();
  await expect(page.locator("html")).toHaveClass(/dark/);
  await page.reload();
  await expect(page.locator("html")).toHaveClass(/dark/);
});

for (const scheme of ["light", "dark"] as const) {
  test(`populated workspace renders at all breakpoints in ${scheme}`, async ({ page }, testInfo) => {
    const errors: string[] = [];
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("console", (message) => { if (["error", "warning"].includes(message.type())) errors.push(message.text()); });
    await page.emulateMedia({ colorScheme: scheme, reducedMotion: "reduce" });
    await mockPlatform(page);
    await populateConversation(page);
    await page.setViewportSize({ width: 1440, height: 960 });
    await page.goto("/");
    await expect(page.getByRole("heading", { name: thread.title })).toBeVisible();
    await expect(page.getByRole("button", { name: "打开会话列表" })).not.toBeVisible();
    await expect(page.getByRole("button", { name: "打开任务视图" })).not.toBeVisible();
    await expect(page.locator(".react-flow__node")).toHaveCount(1);
    await page.locator(".react-flow__node").click();
    await expect(page.getByRole("region", { name: "Selected task details" })).toBeVisible();
    await expect.poll(async () => {
      const node = await page.locator(".react-flow__node").boundingBox();
      const graph = await page.locator(".dag-graph").boundingBox();
      return Boolean(node && graph && node.y >= graph.y && node.y + node.height <= graph.y + graph.height);
    }).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`workspace-${scheme}-1440.png`) });
    expect((await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze()).violations).toEqual([]);
    for (const width of [1024, 768, 375, 320]) {
      await page.setViewportSize({ width, height: 900 });
      await expect(page.getByRole("textbox", { name: "消息内容" })).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(width);
      await page.screenshot({ path: testInfo.outputPath(`workspace-${scheme}-${width}.png`) });
    }
    await page.getByRole("button", { name: "打开任务视图" }).click();
    await expect(page.getByRole("dialog").locator(".react-flow__node")).toHaveCount(1);
    await page.screenshot({ path: testInfo.outputPath(`workspace-${scheme}-drawer.png`) });
    expect(errors).toEqual([]);
  });
}

test("composer preserves Shift+Enter and sends once, then offers Stop", async ({ page }) => {
  await mockPlatform(page);
  let sends = 0;
  let stops = 0;
  await page.route("**/api/chat/threads/*/messages", async (route) => {
    if (route.request().method() === "GET") {
      await route.fulfill({ json: { success: true, data: [] } });
      return;
    }
    sends++;
    await route.fulfill({ json: { success: true, data: {
      thread: { ...thread, status: "running" }, tasks: [], assistantMessage: null,
      message: { messageId: "sent", threadId: thread.threadId, role: "user", content: route.request().postDataJSON().content, status: "completed", createdAt: thread.createdAt }
    } } });
  });
  await page.route("**/api/chat/threads/*/cancel", async (route) => {
    stops++;
    await route.fulfill({ json: { success: true, data: { threadId: thread.threadId, canceledTaskIds: [], canceledInvocationIds: [] } } });
  });
  await page.goto("/");
  const input = page.getByRole("textbox", { name: "消息内容" });
  await input.fill("检查组件");
  await input.dispatchEvent("keydown", { key: "Enter", isComposing: true });
  await expect(input).toHaveValue("检查组件");
  expect(sends).toBe(0);
  await input.press("Shift+Enter");
  await expect(input).toHaveValue("检查组件\n");
  expect(sends).toBe(0);
  await input.press("Enter");
  await expect(page.getByRole("button", { name: "停止当前任务" })).toBeVisible();
  await expect(input).toHaveValue("");
  expect(await input.evaluate((element) => element.getBoundingClientRect().height)).toBeLessThanOrEqual(32);
  expect(sends).toBe(1);
  await page.getByRole("button", { name: "停止当前任务" }).click();
  await expect(page.getByRole("button", { name: "发送消息" })).toBeVisible();
  expect(stops).toBe(1);
});

test("creation failure from mobile navigation is visible after closing the drawer", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await mockPlatform(page);
  await page.route("**/api/chat/threads", async (route) => {
    if (route.request().method() !== "POST") return route.fallback();
    await route.fulfill({ status: 500, json: { success: false, error: { message: "服务暂不可用" } } });
  });
  await page.goto("/");
  await page.getByRole("button", { name: "打开会话列表" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "新建任务" }).click();
  await expect(page.getByRole("dialog")).not.toBeVisible();
  await expect(page.getByRole("region", { name: "协作对话" }).getByRole("alert")).toContainText("创建失败：服务暂不可用");
});
