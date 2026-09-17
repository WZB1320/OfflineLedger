#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第三轮：核实候选物的可复用性。
1. double-entry-generator 的 alipay/wechat 规则文件（列名映射，Apache-2.0 可借鉴）
2. fastexcel 的依赖树（决定能否用在 Android）
3. KeepAccounts 的解析逻辑位置
"""
import json
import urllib.request
import sys

H = {"User-Agent": "ledger-research", "Accept": "application/vnd.github+json"}


def get(url, raw=False):
    req = urllib.request.Request(url, headers=H)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            data = r.read().decode("utf-8", "replace")
            return data if raw else json.loads(data)
    except Exception as e:  # noqa: BLE001
        return f"!! {e}"


def tree(repo, branch="master"):
    for b in (branch, "main"):
        d = get(f"https://api.github.com/repos/{repo}/git/trees/{b}?recursive=1")
        if isinstance(d, dict) and d.get("tree"):
            return [t["path"] for t in d["tree"]]
    return []


print("=" * 78)
print("1) double-entry-generator —— 找 alipay / wechat provider 规则文件")
print("=" * 78)
paths = tree("deb-sig/double-entry-generator")
for p in paths:
    low = p.lower()
    if ("provider" in low or "example" in low) and ("alipay" in low or "wechat" in low):
        if p.endswith((".go", ".example", ".yaml", ".yml", ".md")):
            print("  " + p)

print("\n" + "=" * 78)
print("2) fastexcel —— 依赖树（决定 Android 可用性）")
print("=" * 78)
for pom in ("fastexcel-reader/pom.xml", "pom.xml"):
    txt = get(f"https://raw.githubusercontent.com/dhatim/fastexcel/main/{pom}", raw=True)
    if isinstance(txt, str) and not txt.startswith("!!"):
        print(f"\n--- {pom} ---")
        inside = False
        for ln in txt.splitlines():
            s = ln.strip()
            if s.startswith("<dependencies"):
                inside = True
            if inside:
                if any(k in s for k in ("<artifactId>", "<groupId>", "<version>", "<scope>")):
                    print("   " + s)
                if s.startswith("</dependencies"):
                    inside = False
        break
print("\n--- LICENSE 头 ---")
lic = get("https://raw.githubusercontent.com/dhatim/fastexcel/main/LICENSE", raw=True)
if isinstance(lic, str):
    print("   " + " / ".join(lic.splitlines()[:3]))

print("\n" + "=" * 78)
print("3) 通知抓支付类项目 —— 看文件结构，判断可借鉴程度")
print("=" * 78)
for repo in ("FridayKoi/WhereIsMyMoney", "DykiSensei/seamless-bookkeeping",
             "abeet233/MoneyMate-android", "Rueded/AiExpenseTracker"):
    ps = tree(repo)
    print(f"\n  {repo}  文件数={len(ps)}")
    hits = [p for p in ps if any(k in p.lower() for k in
                                 ("notif", "parse", "bill", "import", "rule", "xlsx", "excel"))]
    for h in hits[:14]:
        print("     " + h)

print("\n" + "=" * 78)
print("4) KeepAccounts（★311）—— 解析逻辑在哪")
print("=" * 78)
ps = tree("MickLife/KeepAccounts_v2.0")
for p in ps:
    if p.endswith((".py", ".md")):
        print("  " + p)
