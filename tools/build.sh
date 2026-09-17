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
#   bash tools/build.sh                                   # 默认 :app:assembleDebug
#   bash tools/build.sh :app:testDebugUnitTest
#   bash tools/build.sh :app:assembleDebug :app:assembleRelease   # 可传多个任务
#
# 注意：Gradle JVM 在受限沙箱内无网络（DNS 失败），首次构建需在沙箱外执行。

set -e

PROJ="/e/AI/OfflineLedger"
if [ $# -eq 0 ]; then set -- :app:assembleDebug; fi

export JAVA_HOME="C:\\Users\\zongb\\.workbuddy\\toolchains\\jdk\\jdk-17.0.20.1+1"
export ANDROID_HOME="C:\\Users\\zongb\\.workbuddy\\toolchains\\android-sdk"
GRADLE="/c/Users/zongb/.workbuddy/toolchains/gradle/gradle-8.7/bin/gradle.bat"

cd "$PROJ"
echo "==> gradle $*  (工程: $PROJ)"
"$GRADLE" "$@" --no-daemon
