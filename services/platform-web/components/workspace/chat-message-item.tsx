"use client";

import type { ReactNode } from "react";
import { CircleAlert, Loader2, Square } from "lucide-react";
import type { ChatMessage } from "@/lib/api";
import { CopyButton } from "./copy-button";
import { MessageContent } from "./message-content";

const timeFormat = new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false });

export function ChatMessageItem({ message, label, icon, className }: {
  message: ChatMessage; label: string; icon: ReactNode; className: string;
}) {
  const isUser = message.role === "user";
  const date = new Date(message.createdAt);
  const validDate = !Number.isNaN(date.getTime());
  const status = message.status;
  return (
    <article className={`message ${isUser ? "user" : "assistant"} ${className}`} aria-label={isUser ? "你的消息" : `${label} 的回复`}>
      <div className="message-body">
        {!isUser && <div className="message-meta">
          <span className="message-icon" aria-hidden="true">{icon}</span>
          <span>{label}</span>
          {status !== "completed" && <span className="message-state" data-state={status} role="status">
            {status === "streaming" ? <><Loader2 className="spin" />生成中</>
              : status === "canceled" ? <><Square />已停止</>
                : status === "failed" ? <><CircleAlert />生成失败</> : "等待生成"}
          </span>}
        </div>}
        {isUser ? <p className="message-content user-message-content">{message.content}</p> : <MessageContent content={message.content} />}
        {!message.content && ["failed", "canceled"].includes(status) && <p className="message-empty">本次未生成回复。</p>}
        <div className="message-actions">
          <CopyButton text={message.content} compact />
          {validDate && <time dateTime={message.createdAt} title={date.toLocaleString("zh-CN", { hour12: false })}>{timeFormat.format(date)}</time>}
        </div>
      </div>
    </article>
  );
}
