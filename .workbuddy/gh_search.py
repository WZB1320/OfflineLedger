#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""GitHub 技术选型调研：为「离线记账 App」找可复用的开源底子。

用法: python .workbuddy/gh_search.py
输出: 每个查询打印 星数 / 语言 / 协议 / 最近更新 / 仓库 / 描述
"""
import json
import urllib.parse
import urllib.request
import sys

QUERIES = [
    ("A. 记账 App 整体", "accounting app android ledger"),
    ("A. 记账 App 整体(中文)", "记账 android"),
    ("B. 通知抓支付", "notification listener payment transaction"),
    ("B. 通知抓支付(中文)", "通知监听 记账"),
    ("C. 账单解析规则", "alipay wechat bill parser"),
    ("C. 账单转 Beancount", "beancount alipay wechat importer"),
    ("D. xlsx 读取", "xlsx reader java streaming"),
    ("D. xlsx 读取(Android)", "excel android read xlsx"),
]

API = "https://api.github.com/search/repositories"


def search(q, per_page=8):
    url = API + "?" + urllib.parse.urlencode({
        "q": q, "sort": "stars", "order": "desc", "per_page": str(per_page),
    })
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "ledger-research",
    })
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.load(r)
    except Exception as e:  # noqa: BLE001
        print(f"  !! 请求失败: {e}", file=sys.stderr)
        return None


def main():
    for label, q in QUERIES:
        print(f"\n{'=' * 78}\n{label}  |  query: {q}\n{'=' * 78}")
        data = search(q)
        if not data or not data.get("items"):
            print("  (无结果)")
            continue
        for it in data["items"]:
            lic = (it.get("license") or {}).get("spdx_id") or "无"
            desc = (it.get("description") or "").replace("\n", " ")[:80]
            print(f"  ★{it['stargazers_count']:<6} {it.get('language') or '-':<12} "
                  f"{lic:<14} {it['pushed_at'][:10]}")
            print(f"          {it['full_name']}")
            print(f"          {desc}")


if __name__ == "__main__":
    main()
