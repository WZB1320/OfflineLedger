#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第六轮：核实 AutoAccounting（★911）的技术栈、能力边界与规则仓库。"""
import json
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


it = get("https://api.github.com/repos/AutoAccountingOrg/AutoAccounting")
if isinstance(it, dict) and it.get("full_name"):
    lic = (it.get("license") or {}).get("spdx_id") or "无"
    print("=" * 78)
    print(f"{it['full_name']}   ★{it['stargazers_count']}   {it.get('language')}   {lic}")
    print(f"  最后推送 {it['pushed_at'][:10]}   创建 {it['created_at'][:10]}   "
          f"体积 {it['size']}KB   fork {it['forks_count']}")
    print(f"  主页 {it.get('homepage')}")
    print(f"  描述 {it.get('description')}")
    print("=" * 78)

print("\n--- README（前 70 行）---")
t = get("https://raw.githubusercontent.com/AutoAccountingOrg/AutoAccounting/master/README.md", raw=True)
if not isinstance(t, str) or t.startswith("!!"):
    t = get("https://raw.githubusercontent.com/AutoAccountingOrg/AutoAccounting/main/README.md", raw=True)
if isinstance(t, str) and not t.startswith("!!"):
    for ln in t.splitlines()[:70]:
        print("  " + ln)
else:
    print(f"  {t}")

print("\n--- 仓库结构：与抓取/解析/导入相关的目录 ---")
d = get("https://api.github.com/repos/AutoAccountingOrg/AutoAccounting/git/trees/master?recursive=1")
if not (isinstance(d, dict) and d.get("tree")):
    d = get("https://api.github.com/repos/AutoAccountingOrg/AutoAccounting/git/trees/main?recursive=1")
if isinstance(d, dict) and d.get("tree"):
    paths = [x["path"] for x in d["tree"]]
    print(f"  总文件数 {len(paths)}")
    kw = ("rule", "notif", "accessib", "xlsx", "csv", "excel", "import", "bill", "parse", "shizuku", "xposed")
    hits = [p for p in paths if any(k in p.lower() for k in kw) and p.endswith((".kt", ".json", ".md", ".gradle", ".kts"))]
    for p in hits[:45]:
        print("    " + p)
    gradle = [p for p in paths if p.endswith("build.gradle.kts") or p.endswith("build.gradle") or "libs.versions" in p]
    print("\n  --- 依赖清单文件 ---")
    for p in gradle[:6]:
        print("    " + p)

print("\n--- 依赖版本清单（判断第三方依赖规模）---")
for p in ("gradle/libs.versions.toml", "app/build.gradle.kts", "app/build.gradle"):
    t = get(f"https://raw.githubusercontent.com/AutoAccountingOrg/AutoAccounting/master/{p}", raw=True)
    if isinstance(t, str) and not t.startswith("!!"):
        print(f"\n  ### {p}")
        for ln in t.splitlines():
            s = ln.strip()
            if s and not s.startswith("//") and any(k in s for k in ("=", "implementation", "api")):
                print("    " + s[:110])
        break

print("\n--- AutoRuleSubmit（MIT）：规则仓库结构 ---")
d = get("https://api.github.com/repos/AutoAccountingOrg/AutoRuleSubmit/git/trees/main?recursive=1")
if not (isinstance(d, dict) and d.get("tree")):
    d = get("https://api.github.com/repos/AutoAccountingOrg/AutoRuleSubmit/git/trees/master?recursive=1")
if isinstance(d, dict) and d.get("tree"):
    for x in d["tree"][:30]:
        print("    " + x["path"])
else:
    print(f"  {d}")
