#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第四轮：拉取关键源码，评估真实可复用度。"""
import urllib.request

H = {"User-Agent": "ledger-research"}

TARGETS = [
    ("XLSX 解析(Android/Kotlin)",
     "FridayKoi/WhereIsMyMoney", "app/src/main/java/com/friday/wimm/util/XLSXParser.kt", 70),
    ("通知文本解析(Kotlin)",
     "DykiSensei/seamless-bookkeeping",
     "app/src/main/java/com/bookkeeping/app/notification/NotificationParser.kt", 70),
    ("支付宝列名映射(Go)",
     "deb-sig/double-entry-generator", "pkg/provider/alipay/types.go", 60),
    ("微信列名映射(Go)",
     "deb-sig/double-entry-generator", "pkg/provider/wechat/types.go", 60),
    ("支付宝 Python 解析",
     "abeet233/MoneyMate-android", "python/parsers/alipay.py", 50),
]


def raw(repo, path):
    for br in ("main", "master"):
        url = f"https://raw.githubusercontent.com/{repo}/{br}/{path}"
        try:
            req = urllib.request.Request(url, headers=H)
            with urllib.request.urlopen(req, timeout=25) as r:
                return r.read().decode("utf-8", "replace")
        except Exception:  # noqa: BLE001
            continue
    return None


for label, repo, path, n in TARGETS:
    print(f"\n{'=' * 78}\n{label}\n{repo}/{path}\n{'=' * 78}")
    txt = raw(repo, path)
    if not txt:
        print("  !! 拉取失败")
        continue
    lines = txt.splitlines()
    print(f"  [总行数 {len(lines)}]")
    for ln in lines[:n]:
        print("  " + ln)
