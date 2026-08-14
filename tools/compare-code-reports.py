#!/usr/bin/env python3
"""对比两组封神代码检索评测报告（如基线 A 与实验 E），输出指标与逐模式差异。"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EVAL = ROOT / "evaluation/fengshen-code-retrieval-eval-500.jsonl"


def load_report(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return {case["id"]: case for case in data["cases"]}


def metrics(cases):
    total = len(cases)
    ranks = [c["rank"] for c in cases.values()]
    top1 = sum(1 for r in ranks if r == 1)
    top5 = sum(1 for r in ranks if r is not None and r <= 5)
    top10 = sum(1 for r in ranks if r is not None and r <= 10)
    mrr = sum(1 / r for r in ranks if r is not None) / total
    latencies = sorted(c["latencyMs"] for c in cases.values())
    p50 = latencies[len(latencies) // 2]
    p95 = latencies[int(len(latencies) * 0.95) - 1] if len(latencies) >= 20 else latencies[-1]
    return {
        "total": total,
        "recall@1": f"{top1}/{total} = {top1 / total:.4f}",
        "recall@5": f"{top5}/{total} = {top5 / total:.4f}",
        "recall@10": f"{top10}/{total} = {top10 / total:.4f}",
        "mrr@10": round(mrr, 4),
        "p50_ms": p50,
        "p95_ms": p95,
        "not_in_top10": total - top10,
    }


def per_mode(cases):
    modes = {}
    for line in EVAL.read_text(encoding="utf-8").splitlines():
        entry = json.loads(line)
        modes.setdefault(entry["queryMode"], []).append(entry["id"])
    result = {}
    for mode, ids in modes.items():
        sub = {i: cases[i] for i in ids}
        total = len(sub)
        top1 = sum(1 for c in sub.values() if c["rank"] == 1)
        top10 = sum(1 for c in sub.values() if c["rank"] is not None and c["rank"] <= 10)
        result[mode] = f"top1 {top1}/{total} = {top1 / total:.4f} | top10 {top10}/{total} = {top10 / total:.4f}"
    return result


def main():
    if len(sys.argv) != 3:
        print("usage: compare-code-reports.py <baseline.json> <experiment.json>")
        sys.exit(1)
    base = load_report(sys.argv[1])
    exp = load_report(sys.argv[2])
    print(f"baseline : {sys.argv[1]}")
    print(f"experiment: {sys.argv[2]}")
    print()
    bm = metrics(base)
    em = metrics(exp)
    print(f"{'metric':<14}{'baseline':>24}{'experiment':>24}")
    for key in ["total", "recall@1", "recall@5", "recall@10", "mrr@10", "p50_ms", "p95_ms", "not_in_top10"]:
        print(f"{key:<14}{str(bm[key]):>24}{str(em[key]):>24}")
    print()
    print("per query mode (baseline -> experiment):")
    bpm = per_mode(base)
    epm = per_mode(exp)
    for mode in bpm:
        print(f"  {mode:<22}{bpm[mode]}")
        print(f"  {'':22}{epm[mode]}")
    print()
    changed = []
    for cid in sorted(base):
        br, er = base[cid]["rank"], exp[cid]["rank"]
        if br != er:
            changed.append((cid, br, er))
    improved = [c for c in changed if c[2] == 1 and c[1] != 1]
    regressed = [c for c in changed if c[1] == 1 and c[2] != 1]
    print(f"rank changes: {len(changed)} (improved-to-1: {len(improved)}, regressed-from-1: {len(regressed)})")
    if regressed:
        print("regressions:")
        for cid, br, er in regressed:
            print(f"  {cid}: {br} -> {er}")
    if improved:
        print("improved to rank1:")
        for cid, br, er in improved:
            print(f"  {cid}: {br} -> {er}")


if __name__ == "__main__":
    main()
