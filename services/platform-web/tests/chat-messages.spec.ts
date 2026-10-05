import { expect, test } from "@playwright/test";
import type { ChatMessage } from "../lib/api";
import { mergeChatMessages } from "../lib/chat-messages";

const streaming: ChatMessage = {
  messageId: "message-1", threadId: "thread-1", role: "assistant", content: "partial",
  status: "streaming", invocationId: "invocation-1", taskId: "task-1", agentId: "codex",
  createdAt: "2026-10-04T10:00:00Z", updatedAt: "2026-10-04T10:00:01.123456Z"
};

for (const status of ["completed", "failed", "canceled"] as const) {
  test(`${status} never regresses to streaming, even with a later stale timestamp`, () => {
    const final = { ...streaming, status, content: "final" };
    expect(mergeChatMessages([final], [{ ...streaming, updatedAt: "2026-10-04T10:00:02Z" }])).toEqual([final]);
  });
}

test("final reconciliation can shorten streaming text despite DB timestamp rounding", () => {
  const final = { ...streaming, status: "completed" as const, content: "ok", updatedAt: "2026-10-04T10:00:01.123Z" };
  expect(mergeChatMessages([streaming], [final], "snapshot")).toEqual([final]);
});

test("sub-millisecond message versions reject older stream replay", () => {
  const latest = { ...streaming, content: "最新内容", updatedAt: "2026-10-04T10:00:01.123999Z" };
  expect(mergeChatMessages([latest], [streaming])).toEqual([latest]);
  expect(mergeChatMessages([streaming], [latest], "snapshot")).toEqual([latest]);
});

test("equal-version history cannot overwrite the currently displayed content", () => {
  const current = { ...streaming, content: "partial plus realtime suffix" };
  expect(mergeChatMessages([current], [streaming], "snapshot")).toEqual([current]);
});

test("equal-version cumulative live chunks grow but cannot truncate a message", () => {
  const longer = { ...streaming, content: "partial plus suffix" };
  expect(mergeChatMessages([streaming], [longer])).toEqual([longer]);
  expect(mergeChatMessages([longer], [streaming])).toEqual([longer]);
});

test("a genuinely newer same-status snapshot replaces older content", () => {
  const final = { ...streaming, status: "completed" as const };
  const newer = { ...final, content: "corrected final", updatedAt: "2026-10-04T10:00:02Z" };
  expect(mergeChatMessages([final], [newer], "snapshot")).toEqual([newer]);
});

test("merging deduplicates and orders history plus realtime-only messages without mutating inputs", () => {
  const old = { ...streaming, messageId: "message-0", createdAt: "2026-10-04T09:59:00Z" };
  const recent = { ...streaming, messageId: "message-2", createdAt: "2026-10-04T10:00:01Z" };
  const current = [streaming, recent];
  const history = [old, streaming];
  expect(mergeChatMessages(current, history, "snapshot")).toEqual([old, streaming, recent]);
  expect(current).toEqual([streaming, recent]);
  expect(history).toEqual([old, streaming]);
});

test("duplicate events preserve the array reference", () => {
  const current = [streaming];
  expect(mergeChatMessages(current, [{ ...streaming }])).toBe(current);
});
