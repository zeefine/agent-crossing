"use client";

import {
  Ban,
  Bot,
  CheckCircle2,
  ChevronsLeft,
  ChevronsRight,
  Circle,
  Clock3,
  CornerDownLeft,
  GitBranch,
  Loader2,
  Plus,
  Send,
  Sparkles,
  Trash2,
  UserRound,
  XCircle
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Background,
  Controls,
  Handle,
  MarkerType,
  Position,
  ReactFlow
} from "@xyflow/react";
import type { Edge, Node, NodeProps } from "@xyflow/react";
import dagre from "dagre";
import {
  AgentStatus,
  ChatEvent,
  ChatMessage,
  ChatThread,
  InvocationMessage,
  Task,
  createThread,
  deleteThread,
  listAgents,
  listInvocationMessages,
  listMessages,
  listTasks,
  listThreads,
  sendMessage
} from "../lib/api";
import { REALTIME_EVENT_TYPES } from "../lib/realtime";
import { useSocket } from "../lib/useSocket";

type LoadState = "booting" | "ready" | "error";

let pendingBootThread: Promise<ChatThread> | null = null;

export default function Home() {
  const [threads, setThreads] = useState<ChatThread[]>([]);
  const [activeThreadId, setActiveThreadId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [invocationMessages, setInvocationMessages] = useState<InvocationMessage[]>([]);
  const [agents, setAgents] = useState<AgentStatus[]>([]);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [input, setInput] = useState("");
  const [loadState, setLoadState] = useState<LoadState>("booting");
  const [isSending, setIsSending] = useState(false);
  const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(false);
  const [isDagCollapsed, setIsDagCollapsed] = useState(false);
  const viewportRef = useRef<HTMLDivElement | null>(null);
  const shouldStickMessagesToBottomRef = useRef(true);

  const activeThread = useMemo(
    () => threads.find((thread) => thread.threadId === activeThreadId) ?? null,
    [activeThreadId, threads]
  );
  const isAwaitingAgentOutput = useMemo(
    () =>
      isSending
      || activeThread?.status === "running"
      || tasks.some((task) => task.status === "queued" || task.status === "processing"),
    [activeThread?.status, isSending, tasks]
  );

  const threadOrderById = useMemo(() => {
    return new Map(
      [...threads]
        .sort((left, right) => {
          const leftTime = new Date(left.createdAt).getTime();
          const rightTime = new Date(right.createdAt).getTime();
          const leftSortTime = Number.isFinite(leftTime) ? leftTime : 0;
          const rightSortTime = Number.isFinite(rightTime) ? rightTime : 0;
          if (leftSortTime !== rightSortTime) {
            return leftSortTime - rightSortTime;
          }
          return left.threadId.localeCompare(right.threadId);
        })
        .map((thread, index) => [thread.threadId, index + 1])
    );
  }, [threads]);

  const activeTraceIdRef = useRef<string | null>(null);
  const activeThreadIdRef = useRef<string | null>(null);
  const inflightThreadIdRef = useRef<string | null>(null);

  const isThreadStillActive = useCallback((threadId: string) => activeThreadIdRef.current === threadId, []);

  const refetchTasks = useCallback(async () => {
    const traceId = activeTraceIdRef.current;
    if (!traceId) {
      return;
    }
    try {
      const next = await listTasks(traceId);
      if (activeTraceIdRef.current === traceId) {
        setTasks(next);
      }
    } catch {
      // swallow — next interval or event will retry
    }
  }, []);

  useEffect(() => {
    activeThreadIdRef.current = activeThreadId;
  }, [activeThreadId]);

  useEffect(() => {
    activeTraceIdRef.current = activeThread?.traceId ?? null;
    if (!activeThread?.traceId) {
      setTasks([]);
      return;
    }
    refetchTasks();
  }, [activeThread?.traceId, refetchTasks]);

  const refreshThreads = useCallback(async () => {
    const nextThreads = await listThreads();
    setThreads(nextThreads);
    return nextThreads;
  }, []);

  const refreshAgents = useCallback(async () => {
    const nextAgents = await listAgents();
    setAgents(nextAgents);
  }, []);

  const loadThreadData = useCallback(async (threadId: string) => {
    // 标记当前正在 in-flight 的 threadId；后到的请求会覆盖这个 ref。
    inflightThreadIdRef.current = threadId;
    // 两个接口独立 fetch + 独立写状态：单边 5xx 不会让另一边的数据连带消失。
    const loadMessages = listMessages(threadId).then((next) => {
      if (inflightThreadIdRef.current === threadId && isThreadStillActive(threadId)) {
        setMessages(next);
      }
    });
    const loadInvocationMessages = listInvocationMessages(threadId).then((next) => {
      if (inflightThreadIdRef.current === threadId && isThreadStillActive(threadId)) {
        setInvocationMessages(next);
      }
    });
    await Promise.allSettled([loadMessages, loadInvocationMessages]);
  }, [isThreadStillActive]);

  const handleRealtimeEvent = useCallback((event: ChatEvent) => {
    if (event.type === REALTIME_EVENT_TYPES.connection) {
      return;
    }
    if (event.type === REALTIME_EVENT_TYPES.thread) {
      setThreads((current) => upsertHeadBy(current, event.payload as ChatThread, "threadId"));
      return;
    }
    if (event.type === REALTIME_EVENT_TYPES.threadDeleted) {
      const deletedThreadId = (event.payload as { threadId: string }).threadId;
      setThreads((current) => current.filter((thread) => thread.threadId !== deletedThreadId));
      if (activeThreadIdRef.current === deletedThreadId) {
        // 当前活跃线程被删；下一个 useEffect 会接手切到第一个剩下的（或保持 null）。
        setActiveThreadId(null);
      }
      return;
    }
    if (event.type === REALTIME_EVENT_TYPES.chatMessage) {
      setMessages((current) => upsertTailBy(current, event.payload as ChatMessage, "messageId"));
      return;
    }
    if (event.type === REALTIME_EVENT_TYPES.task) {
      setTasks((current) => upsertTailBy(current, event.payload as Task, "taskId"));
      return;
    }
    if (event.type === REALTIME_EVENT_TYPES.invocationMessage) {
      setInvocationMessages((current) => upsertTailBy(current, event.payload as InvocationMessage, "messageId"));
    }
  }, []);

  // activeThreadId 因为删除被置 null 时，自动切到第一个剩下的 thread；都没了就停在 null。
  useEffect(() => {
    if (activeThreadId === null && threads.length > 0) {
      setActiveThreadId(threads[0].threadId);
    }
  }, [activeThreadId, threads]);

  const handleDeleteThread = useCallback(async (threadId: string, title: string) => {
    if (typeof window !== "undefined" && !window.confirm(`删除会话 "${title}"？此操作不可撤销。`)) {
      return;
    }
    try {
      await deleteThread(threadId);
      // 乐观更新：API 返回后立刻从本地移除，不等 WS 事件（同一浏览器收到自己的 WS 也是同样的清理，幂等）。
      setThreads((current) => current.filter((thread) => thread.threadId !== threadId));
      if (activeThreadIdRef.current === threadId) {
        setActiveThreadId(null);
      }
    } catch (error) {
      window.alert(`删除失败：${error instanceof Error ? error.message : "未知错误"}`);
    }
  }, []);

  useSocket({
    threadId: activeThreadId,
    onEvent: handleRealtimeEvent
  });

  useEffect(() => {
    let canceled = false;
    async function boot() {
      try {
        const existing = await withTimeout(refreshThreads(), 8000);
        const thread = existing[0] ?? (await withTimeout(ensureBootThread(), 8000));
        if (canceled) {
          return;
        }
        setActiveThreadId(thread.threadId);
        await withTimeout(loadThreadData(thread.threadId), 8000);
        await withTimeout(refreshAgents(), 8000);
        setLoadState("ready");
      } catch (error) {
        setLoadState("error");
      }
    }
    boot();
    return () => {
      canceled = true;
    };
  }, [loadThreadData, refreshAgents, refreshThreads]);

  useEffect(() => {
    const interval = window.setInterval(() => {
      refreshAgents().catch(() => undefined);
      refreshThreads().catch(() => undefined);
      refetchTasks().catch(() => undefined);
    }, 10000);
    return () => window.clearInterval(interval);
  }, [refreshAgents, refreshThreads, refetchTasks]);

  useEffect(() => {
    // 切线程时立即清空旧线程的数据，避免在新数据回来之前页面继续展示上一个线程内容。
    setMessages([]);
    setInvocationMessages([]);
    setTasks([]);
    if (!activeThreadId) {
      return;
    }
    shouldStickMessagesToBottomRef.current = true;
    loadThreadData(activeThreadId).catch(() => undefined);
  }, [activeThreadId, loadThreadData]);

  useEffect(() => {
    if (shouldStickMessagesToBottomRef.current) {
      scrollToBottom(viewportRef.current);
    }
  }, [messages]);

  async function handleNewThread() {
    const thread = await createThread();
    setThreads((current) => [thread, ...current]);
    setActiveThreadId(thread.threadId);
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const content = input.trim();
    if (!content || !activeThreadId || isSending) {
      return;
    }
    const threadId = activeThreadId;
    setInput("");
    setIsSending(true);
    shouldStickMessagesToBottomRef.current = true;
    try {
      const result = await sendMessage(threadId, content);
      if (!isThreadStillActive(threadId)) {
        return;
      }
      setThreads((current) => upsertHeadBy(current, result.thread, "threadId"));
      setMessages((current) => upsertTailBy(current, result.message, "messageId"));
      if (result.assistantMessage) {
        setMessages((current) => upsertTailBy(current, result.assistantMessage as ChatMessage, "messageId"));
      }
      setTasks((current) => upsertManyTailBy(current, result.tasks, "taskId"));
      await refreshAgents();
    } finally {
      setIsSending(false);
    }
  }

  if (loadState === "booting") {
    return <BootScreen label="Connecting platform" />;
  }

  if (loadState === "error") {
    return <BootScreen label="Platform API unavailable" tone="danger" />;
  }

  return (
    <main className={`shell ${isSidebarCollapsed ? "sidebar-collapsed" : ""} ${isDagCollapsed ? "dag-collapsed" : ""}`}>
      <section className={`sidebar ${isSidebarCollapsed ? "collapsed" : ""}`}>
        {isSidebarCollapsed ? (
          <div className="sidebar-collapsed-bar" aria-label="Collapsed threads">
            <button className="icon-button strong" onClick={() => setIsSidebarCollapsed(false)} aria-label="展开历史会话">
              <ChevronsRight size={18} />
            </button>
            <button className="icon-button" onClick={handleNewThread} aria-label="New thread">
              <Plus size={18} />
            </button>
            <div className="collapsed-agent-count">
              <Bot size={16} />
              <span>{threads.length}</span>
            </div>
          </div>
        ) : (
          <>
            <header className="brand-row">
              <div>
                <p className="eyebrow">Agent Crossing</p>
              </div>
              <div className="header-actions">
                <button className="icon-button" onClick={() => setIsSidebarCollapsed(true)} aria-label="收起历史会话">
                  <ChevronsLeft size={18} />
                </button>
              </div>
            </header>

            <button className="sidebar-new-thread" onClick={handleNewThread} aria-label="New thread">
              <Plus size={16} />
              <span>新建任务</span>
            </button>

            <div className="agent-strip">
              <div className="agent-strip-title">当前在线</div>
              {agents.map((agent) => (
                <button key={agent.agentId} className={`agent-chip ${agent.status}`}>
                  <span className="agent-presence-mark">
                    <span className="pulse-dot" />
                  </span>
                  <span className="agent-chip-copy">
                    <span>{agentName(agents, agent.agentId)}</span>
                    <small>{agent.status === "running" ? "运行中" : "空闲"}</small>
                  </span>
                </button>
              ))}
            </div>

            <div className="thread-list">
              {threads.map((thread) => (
                <div key={thread.threadId} className="thread-item-wrapper">
                  <button
                    className={`thread-item ${thread.threadId === activeThreadId ? "active" : ""}`}
                    onClick={() => setActiveThreadId(thread.threadId)}
                  >
                    <span className="thread-copy">
                      <small>
                        <span className="thread-dash">-</span>
                        TASK-{threadOrderById.get(thread.threadId) ?? 1}
                      </small>
                      <strong>{thread.title}</strong>
                    </span>
                  </button>
                  <button
                    type="button"
                    className="thread-delete"
                    onClick={() => handleDeleteThread(thread.threadId, thread.title)}
                    aria-label={`删除会话 ${thread.title}`}
                    title="删除会话"
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              ))}
            </div>
          </>
        )}
      </section>

      <section className="workspace">
        <div className="conversation">
          <div
            className="message-viewport"
            ref={viewportRef}
            onScroll={(event) => {
              shouldStickMessagesToBottomRef.current = isNearBottom(event.currentTarget);
            }}
          >
            <div className="message-rail">
              {messages.length === 0 ? (
                <EmptyConversation />
              ) : (
                messages.map((message) => (
                  <MessageBubble key={message.messageId} message={message} agents={agents} />
                ))
              )}
              {isAwaitingAgentOutput ? <ProcessingIndicator /> : null}
            </div>
          </div>

          <form className="composer" onSubmit={handleSubmit}>
            <div className="composer-rail">
              <textarea
                value={input}
                onChange={(event) => setInput(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
                    event.preventDefault();
                    event.currentTarget.form?.requestSubmit();
                  }
                }}
                placeholder="@opencode 规划并执行下一步"
                rows={1}
              />
              <button className="send-button" type="submit" disabled={!input.trim() || isSending}>
                {isSending ? <Loader2 className="spin" size={18} /> : <Send size={18} />}
              </button>
            </div>
          </form>
        </div>
      </section>

      {isDagCollapsed ? (
        <aside className="dag-collapsed-panel" aria-label="Collapsed DAG">
          <button className="dag-collapsed-button" onClick={() => setIsDagCollapsed(false)} aria-label="展开 DAG">
            <ChevronsLeft size={18} />
            <GitBranch size={18} />
            <span>DAG</span>
          </button>
        </aside>
      ) : (
        <DagPanel
          tasks={tasks}
          agents={agents}
          messages={messages}
          traceId={activeThread?.traceId ?? null}
          invocationMessages={invocationMessages}
          onCollapse={() => setIsDagCollapsed(true)}
        />
      )}
    </main>
  );
}

function BootScreen({ label, tone = "normal" }: { label: string; tone?: "normal" | "danger" }) {
  return (
    <main className="boot-screen">
      <div className={`boot-card ${tone}`}>
        <Sparkles size={24} />
        <span>{label}</span>
      </div>
    </main>
  );
}

function MessageBubble({ message, agents }: { message: ChatMessage; agents: AgentStatus[] }) {
  const isUser = message.role === "user";
  const meta = messageAgentMeta(message, agents);
  return (
    <article className={`message ${isUser ? "user" : "assistant"} ${meta.className}`}>
      <div className="message-icon">{isUser ? <UserRound size={16} /> : meta.icon}</div>
      <div className="message-body">
        <div className="message-meta">
          <span>{isUser ? "You" : meta.label}</span>
          {!isUser && message.agentId ? <span className="agent-id-tag">{message.agentId}</span> : null}
          <time>{formatTime(message.createdAt)}</time>
        </div>
        <p>{message.content}</p>
      </div>
    </article>
  );
}

function EmptyConversation() {
  return (
    <div className="empty-conversation">
      <div className="empty-orbit">
        <Bot size={30} />
      </div>
      <h3>开始一次 Agent 协作</h3>
      <p>输入任务后，系统会自动规划执行步骤，并在右侧同步展示 DAG 状态。</p>
      <div className="empty-hints">
        <span><CornerDownLeft size={14} /> Enter 发送</span>
        <span><Clock3 size={14} /> 实时输出</span>
      </div>
    </div>
  );
}

function ProcessingIndicator() {
  return (
    <div className="message assistant processing-message" aria-live="polite" aria-label="正在处理中">
      <div className="message-icon">
        <Loader2 className="spin" size={16} />
      </div>
      <div className="processing-body">
        <span>正在处理中</span>
        <span className="processing-dots" aria-hidden="true">
          <i />
          <i />
          <i />
        </span>
      </div>
    </div>
  );
}

function upsertHeadBy<T extends Record<K, string>, K extends keyof T>(items: T[], next: T, key: K) {
  const index = items.findIndex((item) => item[key] === next[key]);
  if (index === -1) {
    return [next, ...items];
  }
  // 命中已有项时，把它从原位置移到头部并替换为最新值，体现"活跃线程置顶"语义。
  const copy = [...items];
  copy.splice(index, 1);
  return [next, ...copy];
}

function upsertTailBy<T extends Record<K, string>, K extends keyof T>(items: T[], next: T, key: K) {
  const index = items.findIndex((item) => item[key] === next[key]);
  if (index === -1) {
    return [...items, next];
  }
  const copy = [...items];
  copy[index] = next;
  return copy;
}

function upsertManyTailBy<T extends Record<K, string>, K extends keyof T>(items: T[], nextItems: T[], key: K) {
  return nextItems.reduce((current, next) => upsertTailBy(current, next, key), items);
}

function shortId(value: string) {
  return value.replace(/^([a-z]+-)/, "").slice(0, 8);
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  }).format(new Date(value));
}

function formatDateTime24(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).format(new Date(value));
}

function formatDurationBetween(start: string, end: string) {
  const startTime = new Date(start).getTime();
  const endTime = new Date(end).getTime();
  if (!Number.isFinite(startTime) || !Number.isFinite(endTime) || endTime < startTime) {
    return "计算中";
  }
  const totalSeconds = Math.max(0, Math.round((endTime - startTime) / 1000));
  if (totalSeconds < 1) {
    return "<1s";
  }
  if (totalSeconds < 60) {
    return `${totalSeconds}s`;
  }
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes < 60) {
    return seconds > 0 ? `${minutes}m ${seconds}s` : `${minutes}m`;
  }
  const hours = Math.floor(minutes / 60);
  const restMinutes = minutes % 60;
  return restMinutes > 0 ? `${hours}h ${restMinutes}m` : `${hours}h`;
}

function taskOutputContent(
  taskId: string,
  messages: ChatMessage[],
  invocationMessages: InvocationMessage[]
) {
  const chatOutput = messages
    .filter((message) => message.taskId === taskId && message.role === "assistant" && message.content.trim())
    .map((message) => message.content.trim())
    .join("\n\n");
  if (chatOutput) {
    return chatOutput;
  }
  return invocationMessages
    .filter((message) => message.taskId === taskId && message.content?.trim())
    .map((message) => message.content?.trim())
    .filter((content): content is string => Boolean(content))
    .join("\n");
}

function isNearBottom(element: HTMLElement, threshold = 80) {
  return element.scrollHeight - element.scrollTop - element.clientHeight <= threshold;
}

function scrollToBottom(element: HTMLElement | null) {
  element?.scrollTo({ top: element.scrollHeight, behavior: "auto" });
}

function ensureBootThread() {
  pendingBootThread ??= createThread().finally(() => {
    pendingBootThread = null;
  });
  return pendingBootThread;
}

function withTimeout<T>(promise: Promise<T>, timeoutMs: number) {
  return new Promise<T>((resolve, reject) => {
    const timer = window.setTimeout(() => reject(new Error("Request timed out")), timeoutMs);
    promise
      .then(resolve)
      .catch(reject)
      .finally(() => window.clearTimeout(timer));
  });
}

type DagNode = {
  task: Task;
  level: number;
  childCount: number;
};

const DAG_NODE_WIDTH = 236;
const DAG_NODE_HEIGHT = 76;

const defaultDagEdgeOptions = {
  type: "step",
  markerEnd: {
    type: MarkerType.ArrowClosed,
    width: 18,
    height: 18,
    color: "#536176"
  },
  style: { strokeWidth: 2.3, stroke: "#536176" }
};

type TaskFlowNodeData = {
  task: Task;
  index: number;
  title: string;
  statusLabel: string;
  agentLabel: string;
  childCount: number;
  expanded: boolean;
};

type TaskFlowNode = Node<TaskFlowNodeData, "task">;

function DagPanel({
  tasks,
  agents,
  messages,
  traceId,
  invocationMessages,
  onCollapse
}: {
  tasks: Task[];
  agents: AgentStatus[];
  messages: ChatMessage[];
  traceId: string | null;
  invocationMessages: InvocationMessage[];
  onCollapse: () => void;
}) {
  const dagNodes = useMemo(
    () => buildDagNodes(tasks),
    [tasks]
  );
  const [expandedTaskId, setExpandedTaskId] = useState<string | null>(null);
  const nodeTypes = useMemo(() => ({ task: TaskFlowNodeCard }), []);
  const flowNodes = useMemo(
    () => buildTaskFlowNodes(dagNodes, agents, expandedTaskId),
    [agents, dagNodes, expandedTaskId]
  );
  const flowEdges = useMemo(() => buildTaskFlowEdges(tasks, dagNodes), [dagNodes, tasks]);
  const laidOutNodes = useMemo(
    () => getDagreLayout(flowNodes, flowEdges),
    [flowEdges, flowNodes]
  );
  const selectedDagNode = useMemo(
    () => dagNodes.find((node) => node.task.taskId === expandedTaskId) ?? null,
    [dagNodes, expandedTaskId]
  );
  const handleNodeClick = useCallback((_: unknown, node: TaskFlowNode) => {
    setExpandedTaskId((current) => (current === node.id ? null : node.id));
  }, []);

  return (
    <aside className="steps-panel">
      <div className="panel-heading">
        <button className="icon-button panel-toggle" onClick={onCollapse} aria-label="收起 DAG">
          <ChevronsRight size={17} />
        </button>
        <span>任务视图</span>
      </div>
      {dagNodes.length === 0 ? (
        <div className="steps-empty">
          <Sparkles size={20} />
          <strong>等待 DAG 生成</strong>
          <span>发送消息后，当前 thread 的任务图会显示在这里</span>
        </div>
      ) : (
        <div className="dag-graph" aria-label="Thread task DAG">
          <ReactFlow
            key={`${traceId ?? "thread"}-${tasks.length}`}
            nodes={laidOutNodes}
            edges={flowEdges}
            nodeTypes={nodeTypes}
            defaultEdgeOptions={defaultDagEdgeOptions}
            fitView
            fitViewOptions={{ padding: 0.18 }}
            minZoom={0.25}
            maxZoom={1.4}
            nodesDraggable={false}
            nodesConnectable={false}
            elementsSelectable={false}
            edgesReconnectable={false}
            onNodeClick={handleNodeClick}
            proOptions={{ hideAttribution: true }}
          >
            <Background color="#d7dde8" gap={24} size={1} />
            <Controls showInteractive={false} />
          </ReactFlow>
        </div>
      )}
      {selectedDagNode ? (
        <DagNodeInspector
          node={selectedDagNode}
          index={dagNodes.findIndex((node) => node.task.taskId === selectedDagNode.task.taskId) + 1}
          agentLabel={agentName(agents, selectedDagNode.task.agentId)}
          output={taskOutputContent(selectedDagNode.task.taskId, messages, invocationMessages)}
          onClose={() => setExpandedTaskId(null)}
        />
      ) : null}
    </aside>
  );
}

function TaskFlowNodeCard({ data }: NodeProps<TaskFlowNode>) {
  const status = data.task.status;
  const title = data.title;
  return (
    <article className={`dag-node-wrap ${data.expanded ? "expanded" : ""}`}>
      <Handle type="target" position={Position.Top} className="dag-node-handle" />
      <div className={`dag-node ${status}`}>
        <div className="dag-node-main">
          <span className="dag-node-signal" aria-hidden="true" />
          <strong>{title}</strong>
        </div>
        <span className={`dag-node-status ${status}`}>{data.statusLabel}</span>
        <span className="dag-node-agent">
          <Bot size={12} />
          {data.agentLabel}
        </span>
        <span className="dag-node-index">Task {data.index}</span>
      </div>
      <Handle type="source" position={Position.Bottom} className="dag-node-handle" />
    </article>
  );
}

function DagNodeInspector({
  node,
  index,
  agentLabel,
  output,
  onClose
}: {
  node: DagNode;
  index: number;
  agentLabel: string;
  output: string;
  onClose: () => void;
}) {
  const task = node.task;
  const { label } = stepStatusMeta(task.status);
  const duration = formatDurationBetween(task.createdAt, task.updatedAt);
  return (
    <section className="dag-inspector" aria-label="Selected task details">
      <div className="dag-inspector-head">
        <span>Task {index} details</span>
        <button type="button" onClick={onClose} aria-label="Close task details">
          ×
        </button>
      </div>
      <div className="dag-detail-row">
        <span>agent</span>
        <strong>{agentLabel}</strong>
      </div>
      <div className="dag-detail-row">
        <span>任务执行状态</span>
        <strong>{label}</strong>
      </div>
      <div className="dag-detail-row">
        <span>任务创建时间</span>
        <strong>{formatDateTime24(task.createdAt)}</strong>
      </div>
      <div className="dag-detail-row">
        <span>任务耗时</span>
        <strong>{duration}</strong>
      </div>
      <div className="dag-detail-block">
        <span>具体任务内容</span>
        <p>{task.context?.trim() || "(no context)"}</p>
      </div>
      <div className="dag-detail-block">
        <span>任务输出内容</span>
        <p>{output || "暂无输出"}</p>
      </div>
    </section>
  );
}

function buildTaskFlowNodes(
  dagNodes: DagNode[],
  agents: AgentStatus[],
  expandedTaskId: string | null
): TaskFlowNode[] {
  return dagNodes.map((node, index) => {
    const task = node.task;
    const { label } = stepStatusMeta(task.status);
    const expanded = task.taskId === expandedTaskId;
    return {
      id: task.taskId,
      type: "task",
      position: { x: 0, y: 0 },
      data: {
        task,
        index: index + 1,
        title: task.context?.trim() || "(no context)",
        statusLabel: label,
        agentLabel: agentName(agents, task.agentId),
        childCount: node.childCount,
        expanded
      }
    };
  });
}

function buildTaskFlowEdges(tasks: Task[], dagNodes: DagNode[]): Edge[] {
  const taskIds = new Set(tasks.map((task) => task.taskId));
  const childCounts = new Map(dagNodes.map((node) => [node.task.taskId, node.childCount]));
  return tasks.flatMap((task) => (
    task.dependsOn
      .filter((parentId) => taskIds.has(parentId))
      .map((parentId) => {
        const branched = (childCounts.get(parentId) ?? 0) > 1 || task.dependsOn.length > 1;
        return {
          id: `${parentId}->${task.taskId}`,
          source: parentId,
          target: task.taskId,
          type: "step",
          className: branched ? "dag-edge--branch" : undefined,
          style: branched ? { strokeDasharray: "7 7" } : undefined
        };
      })
  ));
}

function getFlowNodeHeight(node: TaskFlowNode) {
  return DAG_NODE_HEIGHT;
}

function getDagreLayout(nodes: TaskFlowNode[], edges: Edge[]): TaskFlowNode[] {
  const graph = new dagre.graphlib.Graph();
  graph.setDefaultEdgeLabel(() => ({}));
  graph.setGraph({
    rankdir: "TB",
    nodesep: 112,
    ranksep: 88,
    marginx: 80,
    marginy: 80
  });

  nodes.forEach((node) => {
    graph.setNode(node.id, {
      width: DAG_NODE_WIDTH,
      height: getFlowNodeHeight(node)
    });
  });
  edges.forEach((edge) => {
    graph.setEdge(edge.source, edge.target);
  });
  dagre.layout(graph);

  return nodes.map((node) => {
    const layoutNode = graph.node(node.id);
    return {
      ...node,
      targetPosition: Position.Top,
      sourcePosition: Position.Bottom,
      position: {
        x: layoutNode.x - DAG_NODE_WIDTH / 2,
        y: layoutNode.y - getFlowNodeHeight(node) / 2
      }
    };
  });
}

function buildDagNodes(tasks: Task[]): DagNode[] {
  const sorted = [...tasks].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  const byId = new Map(sorted.map((task) => [task.taskId, task]));
  const childCounts = new Map<string, number>();
  for (const task of sorted) {
    for (const parentId of task.dependsOn) {
      childCounts.set(parentId, (childCounts.get(parentId) ?? 0) + 1);
    }
  }
  const levelCache = new Map<string, number>();
  const resolving = new Set<string>();

  function levelFor(task: Task): number {
    const cached = levelCache.get(task.taskId);
    if (cached !== undefined) {
      return cached;
    }
    if (resolving.has(task.taskId) || task.dependsOn.length === 0) {
      levelCache.set(task.taskId, 0);
      return 0;
    }
    resolving.add(task.taskId);
    const parentLevels = task.dependsOn
      .map((parentId) => byId.get(parentId))
      .filter((parent): parent is Task => Boolean(parent))
      .map((parent) => levelFor(parent));
    resolving.delete(task.taskId);
    const nextLevel = parentLevels.length === 0 ? 0 : Math.max(...parentLevels) + 1;
    levelCache.set(task.taskId, nextLevel);
    return nextLevel;
  }

  return sorted
    .map((task) => ({
      task,
      level: levelFor(task),
      childCount: childCounts.get(task.taskId) ?? 0
    }))
    .sort((a, b) => a.level - b.level || a.task.createdAt.localeCompare(b.task.createdAt));
}

function agentName(agents: AgentStatus[], agentId: string): string {
  if (agentId === "claudecode") {
    return "ClaudeCode";
  }
  const agent = agents.find((item) => item.agentId === agentId);
  return agent?.displayName || agentId;
}

function messageAgentMeta(message: ChatMessage, agents: AgentStatus[]) {
  if (message.role === "user") {
    return {
      label: "You",
      className: "from-user",
      icon: <UserRound size={16} />
    };
  }
  if (message.agentId === "masteragent" || (!message.agentId && !message.invocationId && !message.taskId)) {
    return {
      label: "MasterAgent",
      className: "from-masteragent",
      icon: <Sparkles size={16} />
    };
  }
  if (message.agentId) {
    return {
      label: agentName(agents, message.agentId),
      className: `from-${message.agentId.replace(/[^a-zA-Z0-9_-]/g, "-")}`,
      icon: <Bot size={16} />
    };
  }
  return {
    label: "Agent",
    className: "from-agent",
    icon: <Bot size={16} />
  };
}

const STEP_STATUS_META: Record<string, { icon: LucideIcon; label: string }> = {
  queued: { icon: Circle, label: "queued" },
  processing: { icon: Loader2, label: "running" },
  completed: { icon: CheckCircle2, label: "done" },
  failed: { icon: XCircle, label: "failed" },
  blocked: { icon: Ban, label: "blocked" },
  canceled: { icon: XCircle, label: "canceled" }
};

function stepStatusMeta(status: string): { icon: LucideIcon; label: string } {
  return STEP_STATUS_META[status] ?? { icon: Circle, label: status };
}
