"use client";

import { useCallback, useLayoutEffect, useRef, useState, type UIEvent } from "react";
import type { ChatMessage } from "./api";

export function useMessageScroll(messages: ChatMessage[], threadId: string | null, ready: boolean, awaiting: boolean) {
  const viewportRef = useRef<HTMLDivElement>(null);
  const railRef = useRef<HTMLDivElement>(null);
  const following = useRef(true);
  const lastTop = useRef(0);
  const [showLatest, setShowLatest] = useState(false);

  const scrollToBottom = useCallback(() => {
    const viewport = viewportRef.current;
    if (!viewport) return;
    viewport.scrollTop = viewport.scrollHeight;
    lastTop.current = viewport.scrollTop;
  }, []);

  const followLatest = useCallback(() => {
    following.current = true;
    setShowLatest(false);
    scrollToBottom();
  }, [scrollToBottom]);

  useLayoutEffect(() => { followLatest(); }, [threadId, followLatest]);
  useLayoutEffect(() => {
    if (following.current) scrollToBottom();
  }, [messages, ready, awaiting, scrollToBottom]);

  useLayoutEffect(() => {
    if (!ready || !viewportRef.current || !railRef.current) return;
    // Font loading, wrapping and composer height can resize the viewport without a message event.
    const observer = new ResizeObserver(() => {
      if (following.current) scrollToBottom();
    });
    observer.observe(viewportRef.current);
    observer.observe(railRef.current);
    return () => observer.disconnect();
  }, [ready, scrollToBottom]);

  function onScroll(event: UIEvent<HTMLDivElement>) {
    const viewport = event.currentTarget;
    const atBottom = viewport.scrollHeight - viewport.clientHeight - viewport.scrollTop <= 4;
    if (viewport.scrollTop < lastTop.current - 1) following.current = false;
    else if (atBottom) following.current = true;
    lastTop.current = viewport.scrollTop;
    setShowLatest(!following.current && !atBottom);
  }

  return { viewportRef, railRef, onScroll, followLatest, showLatest };
}
