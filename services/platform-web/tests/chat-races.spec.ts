import { expect, test, type Page, type Route, type WebSocketRoute } from "@playwright/test";
import type { ChatMessage, ChatThread } from "../lib/api";

const timestamp = "2026-10-04T10:00:00Z";
const thread: ChatThread = {
  threadId: "race-a", userId: "race-user", title: "竞态会话 A", status: "open",
  traceId: "trace-a", createdAt: timestamp, updatedAt: timestamp
};
const otherThread: ChatThread = { ...thread, threadId: "race-b", title: "竞态会话 B", traceId: "trace-b" };

function message(messageId: string, content: string, overrides: Partial<ChatMessage> = {}): ChatMessage {
  return {
    messageId, content, threadId: thread.threadId, role: "assistant", status: "completed",
    invocationId: null, taskId: null, agentId: "codex", createdAt: timestamp, updatedAt: timestamp,
    ...overrides
  };
}

async function setup(page: Page) {
  const sockets = new Map<string, WebSocketRoute>();
  await page.routeWebSocket(/\/ws\/chat/, (socket) => {
    sockets.set(new URL(socket.url()).searchParams.get("threadId")!, socket);
  });
  await page.route("**/api/**", (route) => {
    const path = new URL(route.request().url()).pathname;
    return route.fulfill({ json: { success: true, data: path === "/api/chat/threads" ? [thread, otherThread] : [] } });
  });
  return async (threadId: string, type: string, payload: unknown) => {
    await expect.poll(() => sockets.has(threadId)).toBe(true);
    sockets.get(threadId)!.send(JSON.stringify({ eventId: "event-race-test", threadId, type, payload, createdAt: timestamp }));
  };
}

async function submitResult(route: Route, content: string) {
  await route.fulfill({ json: { success: true, data: {
    thread: { ...thread, status: "running" }, message: message(content, content, { role: "user" }),
    assistantMessage: null, tasks: []
  } } });
}

test("sending again after Stop applies the new successful response", async ({ page }) => {
  await setup(page);
  await page.route("**/api/chat/threads/*/messages", (route) => route.request().method() === "GET"
    ? route.fulfill({ json: { success: true, data: [] } })
    : submitResult(route, route.request().postDataJSON().content));
  await page.route("**/api/chat/threads/*/cancel", (route) => route.fulfill({ json: { success: true, data: {
    threadId: thread.threadId, canceledTaskIds: [], canceledInvocationIds: []
  } } }));
  await page.goto("/");
  const input = page.getByRole("textbox", { name: "消息内容" });
  await input.fill("第一轮任务");
  await input.press("Enter");
  await page.getByRole("button", { name: "停止当前任务" }).click();
  await expect(page.getByRole("button", { name: "发送消息" })).toBeVisible();
  await input.fill("第二轮任务");
  await input.press("Enter");
  await expect(page.locator(".message-content")).toHaveText(["第一轮任务", "第二轮任务"]);
  await expect(page.getByRole("button", { name: "停止当前任务" })).toBeVisible();
});

for (const cancelSucceeds of [true, false]) {
  test(`a pending send waits for cancellation and ${cancelSucceeds ? "rejects" : "accepts"} its late result`, async ({ page }) => {
    const emit = await setup(page);
    let pendingSend: Route | undefined;
    let pendingCancel: Route | undefined;
    await page.route("**/api/chat/threads/*/messages", async (route) => {
      if (route.request().method() === "GET") await route.fulfill({ json: { success: true, data: [] } });
      else pendingSend = route;
    });
    await page.route("**/api/chat/threads/*/cancel", (route) => { pendingCancel = route; });
    await page.goto("/");
    const input = page.getByRole("textbox", { name: "消息内容" });
    await input.fill("取消前的任务");
    await input.press("Enter");
    await expect.poll(() => Boolean(pendingSend)).toBe(true);
    await emit(thread.threadId, "thread", { ...thread, status: "running" });
    await page.getByRole("button", { name: "停止当前任务" }).click();
    await expect.poll(() => Boolean(pendingCancel)).toBe(true);
    await submitResult(pendingSend!, "取消前的迟到响应");
    if (cancelSucceeds) {
      await pendingCancel!.fulfill({ json: { success: true, data: {
        threadId: thread.threadId, canceledTaskIds: [], canceledInvocationIds: []
      } } });
      await input.fill("取消后的新任务");
      await expect(page.getByRole("button", { name: "发送消息" })).toBeEnabled();
      await expect(page.getByText("取消前的迟到响应", { exact: true })).toHaveCount(0);
      // A fresh request must not inherit the invalidation of the previous request.
      pendingSend = undefined;
      await input.press("Enter");
      await expect.poll(() => Boolean(pendingSend)).toBe(true);
      await submitResult(pendingSend!, "取消后的新任务");
      await expect(page.getByText("取消后的新任务", { exact: true })).toBeVisible();
    } else {
      await pendingCancel!.fulfill({ status: 500, json: { success: false, error: { message: "取消失败" } } });
      await expect(page.getByText("取消前的迟到响应", { exact: true })).toBeVisible();
    }
  });
}

test("late history preserves realtime final replies, new messages and chronological order", async ({ page }) => {
  const emit = await setup(page);
  let history: Route | undefined;
  await page.route("**/api/chat/threads/race-b/messages", (route) => { history = route; });
  await page.goto("/");
  await page.getByRole("button", { name: /竞态会话 B/, exact: false }).first().click();
  await expect.poll(() => Boolean(history)).toBe(true);
  const final = message("stream", "实时完整回复", { threadId: otherThread.threadId, updatedAt: "2026-10-04T10:00:02Z" });
  const newMessage = message("new", "历史快照之后的新消息", { threadId: otherThread.threadId, createdAt: "2026-10-04T10:00:03Z" });
  await emit(otherThread.threadId, "chatMessage", final);
  await emit(otherThread.threadId, "chatMessage", newMessage);
  await expect(page.getByText(final.content, { exact: true })).toBeVisible();
  await history!.fulfill({ json: { success: true, data: [
    message("old", "更早的历史消息", { threadId: otherThread.threadId, createdAt: "2026-10-04T09:59:00Z" }),
    { ...final, status: "streaming", content: "旧的 partial", updatedAt: timestamp }
  ] } });
  await expect(page.locator(".message-content")).toHaveText(["更早的历史消息", final.content, newMessage.content]);
  // Old socket replay must not undo the merged final state either.
  await emit(otherThread.threadId, "chatMessage", { ...final, status: "streaming", content: "重放 partial" });
  await emit(otherThread.threadId, "chatMessage", message("barrier", "重放已处理", { threadId: otherThread.threadId, createdAt: "2026-10-04T10:00:04Z" }));
  await expect(page.getByText("重放已处理", { exact: true })).toBeVisible();
  await expect(page.getByText(final.content, { exact: true })).toBeVisible();
});

test("returning to a thread rejects its previous in-flight history request", async ({ page }) => {
  const emit = await setup(page);
  const histories: Route[] = [];
  await page.route("**/api/chat/threads/race-b/messages", (route) => { histories.push(route); });
  await page.goto("/");
  const select = (title: string) => page.locator(".thread-item").filter({ hasText: title }).click();
  await select(otherThread.title);
  await expect.poll(() => histories.length).toBe(1);
  await select(thread.title);
  await expect(page.getByRole("heading", { name: thread.title, exact: true })).toBeVisible();
  await select(otherThread.title);
  await expect.poll(() => histories.length).toBe(2);
  const fresh = message("fresh", "重新进入后的历史", { threadId: otherThread.threadId });
  await histories[1].fulfill({ json: { success: true, data: [fresh] } });
  await expect(page.getByText(fresh.content, { exact: true })).toBeVisible();
  await histories[0].fulfill({ json: { success: true, data: [message("obsolete", "不再有效的旧请求", { threadId: otherThread.threadId })] } });
  // Wait for a subsequent update rather than making an immediate negative assertion.
  await emit(otherThread.threadId, "chatMessage", message("barrier", "切换完成", { threadId: otherThread.threadId, createdAt: "2026-10-04T10:00:03Z" }));
  await expect(page.getByText("切换完成", { exact: true })).toBeVisible();
  await expect(page.locator(".message-content")).toHaveText([fresh.content, "切换完成"]);
});

test("history failure preserves live messages and leaves realtime updates usable", async ({ page }) => {
  const emit = await setup(page);
  let history: Route | undefined;
  await page.route("**/api/chat/threads/race-b/messages", (route) => { history = route; });
  await page.goto("/");
  await page.locator(".thread-item").filter({ hasText: otherThread.title }).click();
  await expect.poll(() => Boolean(history)).toBe(true);
  const live = message("live", "历史失败也要保留的实时回复", { threadId: otherThread.threadId });
  await emit(otherThread.threadId, "chatMessage", live);
  await expect(page.getByText(live.content, { exact: true })).toBeVisible();
  await history!.fulfill({ status: 500, json: { success: false, error: { message: "历史加载失败" } } });
  await emit(otherThread.threadId, "chatMessage", message("barrier", "仍可接收消息", { threadId: otherThread.threadId, createdAt: "2026-10-04T10:00:04Z" }));
  await expect(page.locator(".message-content")).toHaveText([live.content, "仍可接收消息"]);
});
