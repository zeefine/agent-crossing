import type { ChatMessage } from "./api";

const statusOrder: Record<ChatMessage["status"], number> = {
  created: 0, streaming: 1, completed: 2, failed: 2, canceled: 2
};

// Java Instant preserves sub-millisecond precision on WS; Date.parse alone does not.
function compareTimestamps(left: string, right: string) {
  const milliseconds = Date.parse(left) - Date.parse(right);
  if (milliseconds !== 0) return milliseconds;
  const fraction = (value: string) => Number((value.match(/\.(\d+)/)?.[1] ?? "").padEnd(9, "0"));
  return fraction(left) - fraction(right);
}

function chooseMessage(current: ChatMessage, incoming: ChatMessage, source: "snapshot" | "live") {
  const progression = statusOrder[incoming.status] - statusOrder[current.status];
  // A finalized message must never return to STREAMING, even with equal timestamps
  // or DB timestamp rounding. Final reconciliation may also shorten its content.
  if (progression !== 0) return progression > 0 ? incoming : current;
  const version = compareTimestamps(incoming.updatedAt || incoming.createdAt, current.updatedAt || current.createdAt);
  if (version !== 0) return version > 0 ? incoming : current;
  if (source === "snapshot" || statusOrder[current.status] === 2) return current;
  // Equal-version cumulative stream replay must not remove an already shown suffix.
  return current.content.startsWith(incoming.content) ? current : incoming;
}

/** Merge REST snapshots, submission responses and WS messages through the same rule. */
export function mergeChatMessages(
  current: ChatMessage[],
  incoming: ChatMessage[],
  source: "snapshot" | "live" = "live"
): ChatMessage[] {
  const byId = new Map(current.map((message) => [message.messageId, message]));
  let changed = false;
  let added = false;
  for (const message of incoming) {
    const previous = byId.get(message.messageId);
    const next = previous ? chooseMessage(previous, message, source) : message;
    if (next !== previous) {
      byId.set(message.messageId, next);
      changed = true;
      added ||= !previous;
    }
  }
  if (!changed) return current;
  const result = [...byId.values()];
  // Late history adds older rows; it must not move new realtime replies ahead of them.
  return added ? result.sort((left, right) => compareTimestamps(left.createdAt, right.createdAt)
    || (left.messageId < right.messageId ? -1 : left.messageId > right.messageId ? 1 : 0)) : result;
}
