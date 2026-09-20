#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
装包前的「启动期配置」自检（tools/verify_launch.py）

## 为什么需要它

编译通过、单测全绿，都不代表 App 能启动。这类缺陷的共同点是：
**错误发生在 Android 框架解析配置/创建 Activity 的阶段，任何 JVM 单测都碰不到**，
只有真机点开图标才会暴露 —— 表现统一是「点开就闪退」，而用户看到的只是闪退，
连日志都拿不到。

踩过的实例（2026-09-19）：`MainActivity` 继承 `AppCompatActivity`，但 manifest 的
`application` / `activity` 都没有 `android:theme`，于是框架套上系统默认主题，
AppCompatActivity 在 onCreate 前就抛：

    IllegalStateException: You need to use a Theme.AppCompat theme
                          (or descendant) with this activity.

主题定义在 `res/values/themes.xml` 里躺着、parent 也是对的，只是**没人引用它**。
编译期不检查这种「声明与引用脱节」，所以必须单独验。

## 检查项（全部只读 APK + 源码，不需要设备）
1. 存在带 MAIN/LAUNCHER 的 activity，且显式声明了 android:exported（targetSdk 31+ 硬要求）
2. 启动 activity 最终生效的 theme 非空（activity 级 → application 级依次回退）
3. 该 theme 的资源 id 能在资源表里解析出名字（不是悬空引用）
4. 若启动 activity 继承 androidx 的 AppCompatActivity，其 theme 的 parent 链必须含 AppCompat
5. application 的 android:name 指定的 Application 类在 dex 中存在
6. manifest 里声明的全部自有组件类（activity/service/receiver/provider）在 dex 中存在

任何一项拿不到证据即判失败 —— 不允许「查不到就跳过然后报通过」。
"""

import os
import re
import subprocess
import sys
import tempfile
import zipfile
import glob
from pathlib import Path

APPCOMPAT_BASES = ("Theme.AppCompat", "Theme.MaterialComponents", "Theme.Material3")


def find_tool(name: str) -> str:
    """在 ANDROID_HOME / 本机 toolchains 里找 aapt / dexdump。"""
    cands = []
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(env)
        if root:
            cands += glob.glob(os.path.join(root, "build-tools", "*", name + ".exe"))
            cands += glob.glob(os.path.join(root, "build-tools", "*", name))
    home = str(Path.home()).replace("\\", "/")
    for root in (f"{home}/.workbuddy/toolchains/android-sdk/build-tools",
                 f"{home}/AppData/Local/Android/Sdk/build-tools"):
        # 注意：不要用 Path 运算符拼这种含 * 的模式，Path 会把 "*/" 规范化成 "*"
        cands += glob.glob(f"{root}/*/{name}.exe")
        cands += glob.glob(f"{root}/*/{name}")
    exist = [c for c in cands if os.path.exists(c)]
    if not exist:
        sys.exit(f"FAIL: 找不到 {name}（需要 Android SDK build-tools）")
    return sorted(exist)[-1]          # 取版本号最大的


def out(cmd) -> str:
    p = subprocess.run(cmd, capture_output=True, text=True, errors="replace")
    return p.stdout


def main() -> int:
    apk = sys.argv[1] if len(sys.argv) > 1 else "dist/app-offline-debug.apk"
    src = Path(sys.argv[2] if len(sys.argv) > 2 else "app/src/main")
    if not os.path.exists(apk):
        print(f"FAIL: APK 不存在: {apk}")
        return 1

    aapt = find_tool("aapt")
    dexdump = find_tool("dexdump")
    wapk = str(Path(apk).resolve()).replace("\\", "/")   # Windows exe 读不了 /c/... 路径

    manifest = out([aapt, "dump", "xmltree", wapk, "AndroidManifest.xml"])
    resources = out([aapt, "dump", "resources", wapk])
    if not manifest.strip():
        print("FAIL: 读不出 AndroidManifest.xml")
        return 1

    failures, notes = [], []

    # ---------- 解析 manifest（按行扫描，靠缩进判断父子关系） ----------
    def attr(line: str, key: str):
        m = re.search(r"A: android:%s\(0x[0-9a-f]+\)=(?:\(type 0x12\))?\"?([^\"]*)\"?" % key, line)
        return m.group(1) if m else None

    app_name = app_theme = None
    launcher = None            # (name, exported, theme)
    components = []            # 自有组件类名
    cur_kind = cur_name = cur_exported = cur_theme = None
    has_launcher_intent = False

    for line in manifest.splitlines():
        s = line.strip()
        m = re.match(r"E: (application|activity|service|receiver|provider)", s)
        if m:
            if cur_kind in ("activity", "service", "receiver", "provider") and cur_name:
                if not cur_name.startswith(("androidx.", "android.")):
                    components.append(cur_name)
                if cur_kind == "activity" and has_launcher_intent:
                    launcher = (cur_name, cur_exported, cur_theme)
            cur_kind, cur_name, cur_exported, cur_theme = m.group(1), None, None, None
            has_launcher_intent = False
            continue
        if "MAIN" in s or "LAUNCHER" in s:
            has_launcher_intent = True
        if "android:name" in s:
            v = attr(s, "name")
            if cur_kind == "application":
                app_name = v
            elif cur_kind:
                cur_name = cur_name or v
        if cur_kind and "android:exported" in s:
            cur_exported = attr(s, "exported")
        if cur_kind and "android:theme" in s:
            m = re.search(r"@(0x[0-9a-f]+)", s)
            cur_theme = m.group(1) if m else attr(s, "theme")
        if cur_kind == "application" and "android:theme" in s:
            m = re.search(r"@(0x[0-9a-f]+)", s)
            app_theme = m.group(1) if m else attr(s, "theme")
    # 收尾最后一个组件
    if cur_kind in ("activity", "service", "receiver", "provider") and cur_name:
        if not cur_name.startswith(("androidx.", "android.")):
            components.append(cur_name)
        if cur_kind == "activity" and has_launcher_intent:
            launcher = (cur_name, cur_exported, cur_theme)

    # ---------- 1. launcher activity ----------
    if not launcher:
        failures.append("manifest 里找不到带 MAIN/LAUNCHER 的 activity —— 装上后没有图标可点")
    else:
        lname, lexported, ltheme = launcher
        if lexported is None:
            failures.append(f"启动 activity {lname} 未显式声明 android:exported（targetSdk 31+ 会安装/启动失败）")
        elif lexported not in ("true", "0xffffffff"):
            failures.append(f"启动 activity {lname} 的 exported={lexported}，launcher 必须为 true")

    # ---------- 2/3. theme 解析 ----------
    eff_theme = (launcher[2] if launcher else None) or app_theme
    theme_style = None
    if not eff_theme or eff_theme in ("", "@null"):
        failures.append(
            "启动 activity 最终生效的 theme 为空（application 与 activity 都没声明 android:theme）。"
            "\n        若 Activity 继承的是 AppCompatActivity，框架会用系统默认主题，"
            "必然抛『You need to use a Theme.AppCompat theme』→ 点开即闪退"
        )
    else:
        m = re.search(r"spec resource %s .*?:style/([^:]+):" % re.escape(eff_theme), resources)
        if m:
            theme_style = m.group(1)
            notes.append(f"生效主题: {theme_style} ({eff_theme})")
        else:
            m2 = re.search(r"resource %s .*?:style/([^:]+)" % re.escape(eff_theme), resources)
            if m2:
                theme_style = m2.group(1)
                notes.append(f"生效主题: {theme_style} ({eff_theme})")
            else:
                failures.append(f"theme 资源 {eff_theme} 在资源表里解析不出名字（悬空引用）")

    # ---------- 4. AppCompat 血统 ----------
    lname = launcher[0] if launcher else None
    inherits_appcompat = False
    if lname:
        kt = list(src.rglob("*.kt"))
        for f in kt:
            text = f.read_text(encoding="utf-8", errors="replace")
            if re.search(r"class\s+%s\b" % re.escape(lname.split(".")[-1]), text):
                if re.search(r"class\s+\w+\s*:\s*AppCompatActivity", text):
                    inherits_appcompat = True
                break
    if inherits_appcompat:
        if theme_style:
            themes = list(src.rglob("themes.xml")) + list(src.rglob("styles.xml"))
            parent = None
            for f in themes:
                text = f.read_text(encoding="utf-8", errors="replace")
                m = re.search(r'<style name="%s"\s+parent="([^"]+)"' % re.escape(theme_style), text)
                if m:
                    parent = m.group(1)
                    break
            if parent is None:
                failures.append(f"启动 activity 继承 AppCompatActivity，但源码里找不到主题 {theme_style} 的定义")
            else:
                # 允许 parent 再继承一层（简易追溯：找 parent 的定义）
                resolved, seen = parent, {theme_style}
                for _ in range(4):
                    if any(b in resolved for b in APPCOMPAT_BASES):
                        break
                    nxt = None
                    for f in themes:
                        text = f.read_text(encoding="utf-8", errors="replace")
                        m = re.search(r'<style name="%s"\s+parent="([^"]+)"' % re.escape(resolved), text)
                        if m:
                            nxt = m.group(1)
                            break
                    if not nxt or nxt in seen:
                        break
                    seen.add(nxt)
                    resolved = nxt
                if any(b in resolved for b in APPCOMPAT_BASES):
                    notes.append(f"AppCompat 血统: {theme_style} → … → {resolved}")
                else:
                    failures.append(
                        f"启动 activity 是 AppCompatActivity，但主题 {theme_style} 的 parent 链"
                        f"（当前解析到 {resolved}）不含 AppCompat —— 启动必崩"
                    )

    # ---------- 5/6. 类是否都在 dex 里 ----------
    # 解包到**系统临时目录**：早先写在 APK 同级目录下，结果 8 MB 的 classes.dex
    # 被当成工程文件提交进了版本库（.gitignore 只挡了 *.apk，没挡 *.dex）。
    # 临时目录随 with 退出自动清理，且不再依赖外部 unzip 命令。
    dex_classes = set()
    with tempfile.TemporaryDirectory(prefix="verify_launch_") as td:
        tdp = Path(td)
        with zipfile.ZipFile(apk) as z:
            for n in z.namelist():
                if re.fullmatch(r"classes\d*\.dex", n):
                    z.extract(n, tdp)
        for d in sorted(tdp.glob("classes*.dex")):
            dump = out([dexdump, "-d", str(d).replace("\\", "/")])
            for line in dump.splitlines():
                m = re.search(r"Class descriptor\s+: 'L([^';]+);'", line)
                if m:
                    dex_classes.add(m.group(1).replace("/", "."))

    if app_name:
        if app_name not in dex_classes:
            failures.append(f"Application 类 {app_name} 不在 dex 中（android:name 写错或缺依赖）")
        else:
            notes.append(f"Application 类在 dex: {app_name}")
    else:
        failures.append("manifest 的 application 没有 android:name —— 自定义 Application 不会初始化")

    missing = [c for c in components if c not in dex_classes]
    if missing:
        failures.append("以下组件类不在 dex 中: " + ", ".join(missing))
    else:
        notes.append(f"manifest 声明的 {len(components)} 个自有组件类均在 dex 中")

    # ---------- 输出 ----------
    print(f"== 启动期配置自检: {apk}")
    for n in notes:
        print(f"   · {n}")
    if failures:
        print(f"\n结果: FAIL（{len(failures)} 项）")
        for f in failures:
            print(f"   [FAIL] {f}")
        return 1
    print("\n结果: PASS —— 启动期配置齐备（真机仍可能出现运行时异常，见脚本头注释）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
