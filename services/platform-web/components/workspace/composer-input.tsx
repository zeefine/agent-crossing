"use client";

import { useLayoutEffect, useRef, type ComponentPropsWithoutRef } from "react";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";

function fitContent(element: HTMLTextAreaElement) {
  // Reset first so deleting text or clearing a sent message also shrinks the field.
  // CSS caps the height; longer drafts scroll without moving the action row.
  element.style.height = "auto";
  element.style.height = `${element.scrollHeight}px`;
}

export function ComposerInput({ value, className, ...props }: ComponentPropsWithoutRef<"textarea">) {
  const ref = useRef<HTMLTextAreaElement>(null);

  useLayoutEffect(() => {
    if (ref.current) fitContent(ref.current);
  }, [value]);

  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    let width = element.clientWidth;
    const observer = new ResizeObserver(() => {
      if (element.clientWidth === width) return;
      width = element.clientWidth;
      fitContent(element);
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  return (
    <Textarea
      {...props}
      ref={ref}
      value={value}
      className={cn("field-sizing-fixed min-h-6 resize-none rounded-none border-0 bg-transparent p-0 text-base leading-6 shadow-none focus-visible:ring-0 md:text-[15px] dark:bg-transparent", className)}
    />
  );
}
