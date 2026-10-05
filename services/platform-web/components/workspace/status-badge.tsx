import { Badge } from "@/components/ui/badge";

const labels: Record<string, string> = {
  open: "待开始", idle: "空闲", queued: "排队中", processing: "运行中", running: "运行中",
  completed: "已完成", failed: "失败", blocked: "已阻塞", canceled: "已取消", streaming: "生成中"
};

export function StatusBadge({ status }: { status: string }) {
  const tone = ["running", "processing", "completed"].includes(status)
    ? "bg-accent text-primary"
    : ["failed", "blocked"].includes(status)
      ? "bg-destructive/10 text-destructive"
      : status === "queued"
        ? "bg-[var(--warning)]/10 text-[var(--warning)]"
        : "bg-secondary text-muted-foreground";
  return <Badge variant="outline" className={`status-badge rounded-sm border-transparent px-1.5 text-[10px] ${tone}`}>{labels[status] ?? status}</Badge>;
}
