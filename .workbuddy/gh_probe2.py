#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第五轮：核实 AutoAccounting 系项目 + fastexcel 依赖 + 漏掉的那份 XLSXParser。"""
import json
import urllib.parse
import urllib.request

H = {"User-Agent": "ledger-research", "Accept": "application/vnd.github+json"}


def get(url, raw=False):
    try:
        req = urllib.request.Request(url, headers=H)
        with urllib.request.urlopen(req, timeout=25) as r:
            d = r.read().decode("utf-8", "replace")
            return d if raw else json.loads(d)
    except Exception as e:  # noqa: BLE001
        return f"!! {e}"


print("=" * 78)
print("1) 搜 AutoAccounting / 自动记账 系")
print("=" * 78)
for q in ("AutoAccounting", "auto accounting android 记账", "记账 自动 通知 支付宝"):
    d = get("https://api.github.com/search/repositories?" + urllib.parse.urlencode(
        {"q": q, "sort": "stars", "order": "desc", "per_page": 6}))
    print(f"\n--- {q} ---")
    if isinstance(d, dict) and d.get("items"):
        for it in d["items"]:
            lic = (it.get("license") or {}).get("spdx_id") or "无"
            print(f"  ★{it['stargazers_count']:<6} {it.get('language') or '-':<10} {lic:<13} "
                  f"{it['pushed_at'][:10]}  {it['full_name']}")
            print(f"          {(it.get('description') or '')[:100]}")
    else:
        print("  (无)")

print("\n" + "=" * 78)
print("2) AutoAccountingOrg 组织下的仓库")
print("=" * 78)
d = get("https://api.github.com/orgs/AutoAccountingOrg/repos?per_page=20&sort=updated")
if isinstance(d, list):
    for it in d:
        lic = (it.get("license") or {}).get("spdx_id") or "无"
        print(f"  ★{it['stargazers_count']:<6} {it.get('language') or '-':<10} {lic:<13} "
              f"{it['pushed_at'][:10]}  {it['full_name']}")
        print(f"          {(it.get('description') or '')[:110]}")
else:
    print(f"  {d}")

print("\n" + "=" * 78)
print("3) fastexcel-reader 依赖（决定 Android 可用性）")
print("=" * 78)
t = get("https://raw.githubusercontent.com/dhatim/fastexcel/main/fastexcel-reader/pom.xml", raw=True)
if isinstance(t, str) and not t.startswith("!!"):
    for ln in t.splitlines():
        s = ln.strip()
        if any(k in s for k in ("<artifactId>", "<groupId>", "<scope>", "<optional>")):
            print("   " + s)
else:
    d = get("https://api.github.com/repos/dhatim/fastexcel/git/trees/main?recursive=1")
    if isinstance(d, dict) and d.get("tree"):
        print("  路径猜测失败，仓库结构（前 25 项）：")
        for it in d["tree"][:25]:
            print("   " + it["path"])
    print(f"  raw 结果: {t}")

print("\n" + "=" * 78)
print("4) WhereIsMyMoney 的 XLSXParser.kt")
print("=" * 78)
for br in ("main", "master", "dev"):
    t = get(f"https://raw.githubusercontent.com/FridayKoi/WhereIsMyMoney/{br}/"
            "app/src/main/java/com/friday/wimm/util/XLSXParser.kt", raw=True)
    if isinstance(t, str) and not t.startswith("!!"):
        lines = t.splitlines()
        print(f"  [分支 {br}, 总行数 {len(lines)}]")
        for ln in lines[:45]:
            print("  " + ln)
        break
else:
    print("  !! 未找到（可能路径不同）")
