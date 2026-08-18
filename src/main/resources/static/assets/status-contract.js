(function (global) {
  const labels = {
    IDLE: "未开始", QUEUED: "排队中", PENDING: "等待处理", CLONING: "准备仓库",
    SYNCING: "同步中", RUNNING: "处理中",
    ACTIVE: "已连接", INVALID: "需重新授权",
    READY: "就绪", SUCCESS: "成功", NO_RESULTS: "无结果", PARTIAL: "部分完成", DEGRADED: "降级",
    FAILED: "失败", STALE: "待更新", DISABLED: "已停用", CANCELLED: "已取消", INTERRUPTED: "已中断",
    CHUNKED: "已分块", EMBEDDING: "向量化", INDEXING: "写入索引", EXCLUDED: "已排除"
  };
  const glyphs = {
    IDLE: "○", QUEUED: "◷", PENDING: "◷", CLONING: "◐", SYNCING: "◐",
    RUNNING: "◐", ACTIVE: "✓", INVALID: "!", READY: "✓", SUCCESS: "✓", NO_RESULTS: "○",
    PARTIAL: "◒", DEGRADED: "△", FAILED: "!", STALE: "↻", DISABLED: "—", CANCELLED: "×",
    INTERRUPTED: "■", CHUNKED: "▦", EMBEDDING: "◐", INDEXING: "◐", EXCLUDED: "×"
  };
  global.NexusStatus = {
    label(status) { return labels[status] || status || "未知"; },
    glyph(status) { return glyphs[status] || "○"; },
    tone(status) {
      if (["ACTIVE", "READY", "SUCCESS"].includes(status)) return "good";
      if (["RUNNING", "EMBEDDING", "INDEXING", "QUEUED", "PENDING", "CLONING", "SYNCING"].includes(status)) return "active";
      if (["INVALID", "FAILED", "INTERRUPTED"].includes(status)) return "bad";
      if (["PARTIAL", "DEGRADED", "STALE"].includes(status)) return "warn";
      return "neutral";
    }
  };
})(window);
