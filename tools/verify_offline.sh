#!/usr/bin/env bash
# 离线承诺自检：确认最终打进 APK 的权限列表里没有 INTERNET，并报告体积构成。
#
# 用法：bash tools/verify_offline.sh
#
# 判据设计 —— 这是 2026-09-17 重写的重点：
#   以 **APK 本身**为唯一判据（`aapt dump permissions` 直接读打包产物）。
#   旧版读的是 intermediates 里的合并 Manifest，而那份中间产物的路径随 AGP 版本变化：
#   路径一变，脚本就打一行「[跳过] 未找到合并 Manifest」然后继续，
#   最后照样输出「自检通过 ✓」——**等于根本没验，却给了通过的结论**。
#   现在的规则是：拿不到证据 = 判失败，不允许「跳过」冒充「通过」。

set -uo pipefail

cd "$(dirname "$0")/.."

AAPT="$HOME/.workbuddy/toolchains/android-sdk/build-tools/34.0.0/aapt.exe"
APKS=(
  "app/build/outputs/apk/debug/app-debug.apk"
  "app/build/outputs/apk/release/app-release-unsigned.apk"
)
FAIL=0

if [ ! -x "$AAPT" ] && [ ! -f "$AAPT" ]; then
  echo "[失败] 找不到 aapt：$AAPT"
  echo "       无法读取 APK 权限 —— 没有证据就不能判通过。"
  exit 1
fi

echo "=== 1. 逐 APK 检查实际申请的权限（aapt 直读打包产物）==="
for apk in "${APKS[@]}"; do
  echo "-- $(basename "$apk")"
  if [ ! -f "$apk" ]; then
    echo "   [失败] APK 不存在。先构建：bash tools/build.sh :app:assembleDebug :app:assembleRelease"
    FAIL=1
    continue
  fi

  PERMS=$("$AAPT" dump permissions "$apk" 2>/dev/null | sed -n "s/^uses-permission: name='\(.*\)'\$/\1/p")
  if [ -z "$PERMS" ]; then
    echo "   [失败] aapt 未能读出权限列表（判据缺失）"
    FAIL=1
    continue
  fi
  echo "$PERMS" | sed 's/^/          /'

  if echo "$PERMS" | grep -qx 'android.permission.INTERNET'; then
    echo "   [失败] 存在 INTERNET 权限 —— 离线承诺被破坏！"
    echo "          多半是某个第三方依赖通过 manifest 合并偷偷带进来的。"
    FAIL=1
  else
    echo "   [通过] 无 INTERNET 权限（内核层面无法建立网络连接）"
  fi

  SIZE_KB=$(( ( $(stat -c %s "$apk") + 1023 ) / 1024 ))
  echo "   [体积] ${SIZE_KB} KB"
done

echo
echo "=== 2. 网络相关类残留（信息项，不作为判据）==="
# 即便 dex 里存在 socket 类，只要没有 INTERNET 权限就无法真正通信；
# 而 AndroidX 本身就会引用这些类，所以这里只报告、不判定。
if command -v unzip >/dev/null 2>&1; then
  for apk in "${APKS[@]}"; do
    [ -f "$apk" ] || continue
    REFS=$(unzip -p "$apk" 'classes*.dex' 2>/dev/null | grep -ao 'Ljava/net/Socket' | wc -l)
    echo "   $(basename "$apk"): Ljava/net/Socket 引用 $REFS 处"
  done
else
  echo "   [跳过] 环境无 unzip，无法检查（不影响上面的权限判定）"
fi

echo
echo "=== 3. 合并后的 Manifest（交叉参考）==="
MERGED=$(find app/build/intermediates -path '*merged_manifest*release*' -name AndroidManifest.xml 2>/dev/null | head -1)
if [ -z "$MERGED" ]; then
  echo "   [提示] 未找到中间产物（不影响判定，判据是上面的 APK）"
# 注意：必须匹配 <uses-permission ...> 元素，不能只 grep 裸字符串。
# app/src/main/AndroidManifest.xml 里有一大段「离线保证」注释，正文里就写着
# android.permission.INTERNET —— 只 grep 字符串会把这行注释误判成真声明
# （2026-09-17 实际踩到过：APK 干净，脚本却报失败）。
elif grep -qE '<uses-permission[^>]*android\.permission\.INTERNET' "$MERGED"; then
  echo "   [失败] $MERGED 中声明了 INTERNET"
  grep -nE '<uses-permission[^>]*android\.permission\.INTERNET' "$MERGED" | sed 's/^/          /'
  FAIL=1
else
  echo "   [通过] 未声明 INTERNET"
fi

echo
if [ "$FAIL" -eq 1 ]; then
  echo "结果：自检未通过 ✗"
  exit 1
fi
echo "结果：自检通过 ✓"
