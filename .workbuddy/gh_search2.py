#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第二轮：精准搜索 + 直接核实候选仓库。"""
import json
import urllib.parse
import urllib.request
import sys

API_SEARCH = "https://api.github.com/search/repositories"
API_REPO = "https://api.github.com/repos/"

QUERIES = [
    ("A. 成熟记账 App(Kotlin/Compose)", "expense tracker android kotlin compose"),
    ("A. 成熟记账 App(F-Droid 类)", "personal finance manager android offline sqlite"),
    ("B. 自动记账(无障碍)", "自动记账 微信 支付宝"),
    ("B. 自动记账(accessibility)", "auto bookkeeping accessibility wechat alipay"),
    ("C. 微信账单解析", "微信账单 解析"),
    ("D. fastexcel 生态", "fastexcel"),
]

REPOS = [
    "dhatim/fastexcel",
    "centic9/poi-on-android",
    "mtotschnig/MyExpenses",
    "Ivy-Apps/ivy-wallet",
    "deb-sig/double-entry-generator",
    "ZhiQing456/auto_bookkeeper",
    "Rueded/AiExpenseTracker",
]


def get(url):
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "ledger-research",
    })
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.load(r)
    except Exception as e:  # noqa: BLE001
        print(f"  !! {e}", file=sys.stderr)
        return None


def search(q, per_page=6):
    return get(API_SEARCH + "?" + urllib.parse.urlencode({
        "q": q, "sort": "stars", "order": "desc", "per_page": str(per_page),
    }))


def line(it, name_key="full_name"):
    lic = (it.get("license") or {}).get("spdx_id") or "无"
    return (it["stargazers_count"], it.get("language") or "-", lic,
            it["pushed_at"][:10], it[name_key],
            (it.get("description") or "").replace("\n", " ")[:90])


def main():
    for label, q in QUERIES:
        print(f"\n{'=' * 78}\n{label}  |  {q}\n{'=' * 78}")
        d = search(q)
        if not d or not d.get("items"):
            print("  (无结果)")
            continue
        for it in d["items"]:
            s, lang, lic, up, name, desc = line(it)
            print(f"  ★{s:<6} {lang:<12} {lic:<14} {up}  {name}")
            print(f"          {desc}")

    print(f"\n\n{'#' * 78}\n#  候选仓库直接核实\n{'#' * 78}")
    for full in REPOS:
        it = get(API_REPO + full)
        if not it or it.get("message"):
            print(f"\n  ?? {full}  -> 不存在或不可访问")
            continue
        lic = (it.get("license") or {}).get("spdx_id") or "无"
        print(f"\n  {it['full_name']}   ★{it['stargazers_count']}  {it.get('language')}  "
              f"{lic}  最后推送 {it['pushed_at'][:10]}  创建 {it['created_at'][:10]}")
        print(f"    归档={it.get('archived')}  体积={it.get('size')}KB  "
              f"fork={it.get('forks_count')}  issue={it.get('open_issues_count')}")
        print(f"    {(it.get('description') or '')[:150]}")
        print(f"    主页: {it.get('homepage')}")


if __name__ == "__main__":
    main()
