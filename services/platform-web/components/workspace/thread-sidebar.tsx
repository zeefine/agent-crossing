"use client";

import { Bot, ChevronsLeft, MessageSquare, Plus } from "lucide-react";
import type { AgentStatus, ChatThread } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { StatusBadge } from "./status-badge";
import { DeleteThreadDialog } from "./delete-thread-dialog";

type Props = {
  threads: ChatThread[];
  agents: AgentStatus[];
  activeThreadId: string | null;
  creating: boolean;
  onCreate: () => void;
  onSelect: (id: string) => void;
  onDelete: (id: string) => Promise<void>;
  onCollapse?: () => void;
};

export function ThreadSidebar({ threads, agents, activeThreadId, creating, onCreate, onSelect, onDelete, onCollapse }: Props) {
  return (
    <div className="sidebar-content">
      <header className="brand-row">
        <span className="brand-wordmark">Agent Crossing</span>
        {onCollapse && <Tooltip><TooltipTrigger asChild><Button variant="ghost" size="icon-sm" onClick={onCollapse} aria-label="收起历史会话"><ChevronsLeft /></Button></TooltipTrigger><TooltipContent>收起历史会话</TooltipContent></Tooltip>}
      </header>
      <Button className="w-full justify-start" onClick={onCreate} disabled={creating}><Plus />{creating ? "正在创建" : "新建任务"}</Button>
      <section className="agent-strip" aria-label="当前在线 Agent">
        <h2 className="section-label">当前在线<span>{agents.length}</span></h2>
        {agents.length === 0 && <p className="text-sm text-muted-foreground">暂无可用 Agent</p>}
        {agents.map((agent) => (
          <div key={agent.agentId} className="agent-row">
            <span className="agent-avatar"><Bot size={16} /></span>
            <span className="truncate">{agent.agentId === "claudecode" ? "ClaudeCode" : agent.displayName || agent.agentId}</span>
            <StatusBadge status={agent.status} />
          </div>
        ))}
      </section>
      <Separator />
      <h2 className="section-label">历史会话<span>{threads.length}</span></h2>
      <nav className="thread-list" aria-label="历史会话">
        {threads.length === 0 && <p className="text-sm text-muted-foreground">新建任务，开始一次协作。</p>}
        {threads.map((thread) => (
          <div key={thread.threadId} className={`thread-item-wrapper ${thread.threadId === activeThreadId ? "active" : ""}`}>
            <Button variant="ghost" className="thread-item h-auto justify-start" aria-current={thread.threadId === activeThreadId ? "page" : undefined} onClick={() => onSelect(thread.threadId)}>
              <MessageSquare />
              <span className="thread-copy"><strong>{thread.title}</strong><small>{new Date(thread.createdAt).toLocaleDateString("zh-CN", { month: "short", day: "numeric" })}</small></span>
            </Button>
            <DeleteThreadDialog title={thread.title} onDelete={() => onDelete(thread.threadId)} />
          </div>
        ))}
      </nav>
      <footer className="sidebar-footer"><Bot size={15} /><span>多 Agent 协作空间</span></footer>
    </div>
  );
}
