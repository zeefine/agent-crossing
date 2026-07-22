import type { RealtimeEventType } from "./realtime";
import { getCurrentUserId } from "./user";

export type ApiResponse<T> = {
  success: boolean;
  data: T;
  error: { code: string; message: string } | null;
  timestamp: string;
};

export type ChatThread = {
  threadId: string;
  userId: string;
  title: string;
  status: "open" | "running" | "completed" | "failed" | "canceled";
  traceId: string;
  createdAt: string;
  updatedAt: string;
};

/**
 * MODEL_SYNC(ChatMessage) — 改字段时四处同步（无 codegen）：
 *   · services/platform-api/src/main/java/com/agentcrossing/platform/domain/message/ChatMessage.java
 *   · services/platform-api/src/main/java/com/agentcrossing/platform/api/dto/ChatMessageResponse.java
 *   · services/platform-web/lib/api.ts (export type ChatMessage)  ← 本文件
 *   · services/platform-api/src/main/resources/schema-mysql.sql (chat_message)
 *     + services/platform-api/src/main/resources/mapper/ChatMessageMapper.xml
 */
export type ChatMessage = {
  messageId: string;
  threadId: string;
  role: "user" | "assistant" | "system";
  content: string;
  status: "created" | "streaming" | "completed" | "failed" | "canceled";
  invocationId: string | null;
  taskId: string | null;
  agentId: string | null;
  createdAt: string;
  updatedAt: string;
};

export type InvocationMessage = {
  messageId: string;
  userId: string;
  invocationId: string;
  taskId: string;
  traceId: string;
  agentId: string;
  type: "textDelta" | "message" | "done" | "error";
  content: string | null;
  raw: unknown;
  sequence: number | null;
  createdAt: string;
};

/**
 * MODEL_SYNC(Task wire) — 改 wire 字段时三处同步（无 codegen）：
 *   · contracts/schemas/task.schema.json
 *   · services/platform-api/src/main/java/com/agentcrossing/platform/api/dto/TaskResponse.java
 *   · services/platform-web/lib/api.ts (export type Task)  ← 本文件
 *
 * 注意：domain Task.java 不含 dependsOn；dependsOn 由 TaskDependencyRepository/task_dependency 组装。
 */
export type Task = {
  taskId: string;
  userId: string;
  traceId: string;
  createdByTaskId: string | null;
  dependsOn: string[];
  status: string;
  source: string;
  depth: number;
  agentId: string;
  context: string;
  createdAt: string;
  updatedAt: string;
};

export type AgentStatus = {
  agentId: string;
  displayName: string;
  status: "idle" | "running";
  runningInvocations: number;
  processingTasks: number;
};

export type SubmitMessageResponse = {
  thread: ChatThread;
  message: ChatMessage;
  assistantMessage: ChatMessage | null;
  tasks: Task[];
};

export type ChatEvent<T = unknown> = {
  eventId: string;
  threadId: string;
  type: RealtimeEventType;
  payload: T;
  createdAt: string;
};

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      "X-User-Id": getCurrentUserId(),
      ...(init?.headers ?? {})
    }
  });
  const body = (await response.json()) as ApiResponse<T>;
  if (!response.ok || !body.success) {
    throw new Error(body.error?.message || `Request failed: ${response.status}`);
  }
  return body.data;
}

export function listThreads() {
  return request<ChatThread[]>("/api/chat/threads");
}

export function createThread(title?: string) {
  return request<ChatThread>("/api/chat/threads", {
    method: "POST",
    body: JSON.stringify({ title })
  });
}

export function deleteThread(threadId: string) {
  return request<null>(`/api/chat/threads/${threadId}`, { method: "DELETE" });
}

export function listMessages(threadId: string) {
  return request<ChatMessage[]>(`/api/chat/threads/${threadId}/messages`);
}

export function listInvocationMessages(threadId: string) {
  return request<InvocationMessage[]>(`/api/chat/threads/${threadId}/invocation-messages`);
}

export function sendMessage(threadId: string, content: string) {
  return request<SubmitMessageResponse>(`/api/chat/threads/${threadId}/messages`, {
    method: "POST",
    body: JSON.stringify({ content })
  });
}

export function cancelThreadWork(threadId: string) {
  return request<{ threadId: string; canceledTaskIds: string[]; canceledInvocationIds: string[] }>(
    `/api/chat/threads/${threadId}/cancel`,
    { method: "POST" }
  );
}

export function listAgents() {
  return request<AgentStatus[]>("/api/agents");
}

export function listTasks(traceId: string) {
  return request<Task[]>(`/api/tasks?traceId=${encodeURIComponent(traceId)}`);
}
