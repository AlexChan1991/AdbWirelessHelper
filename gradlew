#!/bin/sh

#
# Gradle 启动脚本（Unix / Git Bash / macOS / Linux）
#
# 说明：本脚本在标准 Gradle Wrapper 之外加了一步兜底 —— 若
#       gradle/wrapper/gradle-wrapper.jar 缺失（本仓库不附带二进制文件），
#       且系统 PATH 中存在 gradle，则自动生成 wrapper 后再继续。
#       详见 README 第 4.1 节。
#

##############################################################################
# 解析脚本所在目录（兼容符号链接）
##############################################################################
PRG="$0"
while [ -h "$PRG" ]; do
  ls=$(ls -ld "$PRG")
  link=$(expr "$ls" : '.*-> \(.*\)$')
  if expr "$link" : '/.*' >/dev/null; then
    PRG="$link"
  else
    PRG=$(dirname "$PRG")"/$link"
  fi
done
APP_HOME=$(cd "$(dirname "$PRG")" >/dev/null 2>&1 && pwd)

# ⚠️ MSYS / Git Bash / Cygwin 下 `pwd` 给出的是 POSIX 路径（形如 /d/Project/...），
#    而 java.exe 是**原生 Windows 程序**，只认 D:\... 这种形态。
#    平时靠 MSYS 的自动路径转换能蒙过去，但一旦用户设了 MSYS_NO_PATHCONV=1
#    （Docker 用户很常见），转换被关掉，-classpath 就会拿到 /d/... 导致
#    `ClassNotFoundException: org.gradle.wrapper.GradleWrapperMain`。
#    这里显式转一次，两种情况都能跑。其它平台（Linux/macOS）不受影响。
case "$(uname -s 2>/dev/null || echo unknown)" in
  CYGWIN*|MINGW*|MSYS*)
    if command -v cygpath >/dev/null 2>&1; then
      APP_HOME=$(cygpath -w "$APP_HOME")
    fi
    ;;
esac

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# ⚠️ 这里**不能**写成 '"-Xmx64m" "-Xms64m"'（内层带引号）：
#    末尾 `exec "$JAVACMD" $DEFAULT_JVM_OPTS …` 是不加引号展开的，
#    shell 只做单词切分、**不会**移除引号，于是 java 收到的第一个参数是
#    带字面引号的 "-Xmx64m"，会被当成主类名 →
#    `错误: 找不到或无法加载主类 "-Xmx64m"`（曾经在 Git Bash 下必现）。
#    不带内层引号时，-Xmx64m 与 -Xms64m 会被正常切成两个参数。
DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"

##############################################################################
# 定位 java
##############################################################################
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD=java
  if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: 找不到 JAVA_HOME，且 PATH 中没有 java。" >&2
    echo "       请安装 JDK 17（AGP 8.x 强制要求）后重试。" >&2
    exit 1
  fi
fi

##############################################################################
# 兜底：wrapper jar 缺失时尝试自动生成
##############################################################################
if [ ! -f "$CLASSPATH" ]; then
  echo "NOTE: 未找到 gradle/wrapper/gradle-wrapper.jar" >&2
  if command -v gradle >/dev/null 2>&1; then
    echo "      检测到系统 gradle，正在生成 wrapper ..." >&2
    (cd "$APP_HOME" && gradle wrapper --gradle-version 8.9 --distribution-type bin) || exit 1
    echo "      wrapper 已生成，继续执行。" >&2
  else
    echo "" >&2
    echo "      生成方式二选一：" >&2
    echo "        A. 安装 Gradle 8.9 后执行：gradle wrapper --gradle-version 8.9 --distribution-type bin" >&2
    echo "        B. 直接用 Android Studio 打开本工程，AS 会自动生成 wrapper（推荐）" >&2
    echo "" >&2
    exit 1
  fi
fi

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
  "-Dorg.gradle.appname=gradlew" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain "$@"
