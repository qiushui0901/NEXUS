#!/usr/bin/env python3
"""代码召回质量评测：对一组代表性查询打 NEXUS 代码检索，记录耗时与 top-N 命中。

用法: python3 tools/code-recall-eval.py --url http://127.0.0.1:8082 --project-id shiguang-eval [--limit 3] [--output target/code-recall-eval.md]
"""
from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request

QUERIES = [
    # 登录与认证
    "用户注册登录", "用户注册", "登录", "验证码登录", "签发访问令牌",
    "发送短信验证码", "验证码校验", "验证码过期", "短信发送频率限制",
    # 笔记
    "发布笔记", "编辑笔记", "删除笔记", "笔记列表", "笔记详情",
    "收藏笔记", "取消收藏", "点赞笔记", "笔记评论列表",
    # 评论
    "发布评论", "回复评论", "删除评论", "评论点赞", "评论列表",
    # 关注关系
    "关注用户", "取消关注", "粉丝列表", "关注列表", "拉黑用户",
    # 搜索
    "搜索笔记", "搜索用户", "热门搜索",
    # 上传
    "文件上传", "图片上传", "上传大小限制",
    # 推荐
    "推荐信息流", "热门内容", "关注内容流",
    # 计数
    "笔记计数", "评论计数", "计数不一致", "评论数不对",
    # 口语化
    "怎么取消点赞", "谁收藏了我的笔记", "我关注的用户发了新笔记", "限流防刷",
    # 精确符号（回归锚点）
    "loginAndRegister", "publishNote", "unlikeComment", "collectNote",
    "recallFromFollowing", "searchNote", "uploadFile", "recommend",
    # 英文
    "user login", "send sms code", "publish note", "follow user",
    "search notes", "upload file", "recommend feed", "unlike",
]


def search(url: str, api_key: str, project_id: str, query: str, limit: int) -> tuple[float, list[dict]]:
    body = json.dumps({"query": query, "projectId": project_id, "limit": limit}).encode("utf-8")
    req = urllib.request.Request(url + "/api/code/search", data=body, method="POST",
                                 headers={"X-API-Key": api_key, "Content-Type": "application/json"})
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            hits = json.load(response)
        return (time.perf_counter() - started) * 1000, hits
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")[:200]
        raise RuntimeError(f"HTTP {error.code}: {detail}") from error


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8082")
    parser.add_argument("--api-key", default="")
    parser.add_argument("--project-id", default="shiguang-eval")
    parser.add_argument("--limit", type=int, default=3)
    parser.add_argument("--output", default="")
    args = parser.parse_args()

    lines: list[str] = []
    latencies: list[float] = []
    failures = 0
    for query in QUERIES:
        try:
            elapsed, hits = search(args.url, args.api_key, args.project_id, query, args.limit)
        except Exception as error:  # noqa: BLE001
            failures += 1
            lines.append(f"| {query} | ERROR | {error} |")
            continue
        latencies.append(elapsed)
        top = "; ".join(f"{h['filePath'].split('/')[0]}:{h.get('symbolName', '')}:{h['startLine']}"
                        for h in hits) if hits else "(empty)"
        lines.append(f"| {query} | {elapsed:.0f}ms | {top} |")

    total = len(QUERIES)
    avg = sum(latencies) / len(latencies) if latencies else 0
    p95 = sorted(latencies)[int(len(latencies) * 0.95) - 1] if latencies else 0
    report = [
        f"# 代码召回评测（{total} 查询）",
        "",
        f"- 服务: {args.url} | 项目: {args.project_id} | top-{args.limit}",
        f"- 平均耗时: {avg:.0f}ms | P95: {p95:.0f}ms | 失败: {failures}",
        "",
        "| 查询 | 耗时 | top-3 命中 (模块:符号:行) |",
        "|---|---|---|",
        *lines,
    ]
    text = "\n".join(report) + "\n"
    print(text)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as stream:
            stream.write(text)
        print(f"\nreport written to {args.output}")


if __name__ == "__main__":
    main()
