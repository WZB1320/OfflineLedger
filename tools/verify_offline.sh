#!/usr/bin/env bash
# 构建后自检：确认两个 flavor 的权限与离线承诺一致，并报告体积。
#
# 用法：bash tools/verify_offline.sh
#
# 判据设计 —— 这是 2026-09-17 重写的重点：
#   以 **APK 本身**为唯一判据（`aapt dump permissions` 直接读打包产物）。
#   旧版读的是 intermediates 里的合并 Manifest，而那份中间产物的路径随 AGP 版本变化：
#   路径一变，脚本就打一行「[跳过] 未找到合并 Manifest」然后继续，
#   最后照样输出「自检通过 ✓」——**等于根本没验，却给了通过的结论**。
#
#   2026-09-17 引入 productFlavors 后的第二个教训：**「不存在」不能一律当「通过」**。
#   产物名从 app-debug.apk 变成 app-offline-debug.apk，如果脚本还盯着旧名字，
#   就会退化成「文件不存在 → 跳过 → 报通过」。所以现在的规则是：
#     - 一个 APK 都没找到  → 失败（没有任何证据）
#     - offline 版没构建   → 失败（它承载核心的离线承诺，不能缺席）
#     - plus 版没构建      → 提示（不冒充已验证，但也不阻塞）
#     - 找到了的，按各自 flavor 的期望断言

set -uo pipefail

cd "$(dirname "$0")/.."

AAPT="$HOME/.workbuddy/toolchains/android-sdk/build-tools/34.0.0/aapt.exe"
APK_ROOT="app/build/outputs/apk"
FAIL=0

if [ ! -f "$AAPT" ]; then
  echo "[失败] 找不到 aapt：$AAPT"
  echo "       无法读取 APK 权限 —— 没有证据就不能判通过。"
  exit 1
fi

# flavor → 期望是否含有 INTERNET 权限
#   offline：必须【不】含（物理断网是它的全部意义）
#   plus   ：目前也「不含」——因为 RuleUpdater 是 P3 才做，此刻 plus 版没有网络代码。
#            ⚠️ P3 把 RuleUpdater 放进 app/src/plus/ 并声明 INTERNET 后，这里改成 yes。
expected_internet() {
  case "$1" in
    plus) echo "no" ;;   # ← P3 解冻时改为 yes
    *)    echo "no" ;;
  esac
}

# 从文件名解析 flavor：app-offline-debug.apk → offline
flavor_of() {
  local base
  base=$(basename "$1")
  case "$base" in
    app-offline-*) echo "offline" ;;
    app-plus-*)    echo "plus" ;;
    app-*)         echo "unknown" ;;   # 旧的单一变体命名
    *)             echo "unknown" ;;
  esac
}

# 收集所有已构建的 APK
APKS=()
while IFS= read -r f; do
  [ -n "$f" ] && APKS+=("$f")
done < <(find "$APK_ROOT" -name '*.apk' -type f 2>/dev/null | sort)

echo "=== 1. 逐 APK 检查实际申请的权限（aapt 直读打包产物）==="
if [ "${#APKS[@]}" -eq 0 ]; then
  echo "   [失败] 在 $APK_ROOT 下一个 APK 都没找到。"
  echo "          先构建：bash tools/build.sh"
  echo "          —— 没有任何证据时不允许判通过。"
  exit 1
fi

SEEN_OFFLINE=0
for apk in "${APKS[@]}"; do
  flavor=$(flavor_of "$apk")
  echo "-- $(basename "$apk")  [flavor: $flavor]"

  PERMS=$("$AAPT" dump permissions "$apk" 2>/dev/null | sed -n "s/^uses-permission: name='\(.*\)'\$/\1/p")
  if [ -z "$PERMS" ]; then
    echo "   [失败] aapt 未能读出权限列表（判据缺失）"
    FAIL=1
    continue
  fi
  echo "$PERMS" | sed 's/^/          /'

  has_internet=0
  echo "$PERMS" | grep -qx 'android.permission.INTERNET' && has_internet=1
  want=$(expected_internet "$flavor")

  if [ "$want" = "no" ]; then
    if [ "$has_internet" -eq 1 ]; then
      echo "   [失败] 出现 INTERNET 权限 —— 离线承诺被破坏！"
      echo "          多半是某个第三方依赖通过 manifest 合并偷偷带进来的。"
      FAIL=1
    else
      echo "   [通过] 无 INTERNET 权限（内核层面无法建立网络连接）"
    fi
  else
    if [ "$has_internet" -eq 1 ]; then
      echo "   [通过] 含 INTERNET 权限（该 flavor 的设计如此）"
    else
      echo "   [失败] $flavor 版应当声明 INTERNET 但实际没有 —— 规则在线更新会不可用"
      FAIL=1
    fi
  fi

  [ "$flavor" = "offline" ] && SEEN_OFFLINE=1

  SIZE_KB=$(( ( $(stat -c %s "$apk") + 1023 ) / 1024 ))
  echo "   [体积] ${SIZE_KB} KB"
done

# 覆盖率：offline 版承载核心承诺，缺席就不能判通过
if [ "$SEEN_OFFLINE" -eq 0 ]; then
  echo
  echo "   [失败] 未找到 offline 版产物 —— 它承载核心的离线承诺，不能缺席。"
  echo "          构建：bash tools/build.sh :app:assembleOfflineDebug :app:assembleOfflineRelease"
  FAIL=1
fi
case " ${APKS[*]} " in
  *app-plus-*) : ;;
  *) echo
     echo "   [提示] 无 plus 版产物（未构建则无从验证，不作为失败；"
     echo "          构建：bash tools/build.sh :app:assemblePlusDebug :app:assemblePlusRelease）" ;;
esac

echo
echo "=== 2. 网络相关类残留（信息项，不作为判据）==="
# 即便 dex 里存在 socket 类，只要没有 INTERNET 权限就无法真正通信；
# 而 AndroidX 本身就会引用这些类，所以这里只报告、不判定。
if command -v unzip >/dev/null 2>&1; then
  for apk in "${APKS[@]}"; do
    REFS=$(unzip -p "$apk" 'classes*.dex' 2>/dev/null | grep -ao 'Ljava/net/Socket' | wc -l)
    echo "   $(basename "$apk"): Ljava/net/Socket 引用 $REFS 处"
  done
else
  echo "   [跳过] 环境无 unzip，无法检查（不影响上面的权限判定）"
fi

echo
echo "=== 3. 合并后的 Manifest（交叉参考）==="
# 注意：必须匹配 <uses-permission ...> 元素，不能只 grep 裸字符串。
# app/src/main/AndroidManifest.xml 里有一大段「离线保证」注释，正文里就写着
# android.permission.INTERNET —— 只 grep 字符串会把这行注释误判成真声明
# （2026-09-17 实际踩到过：APK 干净，脚本却报失败）。
MERGED_COUNT=0
while IFS= read -r m; do
  [ -n "$m" ] || continue
  MERGED_COUNT=$((MERGED_COUNT + 1))
  case "$m" in
    *offline*) label="offline" ;;
    *plus*)    label="plus" ;;
    *)         label="?" ;;
  esac
  if grep -qE '<uses-permission[^>]*android\.permission\.INTERNET' "$m"; then
    echo "   [$label] 声明了 INTERNET：$m"
    grep -nE '<uses-permission[^>]*android\.permission\.INTERNET' "$m" | sed 's/^/          /'
    # 这里只报告，不判定 —— 判定以上面的 APK 为准（中间产物路径与命名都可能变）
  else
    echo "   [$label] 未声明 INTERNET"
  fi
done < <(find app/build/intermediates -path '*merged_manifest*' -name AndroidManifest.xml 2>/dev/null | sort)
[ "$MERGED_COUNT" -eq 0 ] && echo "   [提示] 未找到中间产物（不影响判定，判据是上面的 APK）"

echo
if [ "$FAIL" -eq 1 ]; then
  echo "结果：自检未通过 ✗"
  exit 1
fi
echo "结果：自检通过 ✓"
