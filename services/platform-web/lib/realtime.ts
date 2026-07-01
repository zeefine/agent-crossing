import type { ChatEvent } from "./api";

export const REALTIME_EVENT_TYPES = {
  connection: "connection",
  thread: "thread",
  threadDeleted: "threadDeleted",
  chatMessage: "chatMessage",
  invocationMessage: "invocationMessage",
  task: "task"
} as const;

export type RealtimeEventType = (typeof REALTIME_EVENT_TYPES)[keyof typeof REALTIME_EVENT_TYPES];

const REALTIME_EVENT_TYPE_SET = new Set<string>(Object.values(REALTIME_EVENT_TYPES));

export function isChatEventType(value: string): value is RealtimeEventType {
  return REALTIME_EVENT_TYPE_SET.has(value);
}

export function isChatEvent(value: unknown): value is ChatEvent {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return (
    typeof record.eventId === "string" &&
    typeof record.threadId === "string" &&
    typeof record.type === "string" &&
    isChatEventType(record.type) &&
    typeof record.createdAt === "string" &&
    "payload" in record
  );
}
