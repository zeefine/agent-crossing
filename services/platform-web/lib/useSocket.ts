"use client";

import { useEffect, useRef } from "react";
import type { ChatEvent } from "./api";
import { isChatEvent } from "./realtime";
import { getCurrentUserId } from "./user";

type SocketHandlers = {
  threadId: string | null;
  onEvent: (event: ChatEvent) => void;
  onStatus?: (status: "connecting" | "open" | "closed") => void;
};

const RECONNECT_DELAYS = [500, 1000, 2000, 5000, 10000];

export function useSocket({ threadId, onEvent, onStatus }: SocketHandlers) {
  const eventHandlerRef = useRef(onEvent);
  const statusHandlerRef = useRef(onStatus);

  useEffect(() => {
    eventHandlerRef.current = onEvent;
  }, [onEvent]);

  useEffect(() => {
    statusHandlerRef.current = onStatus;
  }, [onStatus]);

  useEffect(() => {
    if (!threadId) {
      return;
    }

    const subscribedThreadId = threadId;
    const lastEventStorageKey = `agent-crossing:last-event-id:${subscribedThreadId}`;
    let lastEventId = readLastEventId(lastEventStorageKey);
    let closedByEffect = false;
    let reconnectAttempt = 0;
    let reconnectTimer: number | null = null;
    let socket: WebSocket | null = null;

    function connect() {
      statusHandlerRef.current?.("connecting");
      socket = new WebSocket(buildSocketUrl(subscribedThreadId));

      socket.addEventListener("open", () => {
        reconnectAttempt = 0;
        statusHandlerRef.current?.("open");
        socket?.send(JSON.stringify({ type: "subscribe", threadId: subscribedThreadId, lastEventId }));
      });

      socket.addEventListener("message", (message) => {
        try {
          const parsed = JSON.parse(message.data) as unknown;
          if (!isChatEvent(parsed)) {
            return;
          }
          const event = parsed as ChatEvent;
          const parsedEventId = Number(event.eventId);
          if (Number.isFinite(parsedEventId) && parsedEventId > lastEventId) {
            lastEventId = parsedEventId;
            window.localStorage.setItem(lastEventStorageKey, String(parsedEventId));
          }
          eventHandlerRef.current(event);
        } catch {
          // 忽略无法解析的运行时事件，避免单条坏消息断开实时通道。
        }
      });

      socket.addEventListener("close", () => {
        statusHandlerRef.current?.("closed");
        if (closedByEffect) {
          return;
        }
        const delay = RECONNECT_DELAYS[Math.min(reconnectAttempt, RECONNECT_DELAYS.length - 1)];
        reconnectAttempt += 1;
        reconnectTimer = window.setTimeout(connect, delay);
      });

      socket.addEventListener("error", () => {
        socket?.close();
      });
    }

    connect();

    return () => {
      closedByEffect = true;
      if (reconnectTimer !== null) {
        window.clearTimeout(reconnectTimer);
      }
      socket?.close();
    };
  }, [threadId]);
}

function buildSocketUrl(threadId: string) {
  const lastEventId = readLastEventId(`agent-crossing:last-event-id:${threadId}`);
  const explicitBaseUrl = process.env.NEXT_PUBLIC_PLATFORM_WS_URL;
  if (explicitBaseUrl) {
    const url = new URL(explicitBaseUrl);
    url.searchParams.set("threadId", threadId);
    url.searchParams.set("lastEventId", String(lastEventId));
    url.searchParams.set("userId", getCurrentUserId());
    return url.toString();
  }

  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  const host = window.location.hostname;
  const backendPort = window.location.port === "3000" ? "8080" : window.location.port;
  const port = backendPort ? `:${backendPort}` : "";
  return `${protocol}//${host}${port}/ws/chat?threadId=${encodeURIComponent(threadId)}&lastEventId=${lastEventId}&userId=${encodeURIComponent(getCurrentUserId())}`;
}

function readLastEventId(storageKey: string) {
  if (typeof window === "undefined") {
    return 0;
  }
  const stored = Number(window.localStorage.getItem(storageKey));
  return Number.isFinite(stored) && stored > 0 ? stored : 0;
}
