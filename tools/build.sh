#!/usr/bin/env bash
# 用途：无头构建（不需要 Android Studio）
#
# 工程路径 E:\AI\OfflineLedger 为纯 ASCII —— 不存在中文路径导致的
# AGP 拒构建 / 单测 worker 类加载失败问题，可直接在本目录构建。
# （2026-09-16 迁移前的历史包袱与踩坑记录见 tools/mirror_build.sh.bak 或记忆日志）
#
# 工具链（装在 C 盘用户目录，非项目文件）：
#   JDK17       ~/.workbuddy/toolchains/jdk/jdk-17.0.20.1+1
#   Gradle 8.7  ~/.workbuddy/toolchains/gradle/gradle-8.7
#   Android SDK ~/.workbuddy/toolchains/android-sdk（platform-34 + build-tools 34.0.0）
#
# 用法：
#   bash tools/build.sh                                   # 默认 :app:assembleDebug（两个 flavor 的 debug 都出）
#   bash tools/build.sh :app:testOfflineDebugUnitTest        # 单测（注意：现在必须带 flavor）
#   bash tools/build.sh :app:assembleOfflineRelease
#   bash tools/build.sh :app:assembleDebug :app:assembleRelease   # 可传多个任务
#
# ⚠️ 2026-09-17 起引入了 productFlavors（offline / plus），**变体任务名全部带 flavor**：
#     旧：:app:testDebugUnitTest      新：:app:testOfflineDebugUnitTest
#     旧：:app:assembleRelease        新：:app:assembleOfflineRelease
#   产物名也从 app-debug.apk 变成 app-offline-debug.apk。
#   跑之前记不住名字就直接：bash tools/build.sh :app:tasks
#
# 注意：Gradle JVM 在受限沙箱内无网络（DNS 失败），首次构建需在沙箱外执行。

set -e

PROJ="/e/AI/OfflineLedger"
if [ $# -eq 0 ]; then set -- :app:assembleDebug; fi

# ---------------------------------------------------------------- 环境自愈
# 本脚本必须自给自足：调用方的 PATH / TEMP 可能是不完整的。
# 2026-09-17 踩过的坑：外部 shell 的 PATH 丢了 Git 的 usr/bin，导致
# shell-runtime-bash-env.sh 第 3 行 `dirname` / `cd` 就失败，它本该注入的
# TEMP/TMP 于是没进环境 → Gradle 子进程拿不到 java.io.tmpdir → 回退到
# C:\WINDOWS → Kotlin 编译器写 kotlin-compiler-*.alive 时 AccessDeniedException。
# 症状看起来像「Kotlin 编译失败」，实际与代码无关。故在此两处都兜住。

for d in "/c/Program Files/Git/usr/bin" "/c/Program Files (x86)/Git/usr/bin"; do
  if [ -d "$d" ]; then case ":$PATH:" in *":$d:"*) ;; *) PATH="$d:$PATH" ;; esac; fi
done
export PATH

# Gradle 是 Windows 进程，TEMP 必须是 Windows 风格路径
TMP_WIN="C:\\Users\\zongb\\AppData\\Local\\Temp"
mkdir -p "/c/Users/zongb/AppData/Local/Temp" 2>/dev/null || true
if [ ! -d "/c/Users/zongb/AppData/Local/Temp" ]; then
  # 兜底：用户 Temp 不可用时退到工程内的 build/tmp（构建产物目录，不进版本库）
  TMP_WIN="E:\\AI\\OfflineLedger\\build\\tmp"
  mkdir -p "$PROJ/build/tmp"
fi
export TEMP="$TMP_WIN" TMP="$TMP_WIN" TMPDIR="$TMP_WIN"

export JAVA_HOME="C:\\Users\\zongb\\.workbuddy\\toolchains\\jdk\\jdk-17.0.20.1+1"
export ANDROID_HOME="C:\\Users\\zongb\\.workbuddy\\toolchains\\android-sdk"
GRADLE="/c/Users/zongb/.workbuddy/toolchains/gradle/gradle-8.7/bin/gradle.bat"

cd "$PROJ"
echo "==> gradle $*  (工程: $PROJ)"
echo "==> TEMP=$TEMP"
"$GRADLE" "$@" --no-daemon

# ---------------------------------------------------------------- 产物汇总
# 把构建出的 APK 同步到 dist/，供侧载取用。
#
# 2026-09-17 踩到：dist/ 里还躺着上一轮的 app-offline-debug.apk（3748 KB），
# 而新构建的产物在 app/build/outputs/apk/ 下。两者名字一样、大小不同，
# 直接去 dist/ 拿包就会**装到旧版本**，而且从文件上完全看不出来。
# 所以只要这次跑了 assemble，就把 dist/ 重刷一遍；只跑单测时不动它，
# 免得把旧 APK 的 mtime 刷新成「刚构建的样子」，制造新的假象。
case " $* " in
  *assemble*)
    mkdir -p dist
    find app/build/outputs/apk -name '*.apk' -type f -exec cp -f {} dist/ \;
    echo "==> 已同步产物到 dist/："
    ls -l dist/*.apk | awk '{printf "    %s  %d KB\n", $NF, $5/1024}'
    ;;
esac
