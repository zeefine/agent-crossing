"use client";

import { useState } from "react";
import { Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle, AlertDialogTrigger } from "@/components/ui/alert-dialog";

export function DeleteThreadDialog({ title, onDelete }: { title: string; onDelete: () => Promise<void> }) {
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function confirm() {
    if (pending) return;
    setPending(true);
    setError(null);
    try {
      await onDelete();
      setOpen(false);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "删除失败，请稍后重试");
    } finally {
      setPending(false);
    }
  }

  return (
    <AlertDialog open={open} onOpenChange={(value) => { if (!pending) { setOpen(value); setError(null); } }}>
      <AlertDialogTrigger asChild>
        <Button variant="ghost" size="icon-sm" className="thread-delete" aria-label={`删除会话 ${title}`}><Trash2 /></Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>删除这个会话？</AlertDialogTitle>
          <AlertDialogDescription className="break-words">“{title}”及其关联记录将被删除，此操作不可撤销。</AlertDialogDescription>
        </AlertDialogHeader>
        {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
        <AlertDialogFooter>
          <AlertDialogCancel disabled={pending}>取消</AlertDialogCancel>
          <AlertDialogAction variant="destructive" disabled={pending} onClick={(event) => { event.preventDefault(); void confirm(); }}>
            {pending ? "正在删除" : "确认删除"}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
