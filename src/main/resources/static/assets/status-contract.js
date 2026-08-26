(function (global) {
  const labels = {
    IDLE: "未开始", QUEUED: "排队中", PENDING: "等待处理", CLONING: "准备仓库",
    SYNCING: "同步中", RUNNING: "处理中", UP: "正常", DOWN: "异常", UNKNOWN: "未知",
    ACTIVE: "启用", INVALID: "无效", READY: "就绪", SUCCESS: "成功", NO_RESULTS: "无结果",
    PARTIAL: "部分完成", DEGRADED: "降级", FAILED: "失败", STALE: "待更新", DISABLED: "已停用",
    UNAVAILABLE: "不可用", CANCELLED: "已取消", INTERRUPTED: "已中断", CHUNKED: "已分块",
    EMBEDDING: "向量化", INDEXING: "写入索引", EXCLUDED: "已排除",
    // 多源知识/语义候选状态（knowledge 多源检索页面使用，纯新增词条）
    CONFIRMED: "已确认", SUPPORTED: "有支撑", PARTIALLY_SUPPORTED: "部分支撑",
    REVIEW_REQUIRED: "需复核", CONFLICTED: "存在冲突", NO_EVIDENCE: "无证据",
    NO_RESULT: "无结果", EXTRACTED: "已抽取", VERIFIED: "已验证", OPEN: "待确认",
    RESOLVED: "已解决", REJECTED: "已拒绝", OBSOLETE: "已废弃"
  };
  const glyphs = {
    UP: "✓", DOWN: "!", UNKNOWN: "?", ACTIVE: "✓", INVALID: "!", IDLE: "○", QUEUED: "◌",
    RUNNING: "◐", READY: "✓", SUCCESS: "✓", NO_RESULTS: "○", PARTIAL: "◒", DEGRADED: "△",
    FAILED: "!", STALE: "↻", DISABLED: "—", UNAVAILABLE: "!", CANCELLED: "×", INTERRUPTED: "■",
    CHUNKED: "▦", EMBEDDING: "◐", INDEXING: "◐", EXCLUDED: "×",
    CONFIRMED: "✓", SUPPORTED: "✓", PARTIALLY_SUPPORTED: "◒", REVIEW_REQUIRED: "△",
    CONFLICTED: "!", NO_EVIDENCE: "○", NO_RESULT: "○", EXTRACTED: "◒", VERIFIED: "✓",
    OPEN: "△", RESOLVED: "✓", REJECTED: "×", OBSOLETE: "×"
  };
  global.NexusStatus = {
    label(status) { return labels[status] || status || "未知"; },
    glyph(status) { return glyphs[status] || "○"; },
    tone(status) {
      if (["ACTIVE", "READY", "SUCCESS", "CONFIRMED", "VERIFIED", "RESOLVED"].includes(status)) return "good";
      if (["RUNNING", "EMBEDDING", "INDEXING", "QUEUED", "PENDING", "CLONING", "SYNCING"].includes(status)) return "active";
      if (["INVALID", "FAILED", "INTERRUPTED", "CONFLICTED", "REJECTED"].includes(status)) return "bad";
      if (["PARTIAL", "DEGRADED", "STALE", "UNAVAILABLE", "PARTIALLY_SUPPORTED",
        "REVIEW_REQUIRED", "NO_EVIDENCE", "EXTRACTED", "OPEN"].includes(status)) return "warn";
      return "neutral";
    }
  };
})(window);
