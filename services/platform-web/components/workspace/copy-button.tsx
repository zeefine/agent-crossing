"use client";

import { useEffect, useRef, useState } from "react";
import { Check, Copy, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";

export function CopyButton({ text, kind = "消息", compact = false }: { text: string; kind?: "消息" | "代码"; compact?: boolean }) {
  const [state, setState] = useState<"idle" | "copying" | "copied" | "error">("idle");
  const operation = useRef<symbol | null>(null);
  useEffect(() => {
    setState("idle");
    return () => { operation.current = null; };
  }, [text]);
  useEffect(() => {
    if (state !== "copied") return;
    const timer = window.setTimeout(() => setState("idle"), 2000);
    return () => window.clearTimeout(timer);
  }, [state]);

  async function copy() {
    const currentOperation = Symbol();
    operation.current = currentOperation;
    setState("copying");
    try {
      await navigator.clipboard.writeText(text);
      if (operation.current === currentOperation) setState("copied");
    } catch {
      if (operation.current === currentOperation) setState("error");
    }
  }
  const label = state === "copied" ? `${kind}已复制` : `复制${kind}`;
  return (
    <span className="copy-control">
      <Button type="button" variant="ghost" size={compact ? "icon-sm" : "sm"}
        className="text-muted-foreground" onClick={() => void copy()} disabled={!text || state === "copying"}
        aria-label={label} title={label}>
        {state === "copied" ? <Check /> : state === "copying" ? <Loader2 className="spin" /> : <Copy />}
        {!compact && <span>{state === "copied" ? "已复制" : `复制${kind}`}</span>}
      </Button>
      {state === "error" && <span role="status" className="copy-feedback">复制失败，请手动选择文字复制</span>}
      {state === "copied" && <span role="status" className="sr-only">{label}</span>}
    </span>
  );
}
