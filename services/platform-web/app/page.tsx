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
  ArrowUp,
  ArrowDown,
  Square,
  Sparkles,
  Menu,
  X,
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
  ReactFlow,
  useReactFlow,
  useStore
} from "@xyflow/react";
import type { Edge, Node, NodeProps } from "@xyflow/react";
import dagre from "dagre";
import {
  AgentStatus,
  cancelThreadWork,
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
import { mergeChatMessages } from "../lib/chat-messages";
import { Button } from "@/components/ui/button";
import { ComposerInput } from "@/components/workspace/composer-input";
import { Skeleton } from "@/components/ui/skeleton";
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetTrigger } from "@/components/ui/sheet";
import { ThreadSidebar } from "@/components/workspace/thread-sidebar";
import { ThemeMenu } from "@/components/workspace/theme-menu";
import { StatusBadge } from "@/components/workspace/status-badge";
import { ChatMessageItem } from "@/components/workspace/chat-message-item";
import { useMessageScroll } from "@/lib/use-message-scroll";

type LoadState = "booting" | "ready" | "error";
type PendingSubmission = { threadId: string; cancellation: Promise<boolean> | null };

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
  const [isStopping, setIsStopping] = useState(false);
  const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(false);
  const [isDagCollapsed, setIsDagCollapsed] = useState(false);
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);
  const [mobileDagOpen, setMobileDagOpen] = useState(false);
  const [isCreating, setIsCreating] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

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
  const { viewportRef, railRef, onScroll, followLatest, showLatest } = useMessageScroll(messages, activeThreadId, loadState === "ready", isAwaitingAgentOutput);

  const activeTraceIdRef = useRef<string | null>(null);
  const activeThreadIdRef = useRef<string | null>(null);
  const historyRequestIdRef = useRef(0);
  const pendingSubmissionRef = useRef<PendingSubmission | null>(null);

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
    // A → B → A must not make the first A request eligible again.
    const requestId = ++historyRequestIdRef.current;
    // 两个接口独立 fetch + 独立写状态：单边 5xx 不会让另一边的数据连带消失。
    const loadMessages = listMessages(threadId).then((next) => {
      if (historyRequestIdRef.current === requestId && isThreadStillActive(threadId)) {
        setMessages((current) => mergeChatMessages(current, next, "snapshot"));
      }
    });
    const loadInvocationMessages = listInvocationMessages(threadId).then((next) => {
      if (historyRequestIdRef.current === requestId && isThreadStillActive(threadId)) {
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
    // A closing socket can still deliver queued events for the previous thread.
    if (event.threadId !== activeThreadIdRef.current) return;
    if (event.type === REALTIME_EVENT_TYPES.chatMessage) {
      setMessages((current) => mergeChatMessages(current, [event.payload as ChatMessage]));
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

  const handleDeleteThread = useCallback(async (threadId: string) => {
    await deleteThread(threadId);
    setThreads((current) => current.filter((thread) => thread.threadId !== threadId));
    if (activeThreadIdRef.current === threadId) {
      setActiveThreadId(null);
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
    loadThreadData(activeThreadId).catch(() => undefined);
  }, [activeThreadId, loadThreadData]);

  async function handleNewThread() {
    if (isCreating) return;
    setIsCreating(true);
    setNotice(null);
    try {
      const thread = await createThread();
      setThreads((current) => [thread, ...current]);
      setActiveThreadId(thread.threadId);
      setMobileSidebarOpen(false);
    } catch (error) {
      setMobileSidebarOpen(false);
      setNotice(`创建失败：${error instanceof Error ? error.message : "请稍后重试"}`);
    } finally {
      setIsCreating(false);
    }
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const content = input.trim();
    if (!content || !activeThreadId || pendingSubmissionRef.current || isStopping) {
      return;
    }
    const threadId = activeThreadId;
    const submission: PendingSubmission = { threadId, cancellation: null };
    pendingSubmissionRef.current = submission;
    setInput("");
    setNotice(null);
    setIsSending(true);
    followLatest();
    try {
      const result = await sendMessage(threadId, content);
      // Only a cancellation attached to this request can invalidate its response.
      // Wait for the outcome so a failed Stop does not discard a successful send.
      const canceled = submission.cancellation ? await submission.cancellation : false;
      if (!isThreadStillActive(threadId) || canceled) {
        return;
      }
      setThreads((current) => upsertHeadBy(current, result.thread, "threadId"));
      setMessages((current) => mergeChatMessages(current,
        result.assistantMessage ? [result.message, result.assistantMessage] : [result.message]));
      setTasks((current) => upsertManyTailBy(current, result.tasks, "taskId"));
      await refreshAgents().catch(() => undefined);
    } catch (error) {
      if (isThreadStillActive(threadId)) {
        setNotice(`发送失败：${error instanceof Error ? error.message : "请稍后重试"}`);
      }
    } finally {
      pendingSubmissionRef.current = null;
      setIsSending(false);
    }
  }

  async function handleCancel() {
    if (!activeThreadId || isStopping) {
      return;
    }
    const threadId = activeThreadId;
    setIsStopping(true);
    const cancellation = cancelThreadWork(threadId);
    const submission = pendingSubmissionRef.current;
    if (submission?.threadId === threadId) {
      submission.cancellation = cancellation.then(() => true, () => false);
    }
    try {
      const result = await cancellation;
      if (isThreadStillActive(threadId)) {
        setTasks((current) => current.map((task) => (
          result.canceledTaskIds.includes(task.taskId)
            ? { ...task, status: "canceled" }
            : task
        )));
      }
      await Promise.allSettled([refreshThreads(), refetchTasks(), refreshAgents()]);
    } catch (error) {
      setNotice(`停止任务失败：${error instanceof Error ? error.message : "未知错误"}`);
    } finally {
      setIsStopping(false);
    }
  }

  // isSending 只是消息 POST 尚未确认，不能发取消请求，否则取消可能先于提交到达后端。
  // 只有平台已确认 thread/task 活跃后，停止按钮才会出现。
  const canCancelActiveWork = Boolean(activeThreadId) && (
    activeThread?.status === "running"
    || tasks.some((task) => task.status === "queued" || task.status === "processing")
  );

  if (loadState === "booting") {
    return <BootScreen label="正在连接协作空间" />;
  }

  if (loadState === "error") {
    return <BootScreen label="暂时无法连接平台" tone="danger" />;
  }

  return (
    <main className={`shell ${isSidebarCollapsed ? "sidebar-collapsed" : ""} ${isDagCollapsed ? "dag-collapsed" : ""}`}>
      <a href="#message-input" className="skip-link">跳转到消息输入</a>
      <aside className="sidebar desktop-sidebar">
        {isSidebarCollapsed ? (
          <div className="sidebar-collapsed-bar">
            <Button variant="ghost" size="icon" onClick={() => setIsSidebarCollapsed(false)} aria-label="展开历史会话"><ChevronsRight /></Button>
            <Button variant="ghost" size="icon" onClick={handleNewThread} disabled={isCreating} aria-label="新建任务"><Plus /></Button>
          </div>
        ) : <ThreadSidebar threads={threads} agents={agents} activeThreadId={activeThreadId} creating={isCreating} onCreate={handleNewThread} onSelect={setActiveThreadId} onDelete={handleDeleteThread} onCollapse={() => setIsSidebarCollapsed(true)} />}
      </aside>
      <section className="workspace" aria-label="协作对话">
        <header className="workspace-header">
          <Sheet open={mobileSidebarOpen} onOpenChange={setMobileSidebarOpen}>
            <SheetTrigger asChild><Button variant="ghost" size="icon" className="md:hidden" aria-label="打开会话列表"><Menu /></Button></SheetTrigger>
            <SheetContent side="left" className="w-[min(320px,90vw)] gap-0 p-0">
              <SheetHeader className="sr-only"><SheetTitle>历史会话</SheetTitle><SheetDescription>选择会话或新建任务</SheetDescription></SheetHeader>
              <ThreadSidebar threads={threads} agents={agents} activeThreadId={activeThreadId} creating={isCreating} onCreate={handleNewThread} onSelect={(id) => { setActiveThreadId(id); setMobileSidebarOpen(false); }} onDelete={handleDeleteThread} />
            </SheetContent>
          </Sheet>
          <div className="workspace-heading">
            <h1>{activeThread?.title ?? "Agent Crossing"}</h1>
            <p>协作对话</p>
          </div>
          {activeThread && <StatusBadge status={activeThread.status} />}
          <ThemeMenu />
          <Sheet open={mobileDagOpen} onOpenChange={setMobileDagOpen}>
            <SheetTrigger asChild><Button variant="ghost" size="icon" className="xl:hidden" aria-label="打开任务视图"><GitBranch /></Button></SheetTrigger>
            <SheetContent side="right" showCloseButton={false} className="w-[min(380px,95vw)] gap-0 p-0">
              <SheetHeader className="sr-only"><SheetTitle>任务视图</SheetTitle><SheetDescription>查看当前会话的任务依赖和执行详情</SheetDescription></SheetHeader>
              <DagPanel tasks={tasks} agents={agents} messages={messages} traceId={activeThread?.traceId ?? null} invocationMessages={invocationMessages} onCollapse={() => setMobileDagOpen(false)} />
            </SheetContent>
          </Sheet>
        </header>
        {notice && <div role="alert" className="notice"><span>{notice}</span><Button variant="ghost" size="icon-sm" onClick={() => setNotice(null)} aria-label="关闭提示"><X /></Button></div>}
        <div className="conversation">
          <div className="message-scroll-area">
            <div className="message-viewport" ref={viewportRef} onScroll={onScroll}>
              <div className="message-rail" ref={railRef}>
                {messages.length === 0 ? (
                  <EmptyConversation />
                ) : (
                  messages.map((message) => (
                    <ChatMessageItem key={message.messageId} message={message} {...messageAgentMeta(message, agents)} />
                  ))
                )}
                {isAwaitingAgentOutput && !messages.some((message) => message.status === "streaming") ? <ProcessingIndicator /> : null}
              </div>
            </div>
            {showLatest && <Button className="jump-to-latest rounded-full shadow-sm" variant="outline" size="sm" onClick={followLatest}><ArrowDown />回到最新消息</Button>}
          </div>

          <form className="composer" onSubmit={handleSubmit}>
            <div className="composer-rail">
              <label htmlFor="message-input" className="sr-only">消息内容</label>
              <ComposerInput
                id="message-input"
                aria-describedby="composer-help"
                value={input}
                onChange={(event) => setInput(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
                    event.preventDefault();
                    event.currentTarget.form?.requestSubmit();
                  }
                }}
                placeholder="发送消息，或用 @ 指定 Agent"
                rows={1}
              />
              <div className="composer-actions">
                <span id="composer-help" className="composer-hint"><kbd>Enter</kbd> 发送 · <kbd>Shift + Enter</kbd> 换行</span>
                <Button
                  className="size-9 rounded-full bg-foreground text-background shadow-none hover:bg-foreground/85 disabled:bg-muted disabled:text-muted-foreground disabled:opacity-100"
                  size="icon"
                  type={canCancelActiveWork ? "button" : "submit"}
                  onClick={canCancelActiveWork ? () => void handleCancel() : undefined}
                  disabled={canCancelActiveWork ? isStopping : !activeThreadId || !input.trim() || isSending}
                  aria-label={canCancelActiveWork ? "停止当前任务" : "发送消息"}
                  title={canCancelActiveWork ? "停止当前任务" : "发送消息"}
                >
                  {canCancelActiveWork
                    ? (isStopping ? <Loader2 className="spin" size={18} /> : <Square className="size-3.5" fill="currentColor" />)
                    : (isSending ? <Loader2 className="spin" size={18} /> : <ArrowUp className="size-5" strokeWidth={2.25} />)}
                </Button>
              </div>
            </div>
          </form>
        </div>
      </section>

      {isDagCollapsed ? (
        <aside className="dag-collapsed-panel desktop-dag" aria-label="Collapsed DAG">
          <Button variant="ghost" size="icon" onClick={() => setIsDagCollapsed(false)} aria-label="展开 DAG">
            <ChevronsLeft size={18} />
          </Button>
        </aside>
      ) : (
        <div className="desktop-dag min-h-0">
        <DagPanel
          tasks={tasks}
          agents={agents}
          messages={messages}
          traceId={activeThread?.traceId ?? null}
          invocationMessages={invocationMessages}
          onCollapse={() => setIsDagCollapsed(true)}
        />
        </div>
      )}
    </main>
  );
}

function BootScreen({ label, tone = "normal" }: { label: string; tone?: "normal" | "danger" }) {
  return (
    <main className="boot-screen">
      <div className={`boot-card ${tone}`}>
        <span className="brand-wordmark">Agent Crossing</span>
        <span role={tone === "danger" ? "alert" : "status"}>{label}</span>
        {tone === "danger"
          ? <Button variant="outline" onClick={() => window.location.reload()}>重新连接</Button>
          : <div aria-hidden="true" className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-4 w-4/5" /><Skeleton className="h-4 w-3/5" /></div>}
      </div>
    </main>
  );
}

function EmptyConversation() {
  return (
    <div className="empty-conversation">
      <div className="empty-orbit">
        <Bot size={30} />
      </div>
      <h2>开始一次 Agent 协作</h2>
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
    color: "var(--edge)"
  },
  style: { strokeWidth: 2.3, stroke: "var(--edge)" }
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
        <GitBranch size={16} />
        <h2>任务视图</h2>
        <span className="text-xs text-muted-foreground font-mono">{tasks.length}</span>
        <Button variant="ghost" size="icon-sm" className="panel-toggle" onClick={onCollapse} aria-label="收起 DAG"><ChevronsRight /></Button>
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
            <FitTaskViewport />
            <Background color="var(--border)" gap={24} size={1} />
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

// Inspector and responsive drawers resize the graph without remounting its nodes.
function FitTaskViewport() {
  const width = useStore((state) => state.width);
  const height = useStore((state) => state.height);
  const { fitView } = useReactFlow();
  useEffect(() => {
    if (width > 0 && height > 0) void fitView({ padding: 0.18, maxZoom: 1.1 });
  }, [width, height, fitView]);
  return null;
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
        <StatusBadge status={status} />
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
        <Button variant="ghost" size="icon-sm" type="button" onClick={onClose} aria-label="关闭任务详情"><X /></Button>
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
